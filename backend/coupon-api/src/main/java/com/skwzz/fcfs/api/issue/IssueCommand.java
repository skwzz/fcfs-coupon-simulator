package com.skwzz.fcfs.api.issue;

/**
 * 발급 요청 한 건.
 *
 * @param requestId 멱등 키 (X-Request-Id). 클라이언트가 재시도할 때 같은 값을 보낸다. 없으면 null
 */
public record IssueCommand(long eventId, long userId, String requestId) {
}
