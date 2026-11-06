package com.skwzz.fcfs.api.queue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 순번 알림 SSE (push). 이 인스턴스에 연결된 구독자만 관리한다.
 * 주기마다 이벤트별로 구독자 순번을 Lua 한 번(1,000명 단위)으로 읽어서 보낸다.
 * 사용자마다 따로 폴링하는 것보다 Redis 왕복과 HTTP 요청이 훨씬 적다. 대신 연결을 계속 잡고 있다.
 * <p>
 * 모두에게 1초마다 보내면 1만 명 대기에서 초당 1만 건을 쓰느라 CPU를 다 써서 정작 발급이 느려졌다 (docs/06 Phase 5 결과).
 * 그래서 곧 입장할 사람은 자주, 한참 남은 사람은 드물게 보낸다 (예상 대기의 10%, 1~5초).
 * 입장·매진처럼 대기가 끝나는 상태는 바로 보낸다.
 */
@Slf4j
@Component
class QueueNotifier {

	private static final int BATCH = 1_000;
	private static final long MIN_INTERVAL_MS = 1_000;
	/** 가장 드물게 보내는 간격. 중간 프록시가 조용한 연결을 끊지 않게 하는 heartbeat 역할도 한다 */
	private static final long MAX_INTERVAL_MS = 5_000;
	private static final long EMITTER_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

	private final WaitingQueue queue;
	private final Map<Long, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

	QueueNotifier(WaitingQueue queue) {
		this.queue = queue;
	}

	private static final class Subscriber {
		final long userId;
		final SseEmitter emitter;
		QueueStatus last;
		long lastSentAt;

		Subscriber(long userId, SseEmitter emitter) {
			this.userId = userId;
			this.emitter = emitter;
		}
	}

	/** 바로 현재 상태를 한 번 보내고, 대기가 끝날 때(입장, 매진 등)까지 순번이 바뀔 때마다 보낸다. */
	SseEmitter subscribe(long eventId, long userId) {
		queue.requireEnabled(eventId);
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
		Subscriber s = new Subscriber(userId, emitter);
		Set<Subscriber> set = subscribers.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet());
		set.add(s);
		emitter.onCompletion(() -> set.remove(s));
		emitter.onTimeout(() -> set.remove(s));
		emitter.onError(e -> set.remove(s));
		push(eventId, set, List.of(s));
		return emitter;
	}

	@Scheduled(fixedDelayString = "${coupon.queue.push-interval-ms:1000}")
	void pushAll() {
		for (Long eventId : subscribers.keySet()) {
			// 비어 있으면 지운다. computeIfAbsent(subscribe)와 같은 맵 연산이라 새 구독자를 잃지 않는다
			Set<Subscriber> set = subscribers.computeIfPresent(eventId, (k, v) -> v.isEmpty() ? null : v);
			if (set != null) {
				try {
					push(eventId, set, List.copyOf(set));
				} catch (RuntimeException e) {
					log.warn("queue push failed for event {}: {}", eventId, e.getMessage());
				}
			}
		}
	}

	private void push(long eventId, Set<Subscriber> set, List<Subscriber> targets) {
		for (int from = 0; from < targets.size(); from += BATCH) {
			List<Subscriber> batch = targets.subList(from, Math.min(targets.size(), from + BATCH));
			List<QueueStatus> statuses = queue.statuses(eventId, batch.stream().map(s -> s.userId).toList());
			long now = System.currentTimeMillis();
			for (int i = 0; i < batch.size(); i++) {
				send(set, batch.get(i), statuses.get(i), now);
			}
		}
	}

	private static void send(Set<Subscriber> set, Subscriber s, QueueStatus status, long now) {
		synchronized (s) {
			if (s.last != null && !status.isFinal() && now - s.lastSentAt < interval(status)) {
				return;
			}
			try {
				s.emitter.send(SseEmitter.event().data(status.toJson(), MediaType.APPLICATION_JSON));
				s.last = status;
				s.lastSentAt = now;
				if (status.isFinal()) {
					set.remove(s);
					s.emitter.complete();
				}
			} catch (IOException | IllegalStateException e) {
				set.remove(s); // 연결이 끊김
			}
		}
	}

	/** 이 대기자에게 보내는 간격. 예상 대기 시간의 10% (1~5초) */
	private static long interval(QueueStatus status) {
		return Math.clamp(status.estimatedWaitSec() * 100L, MIN_INTERVAL_MS, MAX_INTERVAL_MS);
	}
}
