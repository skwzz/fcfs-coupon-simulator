package com.skwzz.fcfs.api.queue;

import com.skwzz.fcfs.common.redis.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 대기열. 상태는 모두 Redis에 있어서 API 인스턴스 어느 쪽으로 요청이 가도 같은 줄을 본다.
 * <ul>
 * <li>waiting (Sorted Set): 줄. score = 진입 시각(Redis 시계)</li>
 * <li>active (Sorted Set): 입장한 사용자. score = 토큰 만료 시각</li>
 * <li>tokens (Hash): 입장 토큰</li>
 * <li>meta (Hash): 초당 입장 수, 토큰 유효 시간, 마지막 입장 기준 시각, 매진으로 닫혔는지</li>
 * </ul>
 * 시각은 모두 Redis TIME 기준이다. API 서버 두 대의 시계가 조금 달라도 순서와 입장 속도가 어긋나지 않는다.
 */
@Component
public class WaitingQueue {

	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> ENTER = RedisScript.of(new ClassPathResource("scripts/queue-enter.lua"),
			List.class);
	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> STATUS = RedisScript.of(new ClassPathResource("scripts/queue-status.lua"),
			List.class);
	private static final RedisScript<Long> ADMIT = RedisScript.of(new ClassPathResource("scripts/queue-admit.lua"),
			Long.class);
	private static final RedisScript<Long> CHECK = RedisScript.of(new ClassPathResource("scripts/queue-check.lua"),
			Long.class);

	private final StringRedisTemplate redis;
	private final long tokenTtlMs;
	/** 이벤트별 초당 입장 수. 대기열을 쓰지 않으면 0. 이벤트를 만든 뒤에는 바뀌지 않으므로 인스턴스마다 캐시한다 */
	private final Map<Long, Integer> rates = new ConcurrentHashMap<>();
	/** 이 인스턴스가 이미 닫은 대기열. 매진 응답마다 Redis에 다시 쓰지 않으려고 둔다 */
	private final Set<Long> closed = ConcurrentHashMap.newKeySet();

	public WaitingQueue(StringRedisTemplate redis, @Value("${coupon.queue.token-ttl-ms:30000}") long tokenTtlMs) {
		this.redis = redis;
		this.tokenTtlMs = tokenTtlMs;
	}

	/** 이벤트 생성·초기화 때 줄을 비우고, 대기열을 쓰는 이벤트면 입장 스케줄러 대상에 넣는다. */
	public void reset(long eventId, boolean enabled, int admitPerSecond) {
		redis.delete(keys(eventId));
		rates.put(eventId, enabled ? admitPerSecond : 0);
		closed.remove(eventId);
		if (enabled) {
			redis.opsForHash().putAll(RedisKeys.queueMeta(eventId),
					Map.of("rate", Integer.toString(admitPerSecond), "ttl-ms", Long.toString(tokenTtlMs)));
			redis.opsForSet().add(RedisKeys.QUEUE_EVENTS, Long.toString(eventId));
		} else {
			redis.opsForSet().remove(RedisKeys.QUEUE_EVENTS, Long.toString(eventId));
		}
	}

	public boolean isEnabled(long eventId) {
		return rate(eventId) > 0;
	}

	/** 줄을 선다. 이미 서 있으면 그 자리 그대로, 이미 입장했으면 입장 상태를 돌려준다. */
	public QueueStatus enter(long eventId, long userId) {
		int rate = requireEnabled(eventId);
		List<?> result = redis.execute(ENTER, keys(eventId), Long.toString(userId));
		return switch ((String) result.getFirst()) {
			case "WAITING" -> QueueStatus.waiting(Integer.parseInt((String) result.get(1)),
					Integer.parseInt((String) result.get(2)), rate);
			case "ADMITTED" -> status(eventId, userId);
			case "SOLD_OUT" -> QueueStatus.of(QueueStatus.State.SOLD_OUT);
			default -> throw new QueueNotEnabledException(eventId); // Redis가 비워진 경우. 이벤트를 초기화해야 한다
		};
	}

	public QueueStatus status(long eventId, long userId) {
		return statuses(eventId, List.of(userId)).getFirst();
	}

	/** 여러 사용자의 상태를 Redis 왕복 한 번으로. 순서는 userIds와 같다 */
	public List<QueueStatus> statuses(long eventId, List<Long> userIds) {
		int rate = requireEnabled(eventId);
		String[] args = new String[userIds.size() + 1];
		args[0] = UUID.randomUUID().toString(); // 토큰을 만들 때 섞는 값. 사용자 id만으로는 토큰을 추측할 수 없게
		for (int i = 0; i < userIds.size(); i++) {
			args[i + 1] = Long.toString(userIds.get(i));
		}
		List<?> raw = redis.execute(STATUS, keys(eventId), (Object[]) args);
		List<QueueStatus> statuses = new ArrayList<>(raw.size());
		for (Object o : raw) {
			statuses.add(parse((String) o, rate));
		}
		return statuses;
	}

	private static QueueStatus parse(String s, int rate) {
		String[] p = s.split(":");
		return switch (p[0]) {
			case "W" -> QueueStatus.waiting(Integer.parseInt(p[1]), Integer.parseInt(p[2]), rate);
			case "A" -> QueueStatus.admitted(p[1], (long) Double.parseDouble(p[2]));
			case "S" -> QueueStatus.of(QueueStatus.State.SOLD_OUT);
			case "E" -> QueueStatus.of(QueueStatus.State.EXPIRED);
			default -> QueueStatus.of(QueueStatus.State.NOT_IN_QUEUE);
		};
	}

	/** 대기 취소 (이탈, 새로고침). 줄에 있었으면 true */
	public boolean leave(long eventId, long userId) {
		requireEnabled(eventId);
		Long removed = redis.opsForZSet().remove(RedisKeys.waitingQueue(eventId), Long.toString(userId));
		return removed != null && removed > 0;
	}

	/** 발급 요청의 입장 토큰이 이 사용자 것이고 아직 유효한지 */
	public boolean isAdmitted(long eventId, long userId, String token) {
		if (token == null || token.isBlank()) {
			return false;
		}
		Long ok = redis.execute(CHECK, List.of(RedisKeys.activeQueue(eventId), RedisKeys.queueTokens(eventId)),
				Long.toString(userId), token);
		return ok != null && ok == 1;
	}

	/**
	 * 재고가 소진되면 대기열을 닫고 줄을 비운다. 기다려도 받을 수 없는 사용자를 계속 세워 두지 않기 위해서다.
	 * 줄에 있던 사용자는 다음 상태 조회(또는 SSE)에서 SOLD_OUT을 받는다.
	 */
	public void close(long eventId) {
		if (!closed.add(eventId)) {
			return;
		}
		redis.opsForHash().put(RedisKeys.queueMeta(eventId), "closed", "1");
		redis.delete(RedisKeys.waitingQueue(eventId));
	}

	// ---- 입장 스케줄러 ----

	Set<String> scheduledEventIds() {
		Set<String> ids = redis.opsForSet().members(RedisKeys.QUEUE_EVENTS);
		return ids == null ? Set.of() : ids;
	}

	/** @return 이번에 입장시킨 수. 음수면 더 돌 필요 없음 (닫혔거나 대기열이 없음) */
	long admit(long eventId) {
		Long admitted = redis.execute(ADMIT, keys(eventId));
		return admitted == null ? -2 : admitted;
	}

	void unschedule(String eventId) {
		redis.opsForSet().remove(RedisKeys.QUEUE_EVENTS, eventId);
	}

	// ----

	int requireEnabled(long eventId) {
		int rate = rate(eventId);
		if (rate <= 0) {
			throw new QueueNotEnabledException(eventId);
		}
		return rate;
	}

	/**
	 * 캐시에 없으면 Redis meta에서 읽는다. DB에서 읽으면 이벤트 시작 순간 수천 명이 동시에 캐시 미스를 내서
	 * DB 커넥션 풀(10개)이 줄 서기 요청으로 꽉 찬다 (캐시 스탬피드, docs/06 Phase 5 결과).
	 * meta가 없으면 대기열을 쓰지 않는 이벤트다.
	 */
	private int rate(long eventId) {
		Integer rate = rates.get(eventId);
		if (rate == null) {
			Object value = redis.opsForHash().get(RedisKeys.queueMeta(eventId), "rate");
			rate = value == null ? 0 : Integer.parseInt((String) value);
			rates.put(eventId, rate);
		}
		return rate;
	}

	private static List<String> keys(long eventId) {
		return List.of(RedisKeys.waitingQueue(eventId), RedisKeys.activeQueue(eventId),
				RedisKeys.queueTokens(eventId), RedisKeys.queueMeta(eventId));
	}
}
