package com.skwzz.fcfs.api.event;

import com.skwzz.fcfs.api.common.EventNotFoundException;
import com.skwzz.fcfs.api.issue.CouponIssueRepository;
import com.skwzz.fcfs.api.redis.RedisCouponStock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponEventService {

	private final CouponEventRepository eventRepository;
	private final CouponIssueRepository issueRepository;
	private final RedisCouponStock redisStock;

	/** 어떤 전략으로든 요청할 수 있으므로 Redis 재고도 같이 만든다. */
	@Transactional
	public EventResponse create(CreateEventRequest request) {
		CouponEvent event = eventRepository.save(request.toEntity());
		redisStock.reset(event.getId(), event.getTotalQuantity());
		return EventResponse.from(event);
	}

	@Transactional(readOnly = true)
	public EventResponse get(long eventId) {
		return EventResponse.from(find(eventId));
	}

	/** 같은 조건으로 다시 실행할 수 있게 발급 이력, 카운터, Redis 재고를 비운다. (대기열 초기화는 Phase 5) */
	@Transactional
	public EventResponse reset(long eventId) {
		CouponEvent event = find(eventId);
		issueRepository.deleteByEventId(eventId);
		event.resetIssuedQuantity();
		redisStock.reset(eventId, event.getTotalQuantity());
		return EventResponse.from(event);
	}

	@Transactional(readOnly = true)
	public VerificationResponse verify(long eventId) {
		CouponEvent event = find(eventId);
		long issued = issueRepository.countByEventId(eventId);
		long distinctUsers = issueRepository.countDistinctUserByEventId(eventId);
		long overIssued = Math.max(0, issued - event.getTotalQuantity());
		long duplicateIssued = issued - distinctUsers;
		return new VerificationResponse(event.getTotalQuantity(), issued, event.getIssuedQuantity(), overIssued,
				duplicateIssued, overIssued == 0 && duplicateIssued == 0, redisStock.stock(eventId),
				redisStock.issuedUserCount(eventId));
	}

	private CouponEvent find(long eventId) {
		return eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
	}
}
