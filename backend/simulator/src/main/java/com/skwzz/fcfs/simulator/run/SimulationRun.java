package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.simulator.client.IssueOutcome;
import com.skwzz.fcfs.simulator.client.Verification;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실행 하나의 상태. 쓰는 쪽과 읽는 쪽이 나뉜다.
 * <ul>
 * <li>가상 사용자 스레드: 자기 칸의 상태를 바꾸고 changed 큐에 번호를 넣는다. 연타하면 한 사용자에 스레드가 여러 개다.</li>
 * <li>ticker 스레드: 200ms마다 큐를 비워서 바뀐 사용자와 집계 지표를 SSE로 보낸다.</li>
 * </ul>
 * 사용자 배열은 큐를 거쳐 읽기 때문에, 큐에서 꺼낸 번호의 상태는 최신 값이 보인다.
 * 지표는 요청 단위(TPS, 지연시간, 응답 종류)와 사용자 단위(최종 상태)를 나눠서 센다.
 */
final class SimulationRun {

	static final long TICK_MS = 200;
	private static final int TPS_WINDOW = (int) (1000 / TICK_MS);

	final long id;
	final long eventId;
	final SimulationRequest config;
	final BehaviorModel behavior;
	final StrategyType strategy;
	final int stock;
	final int userCount;
	final Instant startedAt = Instant.now();

	// 가상 사용자별 상태. 인덱스 i = userId - 1
	private final byte[] status;
	private final AtomicIntegerArray attempts;
	private final long[] firstRequestNs;
	private final long[] completedNs;
	private final String[] lastReason;
	private final List<List<Messages.TimelineEntry>> timelines;
	private final ConcurrentLinkedQueue<Integer> changed = new ConcurrentLinkedQueue<>();

	// 요청 단위 지표. 지연시간(µs)은 Recorder로 여러 스레드가 동시에 기록하고 구간별로 잘라서 꺼낸다
	private final Recorder recorder = new Recorder(3);
	private final Histogram total = new Histogram(3);
	private final Histogram currentSecond = new Histogram(3);
	private final List<Messages.SeriesPoint> series = new ArrayList<>();
	private final AtomicInteger completed = new AtomicInteger();
	private final AtomicInteger requests = new AtomicInteger();
	private final AtomicInteger retries = new AtomicInteger();
	private final Map<String, LongAdder> responses = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> errors = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> servedByCounts = new ConcurrentHashMap<>();

	// ticker 스레드만 쓰는 값
	private final int[] tpsWindow = new int[TPS_WINDOW];
	private int tickCount;
	private int lastCompleted;
	private Map<String, Messages.ServerSample> server = Map.of();
	private final Map<String, Messages.ServerSample> serverPeak = new TreeMap<>();

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
		this.behavior = config.behaviorModel();
		this.strategy = config.strategy();
		this.stock = stock;
		this.userCount = config.userCount();
		this.status = new byte[userCount];
		this.attempts = new AtomicIntegerArray(userCount);
		this.firstRequestNs = new long[userCount];
		this.completedNs = new long[userCount];
		this.lastReason = new String[userCount];
		this.timelines = new ArrayList<>(userCount);
		for (int i = 0; i < userCount; i++) {
			timelines.add(null);
		}
	}

	// ---- 실행 스레드, 가상 사용자 스레드 ----

	void markStarted() {
		startNanos = System.nanoTime();
	}

	/**
	 * 요청 하나를 보내기 직전.
	 *
	 * @param click 연타 중 몇 번째 클릭인지 (1부터)
	 * @param attempt 그 클릭의 몇 번째 시도인지 (1부터, 2 이상이면 재시도)
	 */
	void requestSent(int i, int click, int attempt) {
		long now = System.nanoTime();
		if (attempts.getAndIncrement(i) == 0) {
			firstRequestNs[i] = now;
		}
		requests.incrementAndGet();
		if (attempt > 1) {
			retries.incrementAndGet();
		}
		String note = (click > 1 ? "클릭 " + click : "") + (attempt > 1 ? (click > 1 ? " · " : "") + "재시도 " + (attempt - 1) : "");
		addTimeline(i, now, UserStatus.REQUESTING, attempts.get(i), note.isEmpty() ? null : note);
		set(i, UserStatus.REQUESTING);
	}

	/**
	 * 요청 하나의 응답 (또는 클라이언트 타임아웃·연결 실패).
	 *
	 * @param latencyNs 음수면 지연시간 통계에 넣지 않는다 (중단으로 끊긴 요청)
	 * @param retryInMs 0 이상이면 이 시간 뒤에 재시도한다. 그동안 사용자는 ERROR로 보인다
	 */
	void responseReceived(int i, IssueOutcome outcome, long latencyNs, long retryInMs) {
		long now = System.nanoTime();
		String result = outcome.result().name();
		responses.computeIfAbsent(result, k -> new LongAdder()).increment();
		if (outcome.reason() != null && UserStatus.of(outcome.result()) == UserStatus.ERROR) {
			errors.computeIfAbsent(outcome.reason(), k -> new LongAdder()).increment();
		}
		if (outcome.servedBy() != null) {
			servedByCounts.computeIfAbsent(outcome.servedBy(), k -> new LongAdder()).increment();
		}
		if (latencyNs >= 0) {
			recorder.recordValue(Math.max(1, latencyNs / 1_000));
			completed.incrementAndGet();
		}
		lastReason[i] = outcome.reason();

		StringBuilder note = new StringBuilder();
		if (latencyNs >= 0) {
			note.append(latencyNs / 1_000_000).append("ms");
		}
		if (outcome.servedBy() != null) {
			note.append(" · ").append(outcome.servedBy());
		}
		if (outcome.reason() != null) {
			note.append(" · ").append(outcome.reason());
		}
		if (retryInMs >= 0) {
			note.append(" → ").append(retryInMs).append("ms 뒤 재시도");
			set(i, UserStatus.ERROR);
		}
		addTimeline(i, now, UserStatus.of(outcome.result()), attempts.get(i), note.toString());
	}

	/** 사용자의 모든 클릭(과 재시도)이 끝났을 때 최종 상태를 정한다. */
	void userFinished(int i, UserStatus result) {
		completedNs[i] = System.nanoTime();
		set(i, result);
	}

	UserStatus statusOf(int i) {
		return UserStatus.VALUES[status[i]];
	}

	private void set(int i, UserStatus s) {
		status[i] = (byte) s.ordinal();
		changed.offer(i);
	}

	private void addTimeline(int i, long nanos, UserStatus s, int attempt, String note) {
		List<Messages.TimelineEntry> timeline;
		synchronized (timelines) {
			timeline = timelines.get(i);
			if (timeline == null) {
				timeline = new ArrayList<>(4);
				timeline.add(new Messages.TimelineEntry(0, UserStatus.IDLE, null, null, null));
				timelines.set(i, timeline);
			}
		}
		synchronized (timeline) {
			timeline.add(new Messages.TimelineEntry(sinceStart(nanos), s, null, attempt, note));
		}
	}

	void finish(RunState state, Verification verification, String error) {
		this.finalState = state;
		this.verification = verification;
		this.error = error;
		this.endNanos = System.nanoTime();
		this.finishing = true;
	}

	// ---- ticker 스레드 ----

	/** @param server 이번 tick 시점의 인스턴스별 서버 지표 */
	synchronized void tick(Map<String, Messages.ServerSample> server) {
		boolean last = finishing; // 먼저 읽어야 마지막 사용자 변경까지 이번 tick에 담긴다
		this.server = server;
		server.forEach((name, sample) -> serverPeak.merge(name, sample, Messages.ServerSample::max));
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
				changes.add(userRow(i));
			}
		}
		return changes;
	}

	/** [userId, status, rank, attempts]. rank는 대기열(Phase 5)부터 */
	private Object[] userRow(int i) {
		return new Object[] {i + 1, statusOf(i), null, attempts.get(i)};
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
		int tps = (int) (sum * 1000L / (filled * TICK_MS)); // 최근 1초 완료 요청 수

		Map<UserStatus, Integer> counts = counts();
		long t = elapsedMs();
		// 실행 기록용 시계열은 1초에 한 점
		if (startNanos != 0 && (tickCount % TPS_WINDOW == 0 || last)) {
			Integer pending = server.isEmpty() ? null
					: server.values().stream().mapToInt(Messages.ServerSample::hikariPending).sum();
			series.add(new Messages.SeriesPoint(t, tps, latency(currentSecond), 0, pending, retries.get()));
			currentSecond.reset();
		}
		return new Messages.Tick(t, stockLeft(counts), counts, tps, latency(interval), 0, retries.get(), requests.get(),
				sums(responses), servedBy(), server);
	}

	private Messages.Done buildDone() {
		long durationNs = startNanos == 0 ? 0 : endNanos - startNanos;
		int avgTps = durationNs == 0 ? 0 : (int) (completed.get() * 1_000_000_000L / durationNs);
		return new Messages.Done(finalState, durationNs / 1_000_000, counts(), verification, latency(total), avgTps, error,
				servedBy(), new TreeMap<>(serverPeak), requests.get(), retries.get(), sums(responses), sums(errors));
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
					all.add(userRow(i));
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
		List<Messages.TimelineEntry> timeline;
		synchronized (timelines) {
			timeline = timelines.get(i);
		}
		if (timeline == null) {
			return List.of(new Messages.TimelineEntry(0, UserStatus.IDLE, null, null, null));
		}
		List<Messages.TimelineEntry> copy;
		synchronized (timeline) {
			copy = new ArrayList<>(timeline);
		}
		UserStatus s = statusOf(i);
		if (s.isFinal() && completedNs[i] != 0 && copy.getLast().status() != s) {
			// 연타 중 마지막 응답이 최종 상태와 다르면 (예: 발급 성공 후 중복 응답) 최종 판정을 덧붙인다
			copy.add(new Messages.TimelineEntry(sinceStart(completedNs[i]), s, null, attempts.get(i), "최종 결과"));
		}
		return copy;
	}

	/** 실행이 끝난 뒤(doneFuture 완료 후)에만 부른다. 출발하지 않은 사용자(중단)는 IDLE로 남긴다. */
	List<UserResult> userResults() {
		List<UserResult> results = new ArrayList<>(userCount);
		for (int i = 0; i < userCount; i++) {
			UserStatus s = statusOf(i);
			boolean requested = attempts.get(i) > 0;
			boolean completed = requested && completedNs[i] != 0;
			results.add(new UserResult(i + 1, s, attempts.get(i), null,
					requested ? (int) sinceStart(firstRequestNs[i]) : null,
					completed ? (int) sinceStart(completedNs[i]) : null,
					completed ? (int) ((completedNs[i] - firstRequestNs[i]) / 1_000_000) : null,
					lastReason[i], timeline(i)));
		}
		return results;
	}

	/** doneFuture 완료 후에만 안전하다 (ticker 스레드가 채운 값). */
	List<Messages.SeriesPoint> series() {
		return series;
	}

	private Map<String, Long> servedBy() {
		return sums(servedByCounts);
	}

	private static Map<String, Long> sums(Map<String, LongAdder> counters) {
		Map<String, Long> result = new TreeMap<>();
		counters.forEach((name, count) -> result.put(name, count.sum()));
		return result;
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
