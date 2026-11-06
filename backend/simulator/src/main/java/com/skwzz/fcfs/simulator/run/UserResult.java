package com.skwzz.fcfs.simulator.run;

import java.util.List;

/**
 * 사용자 한 명의 최종 결과 (simulation_user_result 한 행).
 *
 * @param attempts 연타·재시도 포함 요청 수. IDEAL은 1 (출발 전에 중단되면 0)
 * @param firstRank 대기열 첫 순번. 대기열은 Phase 5
 */
record UserResult(
		int userId,
		UserStatus status,
		int attempts,
		Integer firstRank,
		Integer requestedAtMs,
		Integer completedAtMs,
		Integer latencyMs,
		String reason,
		List<Messages.TimelineEntry> timeline) {
}
