package com.skwzz.fcfs.simulator.client;

import com.skwzz.fcfs.common.issue.IssueResponse;
import com.skwzz.fcfs.common.issue.IssueStatus;

/**
 * 발급 요청 한 번의 결과.
 *
 * @param result 발급 응답의 result. 응답을 못 받았거나 발급 응답이 아니면 ERROR
 * @param reason 에러 사유 (LOCK_TIMEOUT, HTTP_500, TIMEOUT 등)
 * @param servedBy 응답한 coupon-api 인스턴스 (X-Served-By). 응답을 못 받았으면 null
 */
public record IssueOutcome(IssueStatus result, String reason, String servedBy) {

	static IssueOutcome from(int httpStatus, IssueResponse body, String servedBy) {
		if (body == null || body.result() == null) {
			return new IssueOutcome(IssueStatus.ERROR, "HTTP_" + httpStatus, servedBy);
		}
		return new IssueOutcome(body.result(), body.reason(), servedBy);
	}

	static IssueOutcome clientError(String reason) {
		return new IssueOutcome(IssueStatus.ERROR, reason, null);
	}
}
