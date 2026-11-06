package com.skwzz.fcfs.simulator.client;

/** coupon-api의 DB 기준 정합성 검증 결과 (GET /api/events/{id}/verification) */
public record Verification(
		int totalQuantity,
		long issuedInDb,
		int issuedQuantityCounter,
		long overIssued,
		long duplicateIssued,
		boolean passed) {
}
