package com.skwzz.fcfs.api.common;

import com.skwzz.fcfs.common.issue.StrategyType;

public class UnsupportedStrategyException extends RuntimeException {

	public UnsupportedStrategyException(StrategyType type) {
		super("아직 구현되지 않은 발급 전략입니다: " + type);
	}
}
