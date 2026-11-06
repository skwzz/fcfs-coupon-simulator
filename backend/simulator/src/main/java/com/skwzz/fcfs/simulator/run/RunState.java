package com.skwzz.fcfs.simulator.run;

public enum RunState {
	/** 이벤트 준비 중 또는 가상 사용자 요청 중 */
	RUNNING,
	/** 모든 사용자가 끝남 */
	DONE,
	/** 사용자가 중단함 */
	STOPPED,
	/** coupon-api 연결 실패 등으로 시작하지 못함 */
	FAILED
}
