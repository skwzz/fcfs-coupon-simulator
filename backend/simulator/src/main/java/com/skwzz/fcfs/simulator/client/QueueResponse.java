package com.skwzz.fcfs.simulator.client;

/**
 * coupon-api 대기열 응답 (queue, queue/me, queue/me/stream 이벤트 하나).
 * 응답을 못 받았으면 status = ERROR이고 reason에 사유가 들어간다 (simulator에서만 쓰는 값).
 *
 * @param status WAITING, ADMITTED, SOLD_OUT, EXPIRED, NOT_IN_QUEUE, ERROR
 * @param rank 내 순번 (1부터)
 */
public record QueueResponse(String status, Integer rank, Integer totalWaiting, Integer estimatedWaitSec, String token,
		Integer expiresInSec, String reason) {

	public static final String WAITING = "WAITING";
	public static final String ADMITTED = "ADMITTED";
	public static final String SOLD_OUT = "SOLD_OUT";
	public static final String ERROR = "ERROR";

	static QueueResponse error(String reason) {
		return new QueueResponse(ERROR, null, null, null, null, null, reason);
	}

	public boolean is(String s) {
		return s.equals(status);
	}
}
