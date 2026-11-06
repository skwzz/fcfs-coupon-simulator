package com.skwzz.fcfs.api.issue;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 발급 요청을 동시에 몇 개까지 처리하고, 몇 개까지 기다리게 할지 (벌크헤드 + 길이 제한이 있는 대기 줄).
 * <p>
 * Phase 4까지는 Tomcat이 이 역할을 했다. 처리 중 1000개(max-connections), 넘치면 OS 대기 큐 2000개(accept-count),
 * 그것도 넘치면 연결 거절. 그런데 대기열 SSE는 연결을 오래 잡고 있어서, 1만 명이 순번을 기다리면 연결 한도가 SSE로 다 차고
 * 발급 요청은 들어오지도 못한다. 그래서 연결 한도는 크게 열고, 무거운 발급 처리만 여기서 같은 크기로 제한한다.
 * <p>
 * 대기 줄 길이를 제한하지 않으면 재시도 폭주 때 요청이 전부 서버 안에서 기다리면서 nginx 연결까지 다 잡아,
 * 새 연결을 아예 못 받게 된다 (docs/06 Phase 5 결과). 그래서 줄이 차면 기다리게 하지 않고 바로 503 OVERLOADED로 돌려보낸다
 * (부하 차단, load shedding). 빨리 거절해야 클라이언트도 빨리 백오프하고, 연결도 빨리 풀린다.
 */
@Component
public class IssueBulkhead {

	private final Semaphore permits;
	private final int maxWaiting;
	private final long waitMs;
	private final AtomicInteger waiting = new AtomicInteger();

	public IssueBulkhead(@Value("${coupon.issue.max-concurrent:1000}") int maxConcurrent,
			@Value("${coupon.issue.max-waiting:2000}") int maxWaiting,
			@Value("${coupon.issue.bulkhead-wait-ms:60000}") long waitMs) {
		this.permits = new Semaphore(maxConcurrent);
		this.maxWaiting = maxWaiting;
		this.waitMs = waitMs;
	}

	/** 자리를 얻으면 true. true일 때만 release를 불러야 한다. 대기 줄이 차 있으면 기다리지 않고 false */
	public boolean acquire() {
		if (permits.tryAcquire()) {
			return true;
		}
		if (waiting.incrementAndGet() > maxWaiting) {
			waiting.decrementAndGet();
			return false;
		}
		try {
			return permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			waiting.decrementAndGet();
		}
	}

	public void release() {
		permits.release();
	}
}
