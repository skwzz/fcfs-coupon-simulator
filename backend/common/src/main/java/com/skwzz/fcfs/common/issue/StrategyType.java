package com.skwzz.fcfs.common.issue;

/** 쿠폰 발급 동시성 전략. 자세한 설명은 docs/03-issuance-strategies.md */
public enum StrategyType {
	/** 락 없음. 초과 발급과 중복 발급이 실제로 나는 것을 보여주는 반면교사용 */
	NAIVE,
	/** JVM 안에서만 유효한 synchronized. API 인스턴스가 2대면 깨진다 */
	SYNCHRONIZED,
	/** SELECT ... FOR UPDATE */
	DB_PESSIMISTIC,
	/** @Version + 재시도 */
	DB_OPTIMISTIC,
	/** Redisson 분산 락 */
	REDIS_LOCK,
	/** Lua 스크립트로 원자적 판정 */
	REDIS_ATOMIC,
	/** Redis 판정 + Kafka 비동기 저장 */
	REDIS_KAFKA
}
