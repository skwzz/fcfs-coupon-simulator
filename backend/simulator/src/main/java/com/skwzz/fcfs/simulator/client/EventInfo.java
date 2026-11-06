package com.skwzz.fcfs.simulator.client;

import com.skwzz.fcfs.common.issue.StrategyType;

/** coupon-api 이벤트 응답 중 simulator가 쓰는 필드. 나머지 필드는 무시한다. */
public record EventInfo(long id, String name, int totalQuantity, int remainingQuantity, StrategyType strategy,
		boolean queueEnabled, int admitPerSecond) {
}
