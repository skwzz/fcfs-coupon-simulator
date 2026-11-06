package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.simulator.client.Verification;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실행 하나의 상태. 쓰는 쪽과 읽는 쪽이 나뉜다.
 * <ul>
 * <li>가상 사용자 스레드: 자기 칸의 상태를 바꾸고 changed 큐에 번호를 넣는다.</li>
 * <li>ticker 스레드: 200ms마다 큐를 비워서 바뀐 사용자와 집계 지표를 SSE로 보낸다.</li>
 * </ul>
 * 사용자 배열은 큐를 거쳐 읽기 때문에, 큐에서 꺼낸 번호의 상태는 최신 값이 보인다.
 */
final class SimulationRun {

	static final long TICK_MS = 200;
	private static final int TPS_WINDOW = (int) (1000 / TICK_MS);

	final long id;
	final long eventId;
	final SimulationRequest config;
	final StrategyType strategy;
	final int stock;
	final int userCount;
	final Instant startedAt = Instant.now();

	// 가상 사용자별 상태. 인덱스 i = userId - 1
	private final byte[] status;
	private final long[] requestedAtNs;
	private final long[] completedAtNs;
	private final String[] reason;
	private final ConcurrentLinkedQueue<Integer> changed = new ConcurrentLinkedQueue<>();

	// 지연시간(µs). Recorder는 여러 스레드가 동시에 기록해도 되고, 구간별로 잘라서 꺼낼 수 있다
	private final Recorder recorder = new Recorder(3);
	private final Histogram total = new Histogram(3);
	private final Histogram currentSecond = new Histogram(3);
	private final List<Messages.SeriesPoint> series = new ArrayList<>();
	private final AtomicInteger completed = new AtomicInteger();

	// ticker 스레드만 쓰는 값
	private final int[] tpsWindow = new int[TPS_WINDOW];
	private int tickCount;
	private int lastCompleted;

	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	volatile RunState state = RunState.RUNNING;
	volatile boolean stopRequested;
	volatile ExecutorService users;
	volatile ScheduledFuture<?> ticker;
	private volatile long startNanos;

	// 실행 스레드가 채우고 finishing으로 알린다. Done은 ticker가 마지막 지표까지 모은 뒤 만든다
	private RunState finalState;
	private Verification verification;
	private String error;
	private long endNanos;
	private volatile boolean finishing;
	private volatile Messages.Done done;
	/** ticker가 마지막 tick을 보내고 완료한다. 실행 스레드는 이걸 기다렸다가 DB에 저장한다 */
	final CompletableFuture<Messages.Done> doneFuture = new CompletableFuture<>();

	SimulationRun(long id, long eventId, SimulationRequest config, int stock) {
		this.id = id;
		this.eventId = eventId;
		this.config = config;
		this.strategy = config.strategy();
		this.stock = stock;
		this.userCount = config.userCount();
		this.status = new byte[userCount];
		this.requestedAtNs = new long[userCount];
		this.completedAtNs = new long[userCount];
		this.reason = new String[userCount];
	}

	// ---- 실행 스레드, 가상 사용자 스레드 ----

	void markStarted() {
		startNanos = System.nanoTime();
	}

	void requesting(int i) {
		requestedAtNs[i] = System.nanoTime();
		set(i, UserStatus.REQUESTING);
	}

	/** latencyNs가 음수면 지연시간 통계에 넣지 않는다 (중단으로 끊긴 요청). */
	void complete(int i, UserStatus result, String reason, long latencyNs) {
		completedAtNs[i] = System.nanoTime();
		this.reason[i] = reason;
		if (latencyNs >= 0) {
			recorder.recordValue(Math.max(1, latencyNs / 1_000));
			completed.incrementAndGet();
		}
		set(i, result);
	}

	UserStatus statusOf(int i) {
		return UserStatus.VALUES[status[i]];
	}

	private void set(int i, UserStatus s) {
		status[i] = (byte) s.ordinal();
		changed.offer(i);
	}

	void finish(RunState state, Verification verification, String error) {
		this.finalState = state;
		this.verification = verification;
		this.error = error;
		this.endNanos = System.nanoTime();
		this.finishing = true;
	}

	// ---- ticker 스레드 ----

	synchronized void tick() {
		boolean last = finishing; // 먼저 읽어야 마지막 사용자 변경까지 이번 tick에 담긴다
		List<Object[]> changes = drainChanges();
		broadcast("tick", buildTick(last));
		if (!changes.isEmpty()) {
			broadcast("users", new Messages.Users(changes));
		}
		if (last) {
			done = buildDone();
			state = finalState;
			broadcast("done", done);
			emitters.forEach(SseEmitter::complete);
			emitters.clear();
			ticker.cancel(false);
			doneFuture.complete(done);
		}
	}

	private List<Object[]> drainChanges() {
		BitSet seen = new BitSet(userCount);
		List<Object[]> changes = new ArrayList<>();
		for (Integer i; (i = changed.poll()) != null;) {
			if (!seen.get(i)) {
				seen.set(i);
				changes.add(new Object[] {i + 1, null});
			}
		}
		// 큐에 같은 사용자가 여러 번 있어도 마지막 상태만 보낸다
		for (Object[] c : changes) {
			c[1] = statusOf((Integer) c[0] - 1);
		}
		return changes;
	}

	private Messages.Tick buildTick(boolean last) {
		Histogram interval = recorder.getIntervalHistogram();
		total.add(interval);
		currentSecond.add(interval);

		int completedNow = completed.get();
		tpsWindow[tickCount++ % TPS_WINDOW] = completedNow - lastCompleted;
		lastCompleted = completedNow;
		int filled = Math.min(tickCount, TPS_WINDOW);
		int sum = 0;
		for (int k = 0; k < filled; k++) {
			sum += tpsWindow[k];
		}
		int tps = (int) (sum * 1000L / (filled * TICK_MS)); // 최근 1초 완료 건수

		Map<UserStatus, Integer> counts = counts();
		long t = elapsedMs();
		// 실행 기록용 시계열은 1초에 한 점
		if (startNanos != 0 && (tickCount % TPS_WINDOW == 0 || last)) {
			series.add(new Messages.SeriesPoint(t, tps, latency(currentSecond), 0));
			currentSecond.reset();
		}
		return new Messages.Tick(t, stockLeft(counts), counts, tps, latency(interval), 0, 0);
	}

	private Messages.Done buildDone() {
		long durationNs = startNanos == 0 ? 0 : endNanos - startNanos;
		int avgTps = durationNs == 0 ? 0 : (int) (completed.get() * 1_000_000_000L / durationNs);
		return new Messages.Done(finalState, durationNs / 1_000_000, counts(), verification, latency(total), avgTps, error);
	}

	// ---- 구독 ----

	/** 늦게 구독해도 화면을 복원할 수 있게 현재 상태 전체를 먼저 보낸다. */
	synchronized void subscribe(SseEmitter emitter) {
		try {
			emitter.send(SseEmitter.event().name("init")
					.data(new Messages.Init(id, eventId, strategy, stock, userCount, state, elapsedMs())));
			List<Object[]> all = new ArrayList<>();
			for (int i = 0; i < userCount; i++) {
				if (status[i] != 0) {
					all.add(new Object[] {i + 1, statusOf(i)});
				}
			}
			emitter.send(SseEmitter.event().name("users").data(new Messages.Users(all)));
			if (done != null) {
				emitter.send(SseEmitter.event().name("done").data(done));
				emitter.complete();
				return;
			}
			emitters.add(emitter);
		} catch (IOException e) {
			emitter.completeWithError(e);
		}
	}

	void unsubscribe(SseEmitter emitter) {
		emitters.remove(emitter);
	}

	private void broadcast(String name, Object data) {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name(name).data(data));
			} catch (IOException | IllegalStateException e) {
				emitters.remove(emitter); // 브라우저 탭이 닫힘
			}
		}
	}

	// ---- 조회, 저장 ----

	Messages.UserDetail user(long userId) {
		int i = (int) userId - 1;
		if (i < 0 || i >= userCount) {
			throw new RunNotFoundException("user " + userId + " (1~" + userCount + ")");
		}
		return new Messages.UserDetail(userId, statusOf(i), timeline(i));
	}

	private List<Messages.TimelineEntry> timeline(int i) {
		UserStatus s = statusOf(i);
		List<Messages.TimelineEntry> timeline = new ArrayList<>();
		timeline.add(new Messages.TimelineEntry(0, UserStatus.IDLE, null, null));
		if (s != UserStatus.IDLE) {
			timeline.add(new Messages.TimelineEntry(sinceStart(requestedAtNs[i]), UserStatus.REQUESTING, null, null));
		}
		if (s.isFinal()) {
			String note = "응답 " + latencyMs(i) + "ms" + (reason[i] != null ? " · " + reason[i] : "");
			timeline.add(new Messages.TimelineEntry(sinceStart(completedAtNs[i]), s, null, note));
		}
		return timeline;
	}

	/** 실행이 끝난 뒤(doneFuture 완료 후)에만 부른다. 출발하지 않은 사용자(중단)도 IDLE로 남긴다. */
	List<UserResult> userResults() {
		List<UserResult> results = new ArrayList<>(userCount);
		for (int i = 0; i < userCount; i++) {
			UserStatus s = statusOf(i);
			boolean requested = s != UserStatus.IDLE;
			boolean completed = s.isFinal();
			results.add(new UserResult(i + 1, s, requested ? 1 : 0, null,
					requested ? (int) sinceStart(requestedAtNs[i]) : null,
					completed ? (int) sinceStart(completedAtNs[i]) : null,
					completed ? (int) latencyMs(i) : null,
					reason[i], timeline(i)));
		}
		return results;
	}

	/** doneFuture 완료 후에만 안전하다 (ticker 스레드가 채운 값). */
	List<Messages.SeriesPoint> series() {
		return series;
	}

	private long latencyMs(int i) {
		return TimeUnit.NANOSECONDS.toMillis(completedAtNs[i] - requestedAtNs[i]);
	}

	private Map<UserStatus, Integer> counts() {
		int[] c = new int[UserStatus.VALUES.length];
		for (byte b : status) {
			c[b]++;
		}
		Map<UserStatus, Integer> counts = new EnumMap<>(UserStatus.class);
		for (UserStatus s : UserStatus.VALUES) {
			counts.put(s, c[s.ordinal()]);
		}
		return counts;
	}

	/** 화면에 보여줄 남은 재고. 클라이언트가 받은 성공 응답 기준이고, 실제 판정은 DB 검증으로 한다. */
	private int stockLeft(Map<UserStatus, Integer> counts) {
		return Math.max(0, stock - counts.get(UserStatus.ISSUED) - counts.get(UserStatus.ACCEPTED));
	}

	private long elapsedMs() {
		long start = startNanos;
		if (start == 0) {
			return 0;
		}
		long end = finishing ? endNanos : System.nanoTime();
		return (end - start) / 1_000_000;
	}

	private long sinceStart(long nanos) {
		return Math.max(0, (nanos - startNanos) / 1_000_000);
	}

	private static Messages.Latency latency(Histogram h) {
		if (h.getTotalCount() == 0) {
			return new Messages.Latency(null, null, null, null);
		}
		return new Messages.Latency(ms(h.getValueAtPercentile(50)), ms(h.getValueAtPercentile(95)),
				ms(h.getValueAtPercentile(99)), ms(h.getMaxValue()));
	}

	private static double ms(long micros) {
		return Math.round(micros / 100.0) / 10.0;
	}
}
