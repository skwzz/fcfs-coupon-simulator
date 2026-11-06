package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NAIVE 로직을 synchronized로 감싼다.
 * 메서드에 @Transactional을 같이 붙이면 락이 풀린 뒤에 커밋되어 다음 요청이 커밋 전 값을 읽으므로,
 * 락 안에서 트랜잭션을 시작하고 커밋까지 끝낸다.
 * JVM 하나 안에서만 유효해서 API 인스턴스가 2대면 다시 초과 발급이 난다.
 */
@Component
@RequiredArgsConstructor
class SynchronizedIssueStrategy implements IssueStrategy {

	private final CouponIssuer issuer;
	private final TransactionTemplate transactionTemplate;

	@Override
	public StrategyType type() {
		return StrategyType.SYNCHRONIZED;
	}

	@Override
	public synchronized IssueResult issue(IssueCommand command) {
		return transactionTemplate.execute(status -> issuer.issueWithoutLock(command, type()));
	}
}
