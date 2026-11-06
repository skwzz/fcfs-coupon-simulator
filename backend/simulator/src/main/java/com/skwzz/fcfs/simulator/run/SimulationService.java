package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.simulator.SimulatorProperties;
import com.skwzz.fcfs.simulator.client.CouponApiClient;
import com.skwzz.fcfs.simulator.client.CouponApiException;
import com.skwzz.fcfs.simulator.client.EventInfo;
import com.skwzz.fcfs.simulator.client.IssueOutcome;
import com.skwzz.fcfs.simulator.client.Verification;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("sim-ticker").daemon().factory());
	private final Map<Long, SimulationRun> runs;

	public SimulationService(CouponApiClient api, RunRepository repository, SimulatorProperties props) {
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

	public Messages.Started start(SimulationRequest request) {
		request.validateSupported();
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

		Verification verification = null;
		String error = null;
		try {
			verification = api.verify(run.eventId);
		} catch (CouponApiException e) {
			error = e.getMessage();
		}
		run.finish(run.stopRequested ? RunState.STOPPED : RunState.DONE, verification, error);
		save(run);
		log.info("simulation {} finished: {}", run.id, verification);
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

	private void runUser(SimulationRun run, int i, CountDownLatch startSignal) {
		try {
			startSignal.await();
			long delayMs = (long) run.config.rampUp() * i / run.userCount;
			if (delayMs > 0) {
				Thread.sleep(delayMs);
			}
			if (run.stopRequested) {
				return;
			}
			run.requesting(i);
			long t0 = System.nanoTime();
			IssueOutcome outcome = api.issue(run.eventId, i + 1L, run.strategy);
			run.complete(i, UserStatus.of(outcome.result()), outcome.reason(), System.nanoTime() - t0);
		} catch (InterruptedException e) {
			if (run.statusOf(i) == UserStatus.REQUESTING) {
				run.complete(i, UserStatus.ERROR, "STOPPED", -1);
			}
		}
	}

	private void safeTick(SimulationRun run) {
		try {
			run.tick();
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
		SimulationRun run = get(runId);
		SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
		emitter.onCompletion(() -> run.unsubscribe(emitter));
		emitter.onTimeout(() -> run.unsubscribe(emitter));
		emitter.onError(e -> run.unsubscribe(emitter));
		run.subscribe(emitter);
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
