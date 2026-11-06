package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 락 없음. 초과 발급과 중복 발급이 실제로 나는 것을 보여주는 반면교사용. */
@Component
@RequiredArgsConstructor
class NaiveIssueStrategy implements IssueStrategy {

	private final CouponIssuer issuer;

	@Override
	public StrategyType type() {
		return StrategyType.NAIVE;
	}

	@Override
	@Transactional
	public IssueResult issue(IssueCommand command) {
		return issuer.issueWithoutLock(command, type());
	}
}
