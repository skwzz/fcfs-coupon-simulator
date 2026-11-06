package com.skwzz.fcfs.api.queue;

public class QueueNotEnabledException extends RuntimeException {

	public QueueNotEnabledException(long eventId) {
		super("event " + eventId + " does not use the waiting queue");
	}
}
