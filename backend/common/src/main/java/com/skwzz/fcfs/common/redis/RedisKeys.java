package com.skwzz.fcfs.common.redis;

/**
 * Redis 키 규칙. 중괄호로 감싼 {eventId}는 Redis Cluster 해시태그라서
 * 같은 이벤트의 키가 같은 노드에 모이고, 여러 키를 다루는 Lua 스크립트가 클러스터에서도 동작한다.
 */
public final class RedisKeys {

	private RedisKeys() {
	}

	/** 남은 재고 (String, DECR) */
	public static String stock(long eventId) {
		return "coupon:{" + eventId + "}:stock";
	}

	/** 발급받은 userId (Set, 중복 방지) */
	public static String issuedUsers(long eventId) {
		return "coupon:{" + eventId + "}:issued";
	}

	/** 대기열 (Sorted Set, score = 진입 시각) */
	public static String waitingQueue(long eventId) {
		return "queue:{" + eventId + "}:waiting";
	}

	/** 입장한 사용자 (Sorted Set, score = 토큰 만료 시각) */
	public static String activeQueue(long eventId) {
		return "queue:{" + eventId + "}:active";
	}

	/** 입장한 사용자의 입장 토큰 (Hash, userId → token). 만료돼도 남겨서 "만료"와 "들어온 적 없음"을 구분한다 */
	public static String queueTokens(long eventId) {
		return "queue:{" + eventId + "}:tokens";
	}

	/** 대기열 설정과 상태 (Hash: rate, ttl-ms, admitted-at, admitted, closed) */
	public static String queueMeta(long eventId) {
		return "queue:{" + eventId + "}:meta";
	}

	/** 입장 스케줄러가 돌아야 하는 이벤트 id (Set). 이벤트를 가로지르는 키라 해시태그가 없다 */
	public static final String QUEUE_EVENTS = "queue:events";

	/** 분산 락 전략용 */
	public static String issueLock(long eventId) {
		return "lock:coupon:{" + eventId + "}";
	}
}
