package com.skwzz.fcfs.api.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 API. 사용자는 X-User-Id 헤더로 구분한다.
 * 브라우저 EventSource는 헤더를 붙일 수 없어서 조회와 SSE는 ?userId= 도 받는다 (실무라면 로그인 세션·쿠키로 구분).
 */
@RestController
@RequestMapping("/api/events/{eventId}/queue")
@RequiredArgsConstructor
public class QueueController {

	private final WaitingQueue queue;
	private final QueueNotifier notifier;

	@PostMapping
	public QueueStatus enter(@PathVariable long eventId, @RequestHeader("X-User-Id") long userId) {
		return queue.enter(eventId, userId);
	}

	/** 폴링용 */
	@GetMapping("/me")
	public QueueStatus me(@PathVariable long eventId, @RequestHeader(value = "X-User-Id", required = false) Long header,
			@RequestParam(required = false) Long userId) {
		return queue.status(eventId, userId(header, userId));
	}

	/** push용. 대기가 끝나면(입장, 매진 등) 마지막 상태를 보내고 닫는다 */
	@GetMapping(path = "/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> stream(@PathVariable long eventId,
			@RequestHeader(value = "X-User-Id", required = false) Long header,
			@RequestParam(required = false) Long userId) {
		SseEmitter emitter = notifier.subscribe(eventId, userId(header, userId));
		// nginx가 응답을 모았다가 보내지 않게 (SSE는 바로바로 흘려보내야 한다)
		return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
	}

	/** 대기 취소 (이탈). 새로고침은 취소 후 다시 진입이라 순번이 뒤로 밀린다 */
	@DeleteMapping("/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void leave(@PathVariable long eventId, @RequestHeader(value = "X-User-Id", required = false) Long header,
			@RequestParam(required = false) Long userId) {
		queue.leave(eventId, userId(header, userId));
	}

	private static long userId(Long header, Long param) {
		if (header != null) {
			return header;
		}
		if (param != null) {
			return param;
		}
		throw new IllegalArgumentException("X-User-Id 헤더나 userId 파라미터가 필요합니다");
	}
}
