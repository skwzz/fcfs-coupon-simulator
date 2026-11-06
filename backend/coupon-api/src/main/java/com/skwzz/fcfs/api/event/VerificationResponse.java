package com.skwzz.fcfs.api.event;

/**
 * DB 기준 정합성 판정.
 * 초과 발급 = 발급 행 수 - 총 수량, 중복 발급 = 발급 행 수 - 발급받은 사용자 수.
 * issuedQuantity는 NAIVE에서 갱신 손실로 실제보다 작게 남을 수 있어서 함께 보여준다.
 * Redis 전략은 DB 카운터를 쓰지 않으므로 redisStock(남은 재고), redisIssuedUsers(발급 Set 크기)로 Redis 쪽 상태를 본다.
 * 정상이면 redisIssuedUsers = issuedInDb, redisStock = totalQuantity - issuedInDb.
 */
public record VerificationResponse(
		int totalQuantity,
		long issuedInDb,
		int issuedQuantityCounter,
		long overIssued,
		long duplicateIssued,
		boolean passed,
		Long redisStock,
		long redisIssuedUsers) {
}
