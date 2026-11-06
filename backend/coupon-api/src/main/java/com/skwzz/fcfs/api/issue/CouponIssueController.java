package com.skwzz.fcfs.api.issue;

import com.skwzz.fcfs.common.issue.IssueResponse;
import com.skwzz.fcfs.common.issue.IssueStatus;
import com.skwzz.fcfs.common.issue.StrategyType;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CouponIssueController {

	private final CouponIssueService issueService;
	private final IssueBulkhead bulkhead;

	/**
	 * strategy 파라미터를 주면 이벤트의 기본 전략 대신 그 전략으로 실행한다.
	 * 대기열을 쓰는 이벤트는 입장 때 받은 토큰을 X-Queue-Token으로 보내야 한다.
	 */
	@PostMapping("/api/events/{eventId}/issue")
	public ResponseEntity<IssueResponse> issue(@PathVariable long eventId, @RequestHeader("X-User-Id") long userId,
			@RequestHeader(value = "X-Request-Id", required = false) String requestId,
			@RequestHeader(value = "X-Queue-Token", required = false) String queueToken,
			@RequestParam(required = false) StrategyType strategy) {
		if (!bulkhead.acquire()) {
			return respond(IssueResult.error("OVERLOADED"));
		}
		try {
			return respond(issueService.issue(new IssueCommand(eventId, userId, requestId), strategy, queueToken));
		} finally {
			bulkhead.release();
		}
	}

	private static ResponseEntity<IssueResponse> respond(IssueResult result) {
		Instant issuedAt = result.status() == IssueStatus.ISSUED ? Instant.now() : null;
		return ResponseEntity.status(httpStatus(result.status()))
				.body(new IssueResponse(result.status(), result.reason(), issuedAt));
	}

	private static HttpStatus httpStatus(IssueStatus status) {
		return switch (status) {
			case ISSUED -> HttpStatus.OK;
			case ACCEPTED -> HttpStatus.ACCEPTED;
			case DUPLICATE -> HttpStatus.CONFLICT;
			case SOLD_OUT -> HttpStatus.GONE;
			case NOT_ADMITTED -> HttpStatus.FORBIDDEN;
			case ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
		};
	}
}
