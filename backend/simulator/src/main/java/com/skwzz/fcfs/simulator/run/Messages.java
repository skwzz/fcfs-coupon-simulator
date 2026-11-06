package com.skwzz.fcfs.simulator.run;

import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.simulator.client.Verification;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** SSE 이벤트와 조회 API 응답 형식. docs/04-api-spec.md 4~5장 */
public final class Messages {

	private Messages() {
	}

	/** 구독 직후 한 번. 실행 설정 */
	public record Init(long runId, long eventId, StrategyType strategy, int stock, int userCount, RunState state, long t) {
	}

	/** 200ms마다 집계 지표. 지연시간은 이번 구간에 끝난 요청이 없으면 null */
	public record Tick(long t, int stockLeft, Map<UserStatus, Integer> counts, int tps, Latency latencyMs, int queueLength,
			int retries) {
	}

	public record Latency(Double p50, Double p95, Double p99, Double max) {
	}

	/** 200ms마다 상태가 바뀐 사용자만. 원소는 [userId, status] */
	public record Users(List<Object[]> changes) {
	}

	/** 실행 종료. verification은 coupon-api가 DB를 쿼리한 결과 */
	public record Done(RunState state, long durationMs, Map<UserStatus, Integer> counts, Verification verification,
			Latency latencyMs, int avgTps, String error) {
	}

	/** 실행 기록에 남기는 1초 단위 지표 */
	public record SeriesPoint(long t, int tps, Latency latencyMs, int queueLength) {
	}

	public record Started(long runId, long eventId) {
	}

	/** 실행 기록 한 건 (DB 기준). 실행 중이면 결과 항목이 null */
	public record RunSummary(
			long runId,
			long eventId,
			StrategyType strategy,
			int userCount,
			int stock,
			int rampUpMs,
			BehaviorPreset behaviorPreset,
			boolean queueEnabled,
			String targetBaseUrl,
			RunState state,
			Instant startedAt,
			Instant finishedAt,
			Long durationMs,
			Integer avgTps,
			Latency latencyMs,
			Verification verification,
			Map<UserStatus, Integer> counts,
			String error) {
	}

	/** 실행 비교용: 요약 + 1초 단위 시계열 */
	public record Comparison(RunSummary summary, List<SeriesPoint> series) {
	}

	public record TimelineEntry(long t, UserStatus status, Integer rank, String note) {
	}

	public record UserDetail(long userId, UserStatus status, List<TimelineEntry> timeline) {
	}
}
