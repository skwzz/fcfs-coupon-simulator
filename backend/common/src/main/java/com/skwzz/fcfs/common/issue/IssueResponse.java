package com.skwzz.fcfs.common.issue;

import java.time.Instant;

/** 발급 API 응답 본문. coupon-api가 만들고 simulator가 읽는다. */
public record IssueResponse(IssueStatus result, String reason, Instant issuedAt) {
}
