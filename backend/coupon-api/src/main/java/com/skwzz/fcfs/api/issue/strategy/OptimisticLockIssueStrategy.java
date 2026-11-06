package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.event.CouponEvent;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @Version으로 충돌을 감지하고, 충돌하면 트랜잭션을 처음부터 다시 실행한다.
 * 경합이 심하면 재시도가 폭증해서 오히려 느려지는 것을 보여주려고 재시도 횟수를 따로 센다.
 */
@Component
class OptimisticLockIssueStrategy implements IssueStrategy {

	private final CouponIssuer issuer;
	private final TransactionTemplate transactionTemplate;
	private final Counter retryCounter;
	private final int maxRetries;
	private final long backoffMinMs;
	private final long backoffMaxMs;

	OptimisticLockIssueStrategy(CouponIssuer issuer, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry,
			@Value("${coupon.issue.optimistic.max-retries:100}") int maxRetries,
			@Value("${coupon.issue.optimistic.backoff-min-ms:5}") long backoffMinMs,
			@Value("${coupon.issue.optimistic.backoff-max-ms:30}") long backoffMaxMs) {
		this.issuer = issuer;
		this.transactionTemplate = transactionTemplate;
		this.retryCounter = meterRegistry.counter("coupon.issue.retries", "strategy", StrategyType.DB_OPTIMISTIC.name());
		this.maxRetries = maxRetries;
		this.backoffMinMs = backoffMinMs;
		this.backoffMaxMs = backoffMaxMs;
	}

	@Override
	public StrategyType type() {
		return StrategyType.DB_OPTIMISTIC;
	}

	@Override
	public IssueResult issue(IssueCommand command) {
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try {
				return transactionTemplate.execute(status -> {
					CouponEvent event = issuer.findEvent(command.eventId());
					return issuer.tryIssue(event, command, type(), event::increaseIssuedQuantity);
				});
			} catch (OptimisticLockingFailureException e) {
				retryCounter.increment();
				sleepBackoff();
			}
		}
		return IssueResult.error("OPTIMISTIC_RETRY_EXCEEDED");
	}

	private void sleepBackoff() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(backoffMinMs, backoffMaxMs + 1));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("재시도 대기 중 인터럽트", e);
		}
	}
}
