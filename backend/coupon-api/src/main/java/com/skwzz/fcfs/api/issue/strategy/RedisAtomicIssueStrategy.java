package com.skwzz.fcfs.api.issue.strategy;

import com.skwzz.fcfs.api.issue.CouponIssue;
import com.skwzz.fcfs.api.issue.CouponIssueRepository;
import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.issue.IssueResult;
import com.skwzz.fcfs.api.redis.RedisCouponStock;
import com.skwzz.fcfs.api.redis.RedisCouponStock.Decision;
import com.skwzz.fcfs.common.issue.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Lua 스크립트 하나로 중복 확인과 재고 차감을 원자적으로 판정하고, 통과한 요청만 DB에 저장한다.
 * 락을 잡지 않고 DB의 이벤트 행(카운터)도 건드리지 않아서 핫 로우 경합이 없다.
 * DB 저장이 실패하면 Redis를 되돌린다 (docs/03 정합성 보장 장치 F2).
 */
@Component
@RequiredArgsConstructor
class RedisAtomicIssueStrategy implements IssueStrategy {

	private final RedisCouponStock stock;
	private final CouponIssueRepository issueRepository;
	private final CouponIssuer issuer;

	@Override
	public StrategyType type() {
		return StrategyType.REDIS_ATOMIC;
	}

	@Override
	public IssueResult issue(IssueCommand command) {
		Decision decision = stock.tryIssue(command.eventId(), command.userId());
		if (decision == Decision.NOT_INITIALIZED) {
			issuer.initializeRedisStock(command.eventId());
			decision = stock.tryIssue(command.eventId(), command.userId());
		}
		return switch (decision) {
			case SOLD_OUT -> IssueResult.soldOut();
			case DUPLICATE -> IssueResult.duplicate();
			case ISSUED -> save(command);
			case NOT_INITIALIZED -> IssueResult.error("REDIS_STOCK_NOT_INITIALIZED");
		};
	}

	private IssueResult save(IssueCommand command) {
		try {
			boolean unique = issuer.uniqueConstraintEnabled(command.eventId());
			issueRepository.save(CouponIssue.of(command, type(), unique));
			return IssueResult.issued();
		} catch (DataIntegrityViolationException e) {
			// DB에는 이미 이 사용자의 발급이 있다. 재고만 돌려주고 발급 Set은 그대로 둔다
			stock.compensate(command.eventId(), command.userId(), false);
			return IssueResult.duplicate();
		} catch (RuntimeException e) {
			stock.compensate(command.eventId(), command.userId(), true);
			return IssueResult.error("DB_SAVE_FAILED");
		}
	}
}
