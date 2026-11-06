package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;

/** 발급 동시성 전략. 구현체는 트랜잭션 경계까지 직접 책임진다. */
public interface IssueStrategy {

	StrategyType type();

	IssueResult issue(IssueCommand command);
}
