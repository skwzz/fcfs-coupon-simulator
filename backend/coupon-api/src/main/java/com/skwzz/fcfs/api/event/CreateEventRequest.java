package com.skwzz.fcfs.api.event;

import com.skwzz.fcfs.common.issue.StrategyType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 선택 항목은 래퍼 타입으로 받는다. Jackson 3는 기본값으로 생략된 필드를 primitive에 넣지 못하고 400을 낸다. */
public record CreateEventRequest(
		@NotBlank String name,
		@Min(1) int totalQuantity,
		@NotNull StrategyType strategy,
		Boolean queueEnabled,
		Integer admitPerSecond,
		Boolean uniqueConstraintEnabled,
		Instant startAt) {

	CouponEvent toEntity() {
		return new CouponEvent(name, totalQuantity, strategy, Boolean.TRUE.equals(queueEnabled),
				admitPerSecond != null ? admitPerSecond : 200,
				uniqueConstraintEnabled == null || uniqueConstraintEnabled,
				startAt);
	}
}
