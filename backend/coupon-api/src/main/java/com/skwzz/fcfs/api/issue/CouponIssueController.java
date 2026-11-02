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

	/** strategy 파라미터를 주면 이벤트의 기본 전략 대신 그 전략으로 실행한다. */
	@PostMapping("/api/events/{eventId}/issue")
	public ResponseEntity<IssueResponse> issue(@PathVariable long eventId, @RequestHeader("X-User-Id") long userId,
			@RequestParam(required = false) StrategyType strategy) {
		IssueResult result = issueService.issue(eventId, userId, strategy);
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
