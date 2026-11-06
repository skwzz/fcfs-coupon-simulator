package com.skwzz.fcfs.api.issue;

import com.skwzz.fcfs.api.event.CouponEvent;
import com.skwzz.fcfs.common.issue.StrategyType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long eventId;

	private Long userId;

	@Enumerated(EnumType.STRING)
	private StrategyType strategy;

	private String requestId;

	/** 0이면 UNIQUE(event_id, user_id, dup_guard)로 중복이 막힌다. 제약을 끈 이벤트는 행마다 다른 값을 넣어 우회한다. */
	private long dupGuard;

	private Instant issuedAt;

	public static CouponIssue of(CouponEvent event, IssueCommand command, StrategyType strategy) {
		return of(command, strategy, event.isUniqueConstraintEnabled());
	}

	/** 이벤트 엔티티를 읽지 않는 경로(Redis 전략)용 */
	public static CouponIssue of(IssueCommand command, StrategyType strategy, boolean uniqueConstraintEnabled) {
		CouponIssue issue = new CouponIssue();
		issue.eventId = command.eventId();
		issue.userId = command.userId();
		issue.strategy = strategy;
		issue.requestId = command.requestId();
		issue.dupGuard = uniqueConstraintEnabled ? 0 : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
		issue.issuedAt = Instant.now();
		return issue;
	}
}
