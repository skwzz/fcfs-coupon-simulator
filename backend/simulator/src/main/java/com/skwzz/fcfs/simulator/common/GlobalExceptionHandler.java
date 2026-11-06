package com.skwzz.fcfs.simulator.common;

import com.skwzz.fcfs.simulator.client.CouponApiException;
import com.skwzz.fcfs.simulator.run.RunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail handleBadRequest(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	ProblemDetail handleConflict(IllegalStateException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(RunNotFoundException.class)
	ProblemDetail handleNotFound(RunNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/** 이벤트 생성·초기화처럼 시작에 필요한 coupon-api 호출이 실패함 */
	@ExceptionHandler(CouponApiException.class)
	ProblemDetail handleCouponApi(CouponApiException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
	}
}
