package com.skwzz.fcfs.simulator.client;

/** 이벤트 생성·초기화·검증 같은 관리용 호출이 실패했을 때. 발급 요청 실패는 IssueOutcome으로 돌려준다. */
public class CouponApiException extends RuntimeException {

	public CouponApiException(String message) {
		super(message);
	}
}
