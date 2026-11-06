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

	/**
	 * 200ms마다 집계 지표. 지연시간은 이번 구간에 끝난 요청이 없으면 null.
	 *
	 * counts는 사용자 단위(최종·현재 상태), requests·responses는 요청 단위(연타·재시도 포함)다.
	 *
	 * @param retries 누적 재시도 수
	 * @param requests 누적 요청 수
	 * @param responses 응답 결과별 누적 수 (ISSUED, SOLD_OUT, DUPLICATE, ERROR 등)
	 * @param servedBy 인스턴스별 누적 응답 수 (X-Served-By 기준)
	 * @param server 인스턴스별 최근 서버 지표 (1초마다 갱신)
	 */
	public record Tick(long t, int stockLeft, Map<UserStatus, Integer> counts, int tps, Latency latencyMs, int queueLength,
			int retries, int requests, Map<String, Long> responses, Map<String, Long> servedBy,
			Map<String, ServerSample> server) {
	}

	/**
	 * coupon-api 인스턴스 하나의 서버 측 지표. cpuPercent는 컨테이너에 할당된 CPU 기준.
	 *
	 * @param heapUsedMb 사용 중인 힙 (GC 전 쓰레기 포함). 대기열 SSE 연결이 메모리를 얼마나 쓰는지 본다
	 *        (Phase 5부터. 이전 기록은 null)
	 */
	public record ServerSample(int hikariActive, int hikariPending, double cpuPercent, Integer heapUsedMb) {

		ServerSample max(ServerSample other) {
			return new ServerSample(Math.max(hikariActive, other.hikariActive),
					Math.max(hikariPending, other.hikariPending), Math.max(cpuPercent, other.cpuPercent),
					heapUsedMb == null ? other.heapUsedMb
							: other.heapUsedMb == null ? heapUsedMb : Math.max(heapUsedMb, other.heapUsedMb));
		}
	}

	public record Latency(Double p50, Double p95, Double p99, Double max) {
	}

	/** 200ms마다 상태가 바뀐 사용자만. 원소는 [userId, status, rank, attempts] */
	public record Users(List<Object[]> changes) {
	}

	/**
	 * 실행 종료. verification은 coupon-api가 DB를 쿼리한 결과.
	 *
	 * @param serverPeak 인스턴스별 실행 중 최댓값 (커넥션 대기, CPU)
	 * @param errors 에러 사유별 응답 수 (LOCK_TIMEOUT, TIMEOUT 등)
	 */
	public record Done(RunState state, long durationMs, Map<UserStatus, Integer> counts, Verification verification,
			Latency latencyMs, int avgTps, String error, Map<String, Long> servedBy,
			Map<String, ServerSample> serverPeak, Integer requests, Integer retries, Map<String, Long> responses,
			Map<String, Long> errors, QueueStats queue) {
	}

	/**
	 * 대기열 지표 (대기열을 쓰는 실행만).
	 *
	 * @param enters 줄 서기 요청 수 (새로고침으로 다시 선 것 포함)
	 * @param polls 폴링 요청 수 (SSE 모드는 0)
	 * @param refreshes 대기 중 새로고침 수
	 * @param abandoned 기다리다 떠난 사용자 수
	 * @param admitted 입장한 사용자 수
	 * @param errors 줄 서기·폴링 요청 실패 수
	 * @param waitMs 줄 선 뒤 입장하기까지 걸린 시간
	 */
	public record QueueStats(int enters, int polls, int refreshes, int abandoned, int admitted, int errors,
			Latency waitMs, int admitPerSecond, String notifyMode) {
	}

	/** 실행 기록에 남기는 1초 단위 지표. hikariPending은 인스턴스 합계, retries는 누적 */
	public record SeriesPoint(long t, int tps, Latency latencyMs, int queueLength, Integer hikariPending,
			Integer retries) {
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
			Map<String, Long> servedBy,
			Integer requests,
			Integer retries,
			String error) {
	}

	/** 실행 비교용: 요약 + 1초 단위 시계열 */
	public record Comparison(RunSummary summary, List<SeriesPoint> series) {
	}

	/** @param attempt 그 사용자의 몇 번째 요청인지 (연타·재시도 포함, 1부터) */
	public record TimelineEntry(long t, UserStatus status, Integer rank, Integer attempt, String note) {
	}

	public record UserDetail(long userId, UserStatus status, List<TimelineEntry> timeline) {
	}
}
