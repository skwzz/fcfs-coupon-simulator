package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.IssueStatus;
import com.skwzz.fcfs.simulator.SimulatorProperties;
import com.skwzz.fcfs.simulator.client.CouponApiClient;
import com.skwzz.fcfs.simulator.client.CouponApiException;
import com.skwzz.fcfs.simulator.client.EventInfo;
import com.skwzz.fcfs.simulator.client.IssueOutcome;
import com.skwzz.fcfs.simulator.client.QueueResponse;
import com.skwzz.fcfs.simulator.client.Verification;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 시뮬레이션 실행. 가상 사용자 한 명 = Virtual Thread 하나 = 실제 HTTP 요청.
 * 실행 기록과 결과는 simulation DB에 남기고, 실행 중 상태(사용자 배열, SSE 구독자)는 최근 실행만 메모리에 둔다.
 */
@Slf4j
@Service
public class SimulationService {

	private final CouponApiClient api;
	private final RunRepository repository;
	private final SimulatorProperties props;
	private final ServerMetricsCollector serverMetrics;
	private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("sim-ticker").daemon().factory());
	private final Map<Long, SimulationRun> runs;

	public SimulationService(CouponApiClient api, RunRepository repository, SimulatorProperties props,
			ServerMetricsCollector serverMetrics) {
		this.serverMetrics = serverMetrics;
		this.api = api;
		this.repository = repository;
		this.props = props;
		this.runs = Collections.synchronizedMap(new LinkedHashMap<>() {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, SimulationRun> eldest) {
				return size() > props.maxKeptRuns();
			}
		});
	}

	@EventListener(ApplicationReadyEvent.class)
	void cleanUpOrphans() {
		int count = repository.failOrphans();
		if (count > 0) {
			log.warn("이전 simulator 프로세스가 끝내지 못한 실행 {}건을 FAILED로 표시했습니다", count);
		}
	}

	public Messages.Started start(SimulationRequest original) {
		original.validateSupported();
		SimulationRequest request = original.withResolvedBehavior();
		EventInfo event = request.eventId() == null
				? api.createEvent("sim " + request.strategy() + " " + Instant.now(), request.stock(), request.strategy(),
						request.queued(), request.queued() ? request.admitRate() : null)
				: api.resetEvent(request.eventId());
		long runId = repository.insertRunning(event.id(), request, event.totalQuantity(), props.targetBaseUrl(),
				Instant.now());

		// 기존 이벤트를 다시 쓰면 대기열 여부는 요청이 아니라 이벤트 설정을 따른다
		SimulationRun run = new SimulationRun(runId, event.id(), request, event.totalQuantity(), event.queueEnabled());
		runs.put(runId, run);
		run.ticker = ticker.scheduleAtFixedRate(() -> safeTick(run), SimulationRun.TICK_MS, SimulationRun.TICK_MS,
				TimeUnit.MILLISECONDS);
		Thread.ofVirtual().name("sim-run-" + runId).start(() -> execute(run));
		log.info("simulation {} started: event={}, strategy={}, users={}, stock={}, queue={}", runId, event.id(),
				request.strategy(), request.userCount(), event.totalQuantity(),
				event.queueEnabled() ? event.admitPerSecond() + "/s " + request.notifyMode() : "off");
		return new Messages.Started(runId, event.id());
	}

	private void execute(SimulationRun run) {
		serverMetrics.acquire();
		try {
			runUsers(run);
		} finally {
			serverMetrics.release();
		}

		Verification verification = null;
		String error = null;
		// 부하 직후에는 API가 클라이언트가 이미 포기한 요청까지 처리하느라 응답하지 못할 수 있다.
		// 재시도 폭주 뒤에는 수십 초 걸리기도 해서, 최대 1분 정도 다시 묻는다
		for (int attempt = 1; attempt <= 20 && verification == null; attempt++) {
			try {
				verification = api.verify(run.eventId);
				error = null;
			} catch (CouponApiException e) {
				error = e.getMessage();
				sleepQuietly(3_000);
			}
		}
		run.finish(run.stopRequested ? RunState.STOPPED : RunState.DONE, verification, error);
		save(run);
		log.info("simulation {} finished: {}", run.id, verification);
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void runUsers(SimulationRun run) {
		CountDownLatch startSignal = new CountDownLatch(1);
		try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
			run.users = users;
			for (int i = 0; i < run.userCount; i++) {
				int index = i;
				users.submit(() -> runUser(run, index, startSignal));
			}
			// 모든 스레드가 준비된 뒤 한 번에 출발 (rampUpMs가 0이면 사실상 동시 요청)
			run.markStarted();
			startSignal.countDown();
		} // close()가 모든 사용자가 끝날 때까지 기다린다
	}

	/** ticker가 마지막 지표까지 보낸 뒤 결과를 DB에 남긴다. */
	private void save(SimulationRun run) {
		try {
			Messages.Done done = run.doneFuture.get(30, TimeUnit.SECONDS);
			long t0 = System.nanoTime();
			repository.complete(run.id, done, run.series(), run.userResults());
			log.info("simulation {} saved ({} users, {}ms)", run.id, run.userCount,
					TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
		} catch (Exception e) {
			log.error("simulation {} result not saved", run.id, e);
			repository.fail(run.id, "결과 저장 실패: " + e.getMessage());
		}
	}

	/** 가상 사용자 한 명: 생각 시간 → 클릭(연타면 여러 번, 클릭마다 재시도) → 최종 상태 */
	private void runUser(SimulationRun run, int i, CountDownLatch startSignal) {
		try {
			startSignal.await();
			long delayMs = (long) run.config.rampUp() * i / run.userCount + run.behavior.thinkTime();
			if (delayMs > 0) {
				Thread.sleep(delayMs);
			}
			if (run.stopRequested) {
				return;
			}
			if (run.queued) {
				runQueued(run, i);
				return;
			}
			run.userFinished(i, issue(run, i, null));
		} catch (InterruptedException e) {
			// 출발 전에 중단됨. 요청을 보낸 적이 없으므로 IDLE로 남긴다
		}
	}

	/** 발급 버튼을 누른다 (연타면 여러 번). 대기열을 거쳤으면 입장 토큰을 같이 보낸다 */
	private UserStatus issue(SimulationRun run, int i, String token) throws InterruptedException {
		int clicks = run.behavior.clicksForUser();
		return clicks == 1 ? runClick(run, i, 1, token) : runClicks(run, i, clicks, token);
	}

	// ---- 대기열 ----

	private enum WaitResult {
		ADMITTED, SOLD_OUT, ABANDON, REFRESH, REENTER, FAILED
	}

	/** 기다리는 동안 바뀌는 값 */
	private static final class Waiting {
		final long startNs = System.nanoTime();
		final long patienceMs;
		long nextRefreshNs;
		String token;
		int errors;

		Waiting(BehaviorModel behavior) {
			this.patienceMs = behavior.patienceMs();
			scheduleRefresh(behavior);
		}

		void scheduleRefresh(BehaviorModel behavior) {
			long ms = behavior.nextRefreshMs();
			nextRefreshNs = ms == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + ms * 1_000_000;
		}

		boolean outOfPatience() {
			return patienceMs != Long.MAX_VALUE && (System.nanoTime() - startNs) / 1_000_000 >= patienceMs;
		}

		boolean refreshDue() {
			return System.nanoTime() >= nextRefreshNs;
		}
	}

	/**
	 * 대기열을 쓰는 이벤트의 사용자: 줄 서기 → 순번을 받으며 기다림(SSE 또는 폴링) → 입장하면 발급.
	 * 기다리는 동안 새로고침하면 줄에서 나갔다가 맨 뒤로 다시 서고, 참을성이 다하면 떠난다(ABANDONED).
	 * 줄 서기·폴링이 실패하면 발급과 같은 재시도 규칙(횟수, 백오프)을 따른다.
	 */
	private void runQueued(SimulationRun run, int i) {
		long userId = i + 1L;
		Duration timeout = Duration.ofMillis(run.behavior.timeoutMs());
		Waiting w = new Waiting(run.behavior);
		boolean refresh = false;
		try {
			while (true) {
				QueueResponse q = api.enterQueue(run.eventId, userId, timeout);
				if (q.is(QueueResponse.ERROR)) {
					run.queueError(i, q.reason());
					if (!backoffAfterError(run, w)) {
						run.queueFinished(i, UserStatus.ERROR, "줄 서기 실패 · " + q.reason());
						return;
					}
					continue;
				}
				run.queueEntered(i, q, refresh);
				refresh = false;
				WaitResult result = switch (q.status()) {
					case QueueResponse.WAITING -> run.config.notifyMode() == SimulationRequest.NotifyMode.SSE
							? waitBySse(run, i, w)
							: waitByPolling(run, i, w);
					case QueueResponse.ADMITTED -> admittedNow(w, q);
					case QueueResponse.SOLD_OUT -> WaitResult.SOLD_OUT;
					default -> WaitResult.REENTER; // 입장했지만 토큰 만료 등: 다시 줄을 선다
				};
				switch (result) {
					case ADMITTED -> {
						run.admitted(i, w.startNs);
						run.userFinished(i, issue(run, i, w.token));
						return;
					}
					case SOLD_OUT -> {
						run.queueFinished(i, UserStatus.SOLD_OUT, "대기 중 매진 통지");
						return;
					}
					case ABANDON -> {
						api.leaveQueue(run.eventId, userId, timeout);
						run.queueFinished(i, UserStatus.ABANDONED,
								"대기 " + (System.nanoTime() - w.startNs) / 1_000_000_000 + "초 만에 이탈");
						return;
					}
					case REFRESH -> {
						// 새로고침: 대기열 키(세션)를 잃은 것처럼 나갔다가 다시 선다
						api.leaveQueue(run.eventId, userId, timeout);
						w.scheduleRefresh(run.behavior);
						refresh = true;
					}
					case FAILED -> {
						run.queueFinished(i, UserStatus.ERROR, "순번 확인 실패");
						return;
					}
					case REENTER -> {
						// 연결이 끊기거나 토큰이 만료됨: 다시 줄 서기 (이미 서 있으면 자리는 그대로)
					}
				}
				if (run.stopRequested) {
					run.queueFinished(i, UserStatus.ERROR, "중단됨");
					return;
				}
			}
		} catch (InterruptedException e) {
			run.queueFinished(i, UserStatus.ERROR, "중단됨");
		}
	}

	private static WaitResult admittedNow(Waiting w, QueueResponse q) {
		w.token = q.token();
		return WaitResult.ADMITTED;
	}

	/** 순번 SSE를 구독해서 서버가 보내 주는 상태를 기다린다. 이벤트가 올 때마다 새로고침·이탈할지 본다 */
	private WaitResult waitBySse(SimulationRun run, int i, Waiting w) throws InterruptedException {
		Duration timeout = Duration.ofMillis(run.behavior.timeoutMs());
		try (CouponApiClient.QueueStream stream = api.queueStream(run.eventId, i + 1L, timeout)) {
			for (QueueResponse s; (s = stream.next()) != null;) {
				if (Thread.currentThread().isInterrupted()) {
					throw new InterruptedException();
				}
				if (s.is(QueueResponse.ERROR)) {
					run.queueError(i, s.reason());
					return backoffAfterError(run, w) ? WaitResult.REENTER : WaitResult.FAILED;
				}
				WaitResult result = onStatus(run, i, w, s);
				if (result != null) {
					return result;
				}
			}
		}
		return WaitResult.REENTER; // 서버가 연결을 닫음 (SSE 유효 시간 초과)
	}

	/** 주기적으로 내 순번을 묻는다 */
	private WaitResult waitByPolling(SimulationRun run, int i, Waiting w) throws InterruptedException {
		Duration timeout = Duration.ofMillis(run.behavior.timeoutMs());
		while (!run.stopRequested) {
			Thread.sleep(run.behavior.pollDelayMs());
			if (w.outOfPatience()) {
				return WaitResult.ABANDON;
			}
			if (w.refreshDue()) {
				return WaitResult.REFRESH;
			}
			QueueResponse s = api.queueStatus(run.eventId, i + 1L, timeout);
			run.queuePolled();
			if (s.is(QueueResponse.ERROR)) {
				run.queueError(i, s.reason());
				if (!backoffAfterError(run, w)) {
					return WaitResult.FAILED;
				}
				continue;
			}
			w.errors = 0;
			WaitResult result = onStatus(run, i, w, s);
			if (result != null) {
				return result;
			}
		}
		throw new InterruptedException();
	}

	/** 상태 하나를 받았을 때. 계속 기다리면 null */
	private static WaitResult onStatus(SimulationRun run, int i, Waiting w, QueueResponse s) {
		switch (s.status()) {
			case QueueResponse.WAITING -> run.rankUpdated(i, s.rank());
			case QueueResponse.ADMITTED -> {
				return admittedNow(w, s);
			}
			case QueueResponse.SOLD_OUT -> {
				return WaitResult.SOLD_OUT;
			}
			default -> {
				return WaitResult.REENTER;
			}
		}
		if (w.outOfPatience()) {
			return WaitResult.ABANDON;
		}
		if (w.refreshDue()) {
			return WaitResult.REFRESH;
		}
		return null;
	}

	/** 줄 서기·순번 확인이 실패했을 때 재시도 규칙대로 기다린다. 재시도 횟수를 넘기면 false */
	private static boolean backoffAfterError(SimulationRun run, Waiting w) throws InterruptedException {
		w.errors++;
		if (w.errors > run.behavior.maxRetries()) {
			return false;
		}
		long backoff = run.behavior.backoff(w.errors);
		// 백오프가 0인 행동(AGGRESSIVE)이라도 서버가 끊은 직후 바로 다시 붙으면 같은 실패를 반복하므로 최소 간격을 둔다
		Thread.sleep(Math.max(backoff, 100));
		return true;
	}

	/** 응답이 오기 전에 버튼을 여러 번 누른다. 클릭마다 요청 id가 다르다 (멱등 키로 막히지 않는 진짜 중복 요청). */
	private UserStatus runClicks(SimulationRun run, int i, int clicks, String token) throws InterruptedException {
		List<Future<UserStatus>> results = new ArrayList<>(clicks);
		try (ExecutorService clickThreads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int k = 1; k <= clicks; k++) {
				int click = k;
				results.add(clickThreads.submit(() -> {
					Thread.sleep((long) (click - 1) * BehaviorModel.CLICK_INTERVAL_MS);
					return runClick(run, i, click, token);
				}));
			}
		}
		UserStatus best = null;
		for (Future<UserStatus> f : results) {
			try {
				best = better(best, f.get());
			} catch (ExecutionException | CancellationException e) {
				best = better(best, UserStatus.ERROR);
			}
		}
		return best;
	}

	/** 클릭 한 번. 에러면 같은 요청 id로 재시도한다. 중단되면 ERROR(STOPPED) */
	private UserStatus runClick(SimulationRun run, int i, int click, String token) {
		String requestId = UUID.randomUUID().toString();
		Duration timeout = Duration.ofMillis(run.behavior.timeoutMs());
		try {
			for (int attempt = 1;; attempt++) {
				run.requestSent(i, click, attempt);
				long t0 = System.nanoTime();
				IssueOutcome outcome = api.issue(run.eventId, i + 1L, run.strategy, requestId, token, timeout);
				long latency = System.nanoTime() - t0;
				UserStatus status = UserStatus.of(outcome.result());
				boolean retry = status == UserStatus.ERROR && attempt <= run.behavior.maxRetries() && !run.stopRequested;
				long backoff = retry ? run.behavior.backoff(attempt) : -1;
				run.responseReceived(i, outcome, latency, backoff);
				if (!retry) {
					return status;
				}
				if (backoff > 0) {
					Thread.sleep(backoff);
				}
			}
		} catch (InterruptedException e) {
			run.responseReceived(i, new IssueOutcome(IssueStatus.ERROR, "STOPPED", null), -1, -1);
			return UserStatus.ERROR;
		}
	}

	/** 여러 클릭의 결과 중 사용자에게 보이는 최종 결과: 발급 > 매진 > 중복 > 에러 */
	private static UserStatus better(UserStatus a, UserStatus b) {
		if (a == null) {
			return b;
		}
		return rank(b) < rank(a) ? b : a;
	}

	private static int rank(UserStatus s) {
		return switch (s) {
			case ISSUED -> 0;
			case ACCEPTED -> 1;
			case SOLD_OUT -> 2;
			case DUPLICATE -> 3;
			default -> 4;
		};
	}

	private void safeTick(SimulationRun run) {
		try {
			run.tick(serverMetrics.latest());
		} catch (RuntimeException e) {
			// 예외가 나면 scheduleAtFixedRate가 조용히 멈추므로 여기서 삼키고 남긴다
			log.warn("tick failed for run {}", run.id, e);
		}
	}

	public void stop(long runId) {
		SimulationRun run = get(runId);
		run.stopRequested = true;
		ExecutorService users = run.users;
		if (users != null) {
			users.shutdownNow(); // 출발 전 사용자는 건너뛰고, 요청 중인 사용자는 인터럽트
		}
	}

	public SseEmitter subscribe(long runId) {
		SimulationRun run = runs.get(runId);
		if (run == null) {
			return replayFromDb(runId);
		}
		SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
		emitter.onCompletion(() -> run.unsubscribe(emitter));
		emitter.onTimeout(() -> run.unsubscribe(emitter));
		emitter.onError(e -> run.unsubscribe(emitter));
		run.subscribe(emitter);
		return emitter;
	}

	/**
	 * 메모리에 없는 실행(오래됐거나 simulator가 재시작됨)을 구독하면 저장된 결과를 같은 SSE 형식으로 한 번에 보내고 닫는다.
	 * 그래서 화면은 실행 중이던 것과 끝난 것을 구분하지 않고 같은 방식으로 복원할 수 있다.
	 */
	private SseEmitter replayFromDb(long runId) {
		Messages.RunSummary s = summary(runId);
		SseEmitter emitter = new SseEmitter(TimeUnit.SECONDS.toMillis(30));
		try {
			emitter.send(SseEmitter.event().name("init").data(new Messages.Init(runId, s.eventId(), s.strategy(), s.stock(),
					s.userCount(), s.state(), s.durationMs() == null ? 0 : s.durationMs())));
			emitter.send(SseEmitter.event().name("users").data(new Messages.Users(repository.findUserRows(runId))));
			Messages.Done done = repository.findDone(runId).orElseGet(() -> new Messages.Done(s.state(), 0, s.counts(),
					null, s.latencyMs(), 0, s.error(), Map.of(), Map.of(), null, null, null, null, null));
			emitter.send(SseEmitter.event().name("done").data(done));
			emitter.complete();
		} catch (IOException e) {
			emitter.completeWithError(e);
		}
		return emitter;
	}

	public Messages.RunSummary summary(long runId) {
		return repository.find(runId).orElseThrow(() -> new RunNotFoundException("simulation " + runId));
	}

	public List<Messages.RunSummary> list(int limit) {
		return repository.findRecent(Math.min(Math.max(limit, 1), 200));
	}

	public List<Messages.Comparison> compare(List<Long> runIds) {
		return repository.findComparisons(runIds);
	}

	/** 메모리에 있으면 실시간 상태, 없으면 DB에 저장된 최종 결과 */
	public Messages.UserDetail user(long runId, long userId) {
		SimulationRun run = runs.get(runId);
		if (run != null) {
			return run.user(userId);
		}
		return repository.findUser(runId, userId)
				.orElseThrow(() -> new RunNotFoundException("simulation " + runId + " user " + userId));
	}

	public void delete(long runId) {
		SimulationRun run = runs.get(runId);
		if (run != null && run.state == RunState.RUNNING) {
			throw new IllegalStateException("실행 중인 시뮬레이션은 삭제할 수 없습니다. 먼저 중단하세요");
		}
		runs.remove(runId);
		if (!repository.delete(runId)) {
			throw new RunNotFoundException("simulation " + runId);
		}
	}

	private SimulationRun get(long runId) {
		SimulationRun run = runs.get(runId);
		if (run == null) {
			throw new RunNotFoundException("simulation " + runId);
		}
		return run;
	}
}
