package com.skwzz.fcfs.api.event;

import com.skwzz.fcfs.api.common.EventNotFoundException;
import com.skwzz.fcfs.api.issue.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponEventService {

	private final CouponEventRepository eventRepository;
	private final CouponIssueRepository issueRepository;

	@Transactional
	public EventResponse create(CreateEventRequest request) {
		return EventResponse.from(eventRepository.save(request.toEntity()));
	}

	@Transactional(readOnly = true)
	public EventResponse get(long eventId) {
		return EventResponse.from(find(eventId));
	}

	/** 같은 조건으로 다시 실행할 수 있게 발급 이력과 카운터를 비운다. (Redis 재고, 대기열 초기화는 해당 Phase에서 추가) */
	@Transactional
	public EventResponse reset(long eventId) {
		CouponEvent event = find(eventId);
		issueRepository.deleteByEventId(eventId);
		event.resetIssuedQuantity();
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
				duplicateIssued, overIssued == 0 && duplicateIssued == 0);
	}

	private CouponEvent find(long eventId) {
		return eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
	}
}
