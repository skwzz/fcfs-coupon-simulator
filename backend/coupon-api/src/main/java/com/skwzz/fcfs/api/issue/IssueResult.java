package com.skwzz.fcfs.api.issue;

import com.skwzz.fcfs.common.issue.IssueStatus;

public record IssueResult(IssueStatus status, String reason) {

	public static IssueResult issued() {
		return new IssueResult(IssueStatus.ISSUED, null);
	}

	/** 같은 X-Request-Id로 이미 발급된 요청의 재시도. 처음 응답과 같은 ISSUED를 돌려준다 */
	public static IssueResult replayed() {
		return new IssueResult(IssueStatus.ISSUED, "IDEMPOTENT_REPLAY");
	}

	public static IssueResult duplicate() {
		return new IssueResult(IssueStatus.DUPLICATE, null);
	}

	public static IssueResult soldOut() {
		return new IssueResult(IssueStatus.SOLD_OUT, null);
	}

	public static IssueResult error(String reason) {
		return new IssueResult(IssueStatus.ERROR, reason);
	}
}
