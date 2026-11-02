package com.skwzz.fcfs.api.issue;

import com.skwzz.fcfs.common.issue.IssueStatus;

public record IssueResult(IssueStatus status, String reason) {

	public static IssueResult issued() {
		return new IssueResult(IssueStatus.ISSUED, null);
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
