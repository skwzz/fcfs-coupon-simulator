package com.skwzz.fcfs.common.kafka;

public final class KafkaTopics {

	private KafkaTopics() {
	}

	/** 비동기 발급 요청. 키 = eventId (같은 이벤트는 같은 파티션) */
	public static final String COUPON_ISSUE = "coupon-issue";

	/** 재시도를 다 쓴 발급 요청 */
	public static final String COUPON_ISSUE_DLT = COUPON_ISSUE + ".DLT";
}
