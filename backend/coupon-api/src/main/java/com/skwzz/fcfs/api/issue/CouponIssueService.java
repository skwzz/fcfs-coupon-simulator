package com.skwzz.fcfs.api.issue;

import com.skwzz.fcfs.api.common.EventNotFoundException;
import com.skwzz.fcfs.api.common.UnsupportedStrategyException;
import com.skwzz.fcfs.api.event.CouponEvent;
import com.skwzz.fcfs.api.event.CouponEventRepository;
import com.skwzz.fcfs.api.issue.strategy.IssueStrategy;
import com.skwzz.fcfs.common.issue.IssueStatus;
import com.skwzz.fcfs.common.issue.StrategyType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 요청마다 전략을 골라 실행한다. 트랜잭션은 전략이 직접 관리하므로 여기서는 열지 않는다. */
@Service
public class CouponIssueService {

	private final Map<StrategyType, IssueStrategy> strategies = new EnumMap<>(StrategyType.class);
	private final CouponEventRepository eventRepository;
	private final CouponIssueRepository issueRepository;

	public CouponIssueService(List<IssueStrategy> strategies, CouponEventRepository eventRepository,
			CouponIssueRepository issueRepository) {
		strategies.forEach(s -> this.strategies.put(s.type(), s));
		this.eventRepository = eventRepository;
		this.issueRepository = issueRepository;
	}

	public IssueResult issue(long eventId, long userId, StrategyType override) {
		return issue(new IssueCommand(eventId, userId, null), override);
	}

	/**
	 * override가 null이면 이벤트에 설정된 기본 전략을 쓴다.
	 * 중복으로 판정됐더라도 같은 요청 id로 이미 발급된 것이면 재시도로 보고 처음과 같은 성공을 돌려준다 (멱등).
	 */
	public IssueResult issue(IssueCommand command, StrategyType override) {
		IssueResult result = execute(command, override);
		if (result.status() == IssueStatus.DUPLICATE && command.requestId() != null
				&& issueRepository.existsByEventIdAndUserIdAndRequestId(command.eventId(), command.userId(),
						command.requestId())) {
			return IssueResult.replayed();
		}
		return result;
	}

	private IssueResult execute(IssueCommand command, StrategyType override) {
		long eventId = command.eventId();
		StrategyType type = override != null ? override : eventRepository.findById(eventId)
				.map(CouponEvent::getStrategy)
				.orElseThrow(() -> new EventNotFoundException(eventId));
		IssueStrategy strategy = strategies.get(type);
		if (strategy == null) {
			throw new UnsupportedStrategyException(type);
		}
		try {
			return strategy.issue(command);
		} catch (DataIntegrityViolationException e) {
			// UNIQUE(event_id, user_id, dup_guard)가 막은 중복 발급
			return IssueResult.duplicate();
		}
	}
}
