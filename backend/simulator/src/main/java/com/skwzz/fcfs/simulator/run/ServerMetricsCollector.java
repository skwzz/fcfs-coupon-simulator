package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.simulator.SimulatorProperties;
import com.skwzz.fcfs.simulator.client.CouponApiClient;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 실행 중에 coupon-api 인스턴스마다 서버 측 지표를 1초마다 읽어 둔다.
 * 실무라면 Prometheus가 긁어 가는 값인데, 여기서는 대시보드에 바로 보여주려고 simulator가 직접 읽는다 (Prometheus 연동은 백로그).
 */
@Slf4j
@Component
public class ServerMetricsCollector {

	private final CouponApiClient api;
	private final SimulatorProperties props;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
			Thread.ofVirtual().name("server-metrics").factory());
	private final AtomicInteger activeRuns = new AtomicInteger();
	/** key: 인스턴스 이름(X-Served-By) */
	private final Map<String, Messages.ServerSample> latest = new ConcurrentHashMap<>();

	public ServerMetricsCollector(CouponApiClient api, SimulatorProperties props) {
		this.api = api;
		this.props = props;
		scheduler.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
	}

	/** 실행이 있을 때만 읽는다. 시작할 때 acquire, 끝날 때 release */
	void acquire() {
		if (activeRuns.getAndIncrement() == 0) {
			latest.clear();
		}
	}

	void release() {
		activeRuns.decrementAndGet();
	}

	/** 인스턴스 이름순 */
	Map<String, Messages.ServerSample> latest() {
		return new TreeMap<>(latest);
	}

	private void poll() {
		if (activeRuns.get() == 0) {
			return;
		}
		// 인스턴스마다 따로 읽어서, 한 인스턴스가 느려도 다른 인스턴스 지표는 제때 들어오게 한다
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			props.serverMetricsUrls().forEach(url -> executor.submit(() -> pollOne(url)));
		}
	}

	private void pollOne(String url) {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var active = executor.submit(() -> api.metric(url, "hikaricp.connections.active"));
			var pending = executor.submit(() -> api.metric(url, "hikaricp.connections.pending"));
			var cpu = executor.submit(() -> api.metric(url, "process.cpu.usage"));
			var heap = executor.submit(() -> api.metric(url, "jvm.memory.used?tag=area:heap"));
			latest.put(active.get().servedBy(), new Messages.ServerSample((int) active.get().value(),
					(int) pending.get().value(), Math.round(cpu.get().value() * 1000) / 10.0,
					(int) (heap.get().value() / (1024 * 1024))));
		} catch (Exception e) {
			// 지표는 보조 정보라 실패해도 실행에는 영향을 주지 않는다
			log.info("server metrics from {} failed: {}", url, e.toString());
		}
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}
}
