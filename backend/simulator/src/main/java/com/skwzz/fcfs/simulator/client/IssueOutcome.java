package com.skwzz.fcfs.simulator.client;

import com.skwzz.fcfs.common.issue.IssueResponse;
import com.skwzz.fcfs.common.issue.IssueStatus;

/**
 * 발급 요청 한 번의 결과.
 *
 * @param result 발급 응답의 result. 응답을 못 받았거나 발급 응답이 아니면 ERROR
 * @param reason 에러 사유 (LOCK_TIMEOUT, HTTP_500, TIMEOUT 등)
 */
public record IssueOutcome(IssueStatus result, String reason) {

	static IssueOutcome from(int httpStatus, IssueResponse body) {
		if (body == null || body.result() == null) {
			return new IssueOutcome(IssueStatus.ERROR, "HTTP_" + httpStatus);
		}
		return new IssueOutcome(body.result(), body.reason());
	}

	static IssueOutcome clientError(String reason) {
		return new IssueOutcome(IssueStatus.ERROR, reason);
	}
}
