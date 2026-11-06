package com.skwzz.fcfs.simulator.run;

public class RunNotFoundException extends RuntimeException {

	public RunNotFoundException(String what) {
		super(what + "을(를) 찾을 수 없습니다");
	}
}
