package com.skwzz.fcfs.api.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 입장 스케줄러. 모든 API 인스턴스에서 돌지만, 입장 수는 Lua 스크립트가 Redis 시계로 계산하므로 인스턴스 수와 상관없이
 * 합쳐서 초당 admitPerSecond명이다. 한 대가 죽어도 다른 인스턴스가 계속 입장시킨다 (리더 선출이 필요 없음).
 * 주기를 1초보다 짧게 둬서, 1초마다 한꺼번에 들어오지 않고 고르게 들어오게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AdmissionScheduler {

	private final WaitingQueue queue;

	@Scheduled(fixedDelayString = "${coupon.queue.admit-interval-ms:100}")
	void admit() {
		for (String id : queue.scheduledEventIds()) {
			try {
				if (queue.admit(Long.parseLong(id)) < 0) {
					queue.unschedule(id);
				}
			} catch (RuntimeException e) {
				log.warn("admission failed for event {}: {}", id, e.getMessage());
			}
		}
	}
}
