package com.skwzz.fcfs.simulator.run;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 프리셋에 사용자가 바꾼 값을 덮어쓴, 실제로 적용할 행동 모델. docs/01-requirements.md 3.5
 *
 * @param thinkMinMs 버튼을 누르기까지 걸리는 시간 (균등분포 최솟값)
 * @param multiClickRatio 연타하는 사용자 비율 (0~1)
 * @param clicks 연타하는 사용자의 클릭 수. 클릭마다 다른 요청 id를 쓴다 (화면에서 버튼을 여러 번 누른 것)
 * @param maxRetries 에러(5xx, 타임아웃)를 받았을 때 재시도 횟수. 재시도는 같은 요청 id를 쓴다
 * @param backoffMs 재시도 대기 기본값. 시도마다 두 배 (지수 백오프). 0이면 바로 재시도
 * @param jitter 백오프에 무작위성을 줄지 (0 ~ 백오프 사이에서 고름). 재시도가 한순간에 몰리는 것을 막는다
 * @param timeoutMs 클라이언트가 응답을 기다리는 최대 시간. 넘기면 포기하고 에러로 본다 (서버는 계속 처리할 수 있음)
 * @param refreshPerMinute 대기 중 새로고침 횟수 (분당 평균). 새로고침하면 줄에서 나갔다가 맨 뒤로 다시 선다
 * @param abandonMedianSec 대기 중 이탈. 이 시간만큼 기다렸을 때 절반이 떠난다 (0이면 끝까지 기다림)
 * @param pollIntervalMs 폴링 모드에서 순번을 확인하는 간격
 * @param pollJitterMs 폴링 간격의 흔들림 (±). 모두가 같은 순간에 묻지 않게 한다
 */
public record BehaviorModel(
		BehaviorPreset preset,
		int thinkMinMs,
		int thinkMaxMs,
		double multiClickRatio,
		int clicks,
		int maxRetries,
		int backoffMs,
		boolean jitter,
		int timeoutMs,
		double refreshPerMinute,
		int abandonMedianSec,
		int pollIntervalMs,
		int pollJitterMs) {

	/** 연타할 때 클릭 사이 간격 */
	static final int CLICK_INTERVAL_MS = 100;
	private static final int MAX_BACKOFF_MS = 10_000;

	public BehaviorModel {
		require(thinkMinMs >= 0 && thinkMaxMs >= thinkMinMs && thinkMaxMs <= 60_000, "thinkTimeMs는 [최소, 최대], 0~60000");
		require(multiClickRatio >= 0 && multiClickRatio <= 1, "multiClick.ratio는 0~1");
		require(clicks >= 1 && clicks <= 10, "multiClick.clicks는 1~10");
		require(maxRetries >= 0 && maxRetries <= 10, "retry.maxRetries는 0~10");
		require(backoffMs >= 0 && backoffMs <= MAX_BACKOFF_MS, "retry.backoffMs는 0~10000");
		require(timeoutMs >= 100 && timeoutMs <= 60_000, "timeoutMs는 100~60000");
		require(refreshPerMinute >= 0 && refreshPerMinute <= 60, "refreshWhileWaiting.ratePerMinute는 0~60");
		require(abandonMedianSec >= 0 && abandonMedianSec <= 3_600, "abandon.medianWaitSec는 0~3600 (0이면 이탈 없음)");
		require(pollIntervalMs >= 100 && pollIntervalMs <= 60_000, "polling.intervalMs는 100~60000");
		require(pollJitterMs >= 0 && pollJitterMs < pollIntervalMs, "polling.jitterMs는 0 이상, intervalMs 미만");
	}

	static BehaviorModel of(BehaviorPreset preset) {
		return switch (preset) {
			// 전략 자체를 순수하게 비교: 한 번 누르고 결과를 기다린다. 대기열에서도 끝까지 얌전히 기다린다
			case IDEAL -> new BehaviorModel(preset, 0, 0, 0, 1, 0, 0, false, 30_000, 0, 0, 1_000, 0);
			// 실제 이벤트에 가까운 사용자: 일부 연타, 에러 나면 백오프+지터로 두 번까지 재시도, 10초면 포기.
			// 대기 중 가끔 새로고침하고, 1분쯤 기다리면 절반이 떠난다
			case REALISTIC -> new BehaviorModel(preset, 0, 300, 0.2, 3, 2, 500, true, 10_000, 0.1, 60, 1_000, 300);
			// 매크로·조급한 사용자: 절반이 연타하고, 3초 만에 포기하고 기다림 없이 바로 다시 누른다.
			// 대기 중 새로고침을 자주 하지만 떠나지는 않는다
			case AGGRESSIVE -> new BehaviorModel(preset, 0, 0, 0.5, 3, 3, 0, false, 3_000, 0.5, 0, 1_000, 300);
		};
	}

	int thinkTime() {
		return thinkMaxMs == 0 ? 0 : ThreadLocalRandom.current().nextInt(thinkMinMs, thinkMaxMs + 1);
	}

	int clicksForUser() {
		return clicks > 1 && ThreadLocalRandom.current().nextDouble() < multiClickRatio ? clicks : 1;
	}

	/** @param retry 1부터 */
	long backoff(int retry) {
		if (backoffMs == 0) {
			return 0;
		}
		long exp = Math.min(MAX_BACKOFF_MS, (long) backoffMs << Math.min(retry - 1, 10));
		return jitter ? ThreadLocalRandom.current().nextLong(exp + 1) : exp;
	}

	/** 다음 새로고침까지 시간. 새로고침은 아무 때나 일어나므로 지수분포 (평균 1분 / 분당 횟수) */
	long nextRefreshMs() {
		if (refreshPerMinute == 0) {
			return Long.MAX_VALUE;
		}
		return exponential(60_000 / refreshPerMinute);
	}

	/** 이 사용자가 참고 기다리는 시간. 중앙값이 abandonMedianSec인 지수분포 (오래 기다릴수록 떠난 사람이 많다) */
	long patienceMs() {
		if (abandonMedianSec == 0) {
			return Long.MAX_VALUE;
		}
		return exponential(abandonMedianSec * 1_000 / Math.log(2));
	}

	long pollDelayMs() {
		if (pollJitterMs == 0) {
			return pollIntervalMs;
		}
		return pollIntervalMs + ThreadLocalRandom.current().nextLong(-pollJitterMs, pollJitterMs + 1);
	}

	private static long exponential(double meanMs) {
		return (long) (-Math.log(1 - ThreadLocalRandom.current().nextDouble()) * meanMs);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException("행동 모델: " + message);
		}
	}
}
