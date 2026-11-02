package com.skwzz.fcfs.common.issue;

/** 발급 요청 결과. HTTP 상태 코드와의 대응은 docs/04-api-spec.md */
public enum IssueStatus {
	/** 발급 성공 (동기 전략) */
	ISSUED,
	/** 발급 확정, DB 반영 대기 중 (REDIS_KAFKA) */
	ACCEPTED,
	/** 이미 발급받음 */
	DUPLICATE,
	/** 재고 소진 */
	SOLD_OUT,
	/** 대기열 입장 토큰이 없거나 만료됨 */
	NOT_ADMITTED,
	/** 락 타임아웃, 재시도 초과 등 */
	ERROR
}
