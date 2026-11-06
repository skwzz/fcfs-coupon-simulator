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
 * @param queueEnabled 새로 만드는 이벤트에 대기열을 쓸지. 사용자는 줄을 서고, 입장한 뒤에 발급을 요청한다
 * @param admitPerSecond 대기열에서 초당 입장시키는 수 (기본 200)
 * @param queueNotifyMode 대기 중 순번을 받는 방법. SSE(서버가 push) 또는 POLLING(사용자가 주기적으로 조회)
 * @param rampUpMs 0이면 모든 사용자가 동시에 출발. 그 외에는 이 시간 동안 고르게 나눠 출발
 * @param behavior 사용자 행동 모델. preset만 주면 프리셋 값, 다른 항목을 주면 그 값만 덮어쓴다
 */
public record SimulationRequest(
		Long eventId,
		@Min(1) @Max(1_000_000) Integer stock,
		@NotNull StrategyType strategy,
		Boolean queueEnabled,
		@Min(1) @Max(100_000) Integer admitPerSecond,
		NotifyMode queueNotifyMode,
		@NotNull @Min(1) @Max(10_000) Integer userCount,
		@Min(0) @Max(60_000) Integer rampUpMs,
		Behavior behavior) {

	static final int DEFAULT_ADMIT_PER_SECOND = 200;

	public enum NotifyMode {
		SSE, POLLING
	}

	public record Behavior(
			BehaviorPreset preset,
			List<Integer> thinkTimeMs,
			MultiClick multiClick,
			Retry retry,
			Integer timeoutMs,
			RefreshWhileWaiting refreshWhileWaiting,
			Abandon abandon,
			Polling polling) {
	}

	public record MultiClick(Double ratio, Integer clicks) {
	}

	public record Retry(Integer maxRetries, Integer backoffMs, Boolean jitter) {
	}

	public record RefreshWhileWaiting(Double ratePerMinute) {
	}

	/** @param medianWaitSec 0이면 이탈 없음 */
	public record Abandon(Integer medianWaitSec) {
	}

	public record Polling(Integer intervalMs, Integer jitterMs) {
	}

	int rampUp() {
		return rampUpMs != null ? rampUpMs : 0;
	}

	boolean queued() {
		return Boolean.TRUE.equals(queueEnabled);
	}

	int admitRate() {
		return admitPerSecond != null ? admitPerSecond : DEFAULT_ADMIT_PER_SECOND;
	}

	NotifyMode notifyMode() {
		return queueNotifyMode != null ? queueNotifyMode : NotifyMode.SSE;
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
		RefreshWhileWaiting refresh = behavior.refreshWhileWaiting();
		Abandon abandon = behavior.abandon();
		Polling polling = behavior.polling();
		return new BehaviorModel(
				base.preset(),
				think != null ? think.get(0) : base.thinkMinMs(),
				think != null ? think.get(1) : base.thinkMaxMs(),
				mc != null && mc.ratio() != null ? mc.ratio() : base.multiClickRatio(),
				mc != null && mc.clicks() != null ? mc.clicks() : base.clicks(),
				retry != null && retry.maxRetries() != null ? retry.maxRetries() : base.maxRetries(),
				retry != null && retry.backoffMs() != null ? retry.backoffMs() : base.backoffMs(),
				retry != null && retry.jitter() != null ? retry.jitter() : base.jitter(),
				behavior.timeoutMs() != null ? behavior.timeoutMs() : base.timeoutMs(),
				refresh != null && refresh.ratePerMinute() != null ? refresh.ratePerMinute() : base.refreshPerMinute(),
				abandon != null && abandon.medianWaitSec() != null ? abandon.medianWaitSec() : base.abandonMedianSec(),
				polling != null && polling.intervalMs() != null ? polling.intervalMs() : base.pollIntervalMs(),
				polling != null && polling.jitterMs() != null ? polling.jitterMs() : base.pollJitterMs());
	}

	/** 실행 기록에 실제로 적용한 값이 남도록, 행동 모델과 대기열 설정을 모두 채운 요청으로 바꾼다. */
	SimulationRequest withResolvedBehavior() {
		BehaviorModel m = behaviorModel();
		Behavior resolved = new Behavior(m.preset(), List.of(m.thinkMinMs(), m.thinkMaxMs()),
				new MultiClick(m.multiClickRatio(), m.clicks()), new Retry(m.maxRetries(), m.backoffMs(), m.jitter()),
				m.timeoutMs(), new RefreshWhileWaiting(m.refreshPerMinute()), new Abandon(m.abandonMedianSec()),
				new Polling(m.pollIntervalMs(), m.pollJitterMs()));
		return new SimulationRequest(eventId, stock, strategy, queued(), queued() ? admitRate() : null,
				queued() ? notifyMode() : null, userCount, rampUpMs, resolved);
	}

	void validateSupported() {
		if (eventId == null && stock == null) {
			throw new IllegalArgumentException("eventId가 없으면 stock은 필수입니다");
		}
		behaviorModel();
	}
}
