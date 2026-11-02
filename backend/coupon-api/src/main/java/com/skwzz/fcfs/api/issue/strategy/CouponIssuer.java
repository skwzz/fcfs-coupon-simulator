package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.common.EventNotFoundException;
import com.skwzz.fcfs.api.event.CouponEvent;
import com.skwzz.fcfs.api.event.CouponEventRepository;
import com.skwzz.fcfs.api.issue.CouponIssue;
import com.skwzz.fcfs.api.issue.CouponIssueRepository;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 전략들이 공통으로 쓰는 "중복 확인 → 재고 확인 → 차감 → 발급 기록". 모두 호출하는 쪽의 트랜잭션 안에서 실행된다. */
@Component
@RequiredArgsConstructor
class CouponIssuer {

	private final CouponEventRepository eventRepository;
	private final CouponIssueRepository issueRepository;

	/** 재고 차감 방법은 전략마다 달라서 increaseStock으로 받는다. */
	IssueResult tryIssue(CouponEvent event, long userId, StrategyType strategy, Runnable increaseStock) {
		if (issueRepository.existsByEventIdAndUserId(event.getId(), userId)) {
			return IssueResult.duplicate();
		}
		if (event.isSoldOut()) {
			return IssueResult.soldOut();
		}
		increaseStock.run();
		issueRepository.save(CouponIssue.of(event, userId, strategy));
		return IssueResult.issued();
	}

	/**
	 * 락도 버전 확인도 없이 "읽은 값 + 1"을 덮어쓴다. NAIVE와 SYNCHRONIZED가 쓴다.
	 * 동시에 들어온 요청이 같은 재고를 읽고 모두 통과하기 때문에 초과 발급이 난다.
	 */
	IssueResult issueWithoutLock(long eventId, long userId, StrategyType strategy) {
		CouponEvent event = findEvent(eventId);
		int readQuantity = event.getIssuedQuantity();
		return tryIssue(event, userId, strategy,
				() -> eventRepository.overwriteIssuedQuantity(eventId, readQuantity + 1));
	}

	CouponEvent findEvent(long eventId) {
		return eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
	}
}
