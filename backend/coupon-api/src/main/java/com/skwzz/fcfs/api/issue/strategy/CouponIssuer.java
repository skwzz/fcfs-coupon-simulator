package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.common.EventNotFoundException;
import com.skwzz.fcfs.api.event.CouponEvent;
import com.skwzz.fcfs.api.event.CouponEventRepository;
import com.skwzz.fcfs.api.issue.CouponIssue;
import com.skwzz.fcfs.api.issue.CouponIssueRepository;
import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.api.redis.RedisCouponStock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 전략들이 공통으로 쓰는 "중복 확인 → 재고 확인 → 차감 → 발급 기록". 모두 호출하는 쪽의 트랜잭션 안에서 실행된다. */
@Component
@RequiredArgsConstructor
class CouponIssuer {

	private final CouponEventRepository eventRepository;
	private final CouponIssueRepository issueRepository;
	private final RedisCouponStock redisStock;
	/** 이벤트의 UNIQUE 제약 사용 여부는 바뀌지 않으므로 Redis 경로에서는 DB를 다시 읽지 않는다 */
	private final Map<Long, Boolean> uniqueConstraintCache = new ConcurrentHashMap<>();

	/** 재고 차감 방법은 전략마다 달라서 increaseStock으로 받는다. */
	IssueResult tryIssue(CouponEvent event, IssueCommand command, StrategyType strategy, Runnable increaseStock) {
		if (issueRepository.existsByEventIdAndUserId(event.getId(), command.userId())) {
			return IssueResult.duplicate();
		}
		if (event.isSoldOut()) {
			return IssueResult.soldOut();
		}
		increaseStock.run();
		issueRepository.save(CouponIssue.of(event, command, strategy));
		return IssueResult.issued();
	}

	/**
	 * 락도 버전 확인도 없이 "읽은 값 + 1"을 덮어쓴다. NAIVE와 SYNCHRONIZED가 쓴다.
	 * 동시에 들어온 요청이 같은 재고를 읽고 모두 통과하기 때문에 초과 발급이 난다.
	 */
	IssueResult issueWithoutLock(IssueCommand command, StrategyType strategy) {
		CouponEvent event = findEvent(command.eventId());
		int readQuantity = event.getIssuedQuantity();
		return tryIssue(event, command, strategy,
				() -> eventRepository.overwriteIssuedQuantity(command.eventId(), readQuantity + 1));
	}

	CouponEvent findEvent(long eventId) {
		return eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
	}

	boolean uniqueConstraintEnabled(long eventId) {
		return uniqueConstraintCache.computeIfAbsent(eventId, id -> findEvent(id).isUniqueConstraintEnabled());
	}

	/** Redis 재고 키가 없으면(Redis 재시작 등) DB 기준으로 다시 채운다. */
	void initializeRedisStock(long eventId) {
		CouponEvent event = findEvent(eventId);
		var issuedUsers = issueRepository.findUserIdsByEventId(eventId);
		redisStock.initializeIfAbsent(eventId, event.getTotalQuantity() - issuedUsers.size(), issuedUsers);
	}
}
