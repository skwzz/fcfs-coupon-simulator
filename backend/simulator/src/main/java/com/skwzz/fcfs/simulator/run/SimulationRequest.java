package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.StrategyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/simulations 본문. 선택 항목은 래퍼 타입으로 받는다.
 *
 * @param eventId 기존 이벤트를 초기화해서 다시 쓸 때. 없으면 stock·strategy로 새 이벤트를 만든다
 * @param rampUpMs 0이면 모든 사용자가 동시에 출발. 그 외에는 이 시간 동안 고르게 나눠 출발
 */
public record SimulationRequest(
		Long eventId,
		@Min(1) @Max(1_000_000) Integer stock,
		@NotNull StrategyType strategy,
		Boolean queueEnabled,
		@NotNull @Min(1) @Max(10_000) Integer userCount,
		@Min(0) @Max(60_000) Integer rampUpMs,
		Behavior behavior) {

	public record Behavior(BehaviorPreset preset) {
	}

	int rampUp() {
		return rampUpMs != null ? rampUpMs : 0;
	}

	BehaviorPreset preset() {
		return behavior != null && behavior.preset() != null ? behavior.preset() : BehaviorPreset.IDEAL;
	}

	/** 아직 구현되지 않은 옵션은 조용히 무시하지 않고 거절한다. */
	void validateSupported() {
		if (eventId == null && stock == null) {
			throw new IllegalArgumentException("eventId가 없으면 stock은 필수입니다");
		}
		if (Boolean.TRUE.equals(queueEnabled)) {
			throw new IllegalArgumentException("대기열은 Phase 5에서 구현합니다");
		}
		if (preset() != BehaviorPreset.IDEAL) {
			throw new IllegalArgumentException("행동 모델 " + preset() + "는 Phase 4에서 구현합니다");
		}
	}
}
