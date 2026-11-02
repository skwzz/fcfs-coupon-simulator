package com.skwzz.fcfs.api.event;

import com.skwzz.fcfs.common.issue.StrategyType;
import java.time.Instant;

public record EventResponse(
		long id,
		String name,
		int totalQuantity,
		int issuedQuantity,
		int remainingQuantity,
		StrategyType strategy,
		boolean queueEnabled,
		int admitPerSecond,
		boolean uniqueConstraintEnabled,
		Instant startAt,
		Instant createdAt) {

	static EventResponse from(CouponEvent e) {
		return new EventResponse(e.getId(), e.getName(), e.getTotalQuantity(), e.getIssuedQuantity(), e.remainingQuantity(),
				e.getStrategy(), e.isQueueEnabled(), e.getAdmitPerSecond(), e.isUniqueConstraintEnabled(), e.getStartAt(),
				e.getCreatedAt());
	}
}
