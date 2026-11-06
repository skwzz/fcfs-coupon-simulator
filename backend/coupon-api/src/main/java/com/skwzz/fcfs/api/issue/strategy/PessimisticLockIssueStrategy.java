package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.common.EventNotFoundException;
import com.skwzz.fcfs.api.event.CouponEvent;
import com.skwzz.fcfs.api.event.CouponEventRepository;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.common.issue.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** SELECT ... FOR UPDATE로 이벤트 행을 잠근다. 모든 요청이 행 하나에 몰려 사실상 직렬로 처리된다. */
@Component
@RequiredArgsConstructor
class PessimisticLockIssueStrategy implements IssueStrategy {

	private final CouponEventRepository eventRepository;
	private final CouponIssuer issuer;

	@Override
	public StrategyType type() {
		return StrategyType.DB_PESSIMISTIC;
	}

	@Override
	@Transactional
	public IssueResult issue(IssueCommand command) {
		CouponEvent event = eventRepository.findByIdForUpdate(command.eventId())
				.orElseThrow(() -> new EventNotFoundException(command.eventId()));
		return issuer.tryIssue(event, command, type(), event::increaseIssuedQuantity);
	}
}
