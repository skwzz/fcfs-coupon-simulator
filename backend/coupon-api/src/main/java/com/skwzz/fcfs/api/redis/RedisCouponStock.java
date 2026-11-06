package com.skwzz.fcfs.api.redis;

import com.skwzz.fcfs.common.redis.RedisKeys;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Redis에 둔 이벤트별 재고(String)와 발급받은 사용자(Set). 키 규칙은 RedisKeys */
@Component
public class RedisCouponStock {

	public enum Decision {
		ISSUED, SOLD_OUT, DUPLICATE, NOT_INITIALIZED
	}

	private static final RedisScript<Long> ISSUE = script("scripts/issue.lua");
	private static final RedisScript<Long> COMPENSATE = script("scripts/compensate.lua");
	private static final RedisScript<Long> INIT_IF_ABSENT = script("scripts/init-if-absent.lua");

	private final StringRedisTemplate redis;

	public RedisCouponStock(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public Decision tryIssue(long eventId, long userId) {
		Long result = redis.execute(ISSUE, keys(eventId), Long.toString(userId));
		return switch (result.intValue()) {
			case 1 -> Decision.ISSUED;
			case 0 -> Decision.SOLD_OUT;
			case -1 -> Decision.DUPLICATE;
			default -> Decision.NOT_INITIALIZED;
		};
	}

	/** @param removeUser 발급 Set에서도 뺄지. DB에 이미 같은 사용자의 발급이 있으면(중복) Set은 그대로 둔다 */
	public void compensate(long eventId, long userId, boolean removeUser) {
		redis.execute(COMPENSATE, keys(eventId), Long.toString(userId), removeUser ? "1" : "0");
	}

	/** 이벤트 생성·초기화 때 재고를 총 수량으로 맞추고 발급 Set을 비운다. */
	public void reset(long eventId, int totalQuantity) {
		redis.delete(keys(eventId));
		redis.opsForValue().set(RedisKeys.stock(eventId), Integer.toString(totalQuantity));
	}

	/** 키가 없을 때만 DB 기준으로 채운다. 이미 있으면 아무것도 하지 않는다. */
	public void initializeIfAbsent(long eventId, int remaining, Collection<Long> issuedUserIds) {
		List<String> args = new ArrayList<>(issuedUserIds.size() + 1);
		args.add(Integer.toString(Math.max(0, remaining)));
		issuedUserIds.forEach(id -> args.add(Long.toString(id)));
		redis.execute(INIT_IF_ABSENT, keys(eventId), args.toArray());
	}

	/** 남은 재고. 키가 없으면 null */
	public Long stock(long eventId) {
		String value = redis.opsForValue().get(RedisKeys.stock(eventId));
		return value == null ? null : Long.valueOf(value);
	}

	public long issuedUserCount(long eventId) {
		Long size = redis.opsForSet().size(RedisKeys.issuedUsers(eventId));
		return size == null ? 0 : size;
	}

	private static List<String> keys(long eventId) {
		return List.of(RedisKeys.stock(eventId), RedisKeys.issuedUsers(eventId));
	}

	private static RedisScript<Long> script(String path) {
		return RedisScript.of(new ClassPathResource(path), Long.class);
	}
}
