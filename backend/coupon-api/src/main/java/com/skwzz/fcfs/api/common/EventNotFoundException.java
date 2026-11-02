package com.skwzz.fcfs.api.common;

public class EventNotFoundException extends RuntimeException {

	public EventNotFoundException(long eventId) {
		super("쿠폰 이벤트를 찾을 수 없습니다: " + eventId);
	}
}
