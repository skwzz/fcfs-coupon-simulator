package com.skwzz.fcfs.simulator.run;

/** 사용자 행동 모델 프리셋. docs/01-requirements.md 3.5 */
public enum BehaviorPreset {
	/** 1회 요청, 재시도·이탈 없음 */
	IDEAL,
	/** Phase 4 */
	REALISTIC,
	/** Phase 4 */
	AGGRESSIVE
}
