package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.IssueStatus;

/** 가상 사용자 상태. 상태 전이는 docs/04-api-spec.md 6장 */
public enum UserStatus {
	IDLE, REQUESTING, WAITING, ADMITTED, ISSUED, ACCEPTED, SOLD_OUT, DUPLICATE, ERROR, ABANDONED;

	static final UserStatus[] VALUES = values();

	boolean isFinal() {
		return this != IDLE && this != REQUESTING && this != WAITING && this != ADMITTED;
	}

	/** 발급 응답을 사용자 상태로. NOT_ADMITTED는 대기열 없이는 나오지 않으므로 에러로 본다. */
	static UserStatus of(IssueStatus result) {
		return switch (result) {
			case ISSUED -> ISSUED;
			case ACCEPTED -> ACCEPTED;
			case DUPLICATE -> DUPLICATE;
			case SOLD_OUT -> SOLD_OUT;
			case NOT_ADMITTED, ERROR -> ERROR;
		};
	}
}
