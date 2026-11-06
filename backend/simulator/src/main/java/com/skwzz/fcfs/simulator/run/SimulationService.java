package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.IssueStatus;
import com.skwzz.fcfs.simulator.SimulatorProperties;
import com.skwzz.fcfs.simulator.client.CouponApiClient;
import com.skwzz.fcfs.simulator.client.CouponApiException;
import com.skwzz.fcfs.simulator.client.EventInfo;
import com.skwzz.fcfs.simulator.client.IssueOutcome;
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
				? api.createEvent("sim " + request.strategy() + " " + Instant.now(), request.stock(), request.strategy())
				: api.resetEvent(request.eventId());
		long runId = repository.insertRunning(event.id(), request, event.totalQuantity(), props.targetBaseUrl(),
				Instant.now());

		SimulationRun run = new SimulationRun(runId, event.id(), request, event.totalQuantity());
		runs.put(runId, run);
		run.ticker = ticker.scheduleAtFixedRate(() -> safeTick(run), SimulationRun.TICK_MS, SimulationRun.TICK_MS,
				TimeUnit.MILLISECONDS);
		Thread.ofVirtual().name("sim-run-" + runId).start(() -> execute(run));
		log.info("simulation {} started: event={}, strategy={}, users={}, stock={}", runId, event.id(),
				request.strategy(), request.userCount(), event.totalQuantity());
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
			int clicks = run.behavior.clicksForUser();
			UserStatus result = clicks == 1 ? runClick(run, i, 1) : runClicks(run, i, clicks);
			run.userFinished(i, result);
		} catch (InterruptedException e) {
			// 출발 전에 중단됨. 요청을 보낸 적이 없으므로 IDLE로 남긴다
		}
	}

	/** 응답이 오기 전에 버튼을 여러 번 누른다. 클릭마다 요청 id가 다르다 (멱등 키로 막히지 않는 진짜 중복 요청). */
	private UserStatus runClicks(SimulationRun run, int i, int clicks) throws InterruptedException {
		List<Future<UserStatus>> results = new ArrayList<>(clicks);
		try (ExecutorService clickThreads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int k = 1; k <= clicks; k++) {
				int click = k;
				results.add(clickThreads.submit(() -> {
					Thread.sleep((long) (click - 1) * BehaviorModel.CLICK_INTERVAL_MS);
					return runClick(run, i, click);
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
	private UserStatus runClick(SimulationRun run, int i, int click) {
		String requestId = UUID.randomUUID().toString();
		Duration timeout = Duration.ofMillis(run.behavior.timeoutMs());
		try {
			for (int attempt = 1;; attempt++) {
				run.requestSent(i, click, attempt);
				long t0 = System.nanoTime();
				IssueOutcome outcome = api.issue(run.eventId, i + 1L, run.strategy, requestId, timeout);
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
					null, s.latencyMs(), 0, s.error(), Map.of(), Map.of(), null, null, null, null));
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
