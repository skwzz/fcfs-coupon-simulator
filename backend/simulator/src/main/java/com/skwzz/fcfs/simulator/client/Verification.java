package com.skwzz.fcfs.simulator.client;

/**
 * coupon-api의 DB 기준 정합성 검증 결과 (GET /api/events/{id}/verification).
 * redisStock·redisIssuedUsers는 Redis 쪽 상태 (Redis 전략 비교용, Phase 4 이전 기록은 null)
 */
public record Verification(
		int totalQuantity,
		long issuedInDb,
		int issuedQuantityCounter,
		long overIssued,
		long duplicateIssued,
		boolean passed,
		Long redisStock,
		Long redisIssuedUsers) {
}
