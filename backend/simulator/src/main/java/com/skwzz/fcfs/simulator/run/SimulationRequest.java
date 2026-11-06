package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.StrategyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * POST /api/simulations 본문. 선택 항목은 래퍼 타입으로 받는다.
 *
 * @param eventId 기존 이벤트를 초기화해서 다시 쓸 때. 없으면 stock·strategy로 새 이벤트를 만든다
 * @param rampUpMs 0이면 모든 사용자가 동시에 출발. 그 외에는 이 시간 동안 고르게 나눠 출발
 * @param behavior 사용자 행동 모델. preset만 주면 프리셋 값, 다른 항목을 주면 그 값만 덮어쓴다
 */
public record SimulationRequest(
		Long eventId,
		@Min(1) @Max(1_000_000) Integer stock,
		@NotNull StrategyType strategy,
		Boolean queueEnabled,
		@NotNull @Min(1) @Max(10_000) Integer userCount,
		@Min(0) @Max(60_000) Integer rampUpMs,
		Behavior behavior) {

	/** 대기열 관련 항목(refreshWhileWaiting, abandon, polling)은 Phase 5 */
	public record Behavior(
			BehaviorPreset preset,
			List<Integer> thinkTimeMs,
			MultiClick multiClick,
			Retry retry,
			Integer timeoutMs) {
	}

	public record MultiClick(Double ratio, Integer clicks) {
	}

	public record Retry(Integer maxRetries, Integer backoffMs, Boolean jitter) {
	}

	int rampUp() {
		return rampUpMs != null ? rampUpMs : 0;
	}

	BehaviorPreset preset() {
		return behavior != null && behavior.preset() != null ? behavior.preset() : BehaviorPreset.IDEAL;
	}

	/** 프리셋 값에 요청에서 준 값을 덮어쓴다. 범위를 벗어나면 IllegalArgumentException */
	BehaviorModel behaviorModel() {
		BehaviorModel base = BehaviorModel.of(preset());
		if (behavior == null) {
			return base;
		}
		List<Integer> think = behavior.thinkTimeMs();
		if (think != null && think.size() != 2) {
			throw new IllegalArgumentException("행동 모델: thinkTimeMs는 [최소, 최대]");
		}
		MultiClick mc = behavior.multiClick();
		Retry retry = behavior.retry();
		return new BehaviorModel(
				base.preset(),
				think != null ? think.get(0) : base.thinkMinMs(),
				think != null ? think.get(1) : base.thinkMaxMs(),
				mc != null && mc.ratio() != null ? mc.ratio() : base.multiClickRatio(),
				mc != null && mc.clicks() != null ? mc.clicks() : base.clicks(),
				retry != null && retry.maxRetries() != null ? retry.maxRetries() : base.maxRetries(),
				retry != null && retry.backoffMs() != null ? retry.backoffMs() : base.backoffMs(),
				retry != null && retry.jitter() != null ? retry.jitter() : base.jitter(),
				behavior.timeoutMs() != null ? behavior.timeoutMs() : base.timeoutMs());
	}

	/** 실행 기록에 실제로 적용한 값이 남도록, 행동 모델을 모두 채운 요청으로 바꾼다. */
	SimulationRequest withResolvedBehavior() {
		BehaviorModel m = behaviorModel();
		Behavior resolved = new Behavior(m.preset(), List.of(m.thinkMinMs(), m.thinkMaxMs()),
				new MultiClick(m.multiClickRatio(), m.clicks()), new Retry(m.maxRetries(), m.backoffMs(), m.jitter()),
				m.timeoutMs());
		return new SimulationRequest(eventId, stock, strategy, queueEnabled, userCount, rampUpMs, resolved);
	}

	/** 아직 구현되지 않은 옵션은 조용히 무시하지 않고 거절한다. */
	void validateSupported() {
		if (eventId == null && stock == null) {
			throw new IllegalArgumentException("eventId가 없으면 stock은 필수입니다");
		}
		if (Boolean.TRUE.equals(queueEnabled)) {
			throw new IllegalArgumentException("대기열은 Phase 5에서 구현합니다");
		}
		behaviorModel();
	}
}
