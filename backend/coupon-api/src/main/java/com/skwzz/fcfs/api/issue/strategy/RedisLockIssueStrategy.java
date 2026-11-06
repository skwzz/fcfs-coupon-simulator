package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.common.redis.RedisKeys;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redisson 분산 락 안에서 NAIVE와 같은 로직을 실행한다. SYNCHRONIZED와 달리 인스턴스가 여러 대여도 유효하다.
 * SYNCHRONIZED와 같은 이유로 트랜잭션은 락 안에서 시작하고 커밋까지 끝낸다.
 * 모든 요청이 락 하나를 기다리므로, 대기 시간(wait-ms)을 넘기면 LOCK_TIMEOUT으로 실패한다.
 * <p>
 * 락 유지 시간(lease)을 고정하지 않고 Redisson watchdog에 맡긴다. 락을 잡은 스레드가 살아 있는 동안 자동으로 연장되고,
 * 인스턴스가 죽으면 30초 뒤 풀린다. lease를 5초로 고정했을 때는 메모리 압박으로 처리가 5초를 넘기자
 * 락이 먼저 풀려 다른 요청이 들어왔고, 초과 발급 1건이 났다 (docs/03 REDIS_LOCK).
 */
@Component
class RedisLockIssueStrategy implements IssueStrategy {

	private final RedissonClient redisson;
	private final CouponIssuer issuer;
	private final TransactionTemplate transactionTemplate;
	private final long waitMs;

	RedisLockIssueStrategy(RedissonClient redisson, CouponIssuer issuer, TransactionTemplate transactionTemplate,
			@Value("${coupon.issue.redis-lock.wait-ms:10000}") long waitMs) {
		this.redisson = redisson;
		this.issuer = issuer;
		this.transactionTemplate = transactionTemplate;
		this.waitMs = waitMs;
	}

	@Override
	public StrategyType type() {
		return StrategyType.REDIS_LOCK;
	}

	@Override
	public IssueResult issue(IssueCommand command) {
		RLock lock = redisson.getLock(RedisKeys.issueLock(command.eventId()));
		try {
			if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) {
				return IssueResult.error("LOCK_TIMEOUT");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return IssueResult.error("LOCK_INTERRUPTED");
		}
		try {
			return transactionTemplate.execute(status -> issuer.issueWithoutLock(command, type()));
		} finally {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}
}
