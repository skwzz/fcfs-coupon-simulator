package com.skwzz.fcfs.api.queue;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 대기열에서 내 상태. queue/me 응답이자 queue/me/stream의 이벤트 하나. docs/04-api-spec.md 3장
 *
 * @param rank 내 순번 (1부터, 내 앞에 rank - 1명)
 * @param estimatedWaitSec 순번 / 초당 입장 수
 * @param token 입장 토큰. 발급 요청에 X-Queue-Token으로 보낸다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatus(State status, Integer rank, Integer totalWaiting, Integer estimatedWaitSec, String token,
		Integer expiresInSec) {

	public enum State {
		WAITING,
		ADMITTED,
		/** 재고가 소진되어 대기열이 닫힘 */
		SOLD_OUT,
		/** 입장했지만 토큰 시간 안에 발급을 요청하지 않음. 다시 줄을 서야 한다 */
		EXPIRED,
		/** 줄을 선 적이 없거나 대기를 취소함 */
		NOT_IN_QUEUE
	}

	static QueueStatus waiting(int rank, int totalWaiting, int admitPerSecond) {
		return new QueueStatus(State.WAITING, rank, totalWaiting, (int) Math.ceil((double) rank / admitPerSecond), null,
				null);
	}

	static QueueStatus admitted(String token, long remainingMs) {
		return new QueueStatus(State.ADMITTED, null, null, null, token, (int) Math.ceil(remainingMs / 1000.0));
	}

	static QueueStatus of(State state) {
		return new QueueStatus(state, null, null, null, null, null);
	}

	/** 대기가 끝났는지 (SSE를 닫는다) */
	public boolean isFinal() {
		return status != State.WAITING;
	}

	/**
	 * SSE로 보낼 JSON. 1초에 수천 번 보내므로 Jackson을 거치지 않고 직접 만든다 (값이 숫자와 토큰뿐이라 이스케이프가 필요 없음).
	 * 필드 구성은 Jackson 직렬화(null 생략)와 같다.
	 */
	String toJson() {
		StringBuilder sb = new StringBuilder(96).append("{\"status\":\"").append(status).append('"');
		if (rank != null) {
			sb.append(",\"rank\":").append(rank).append(",\"totalWaiting\":").append(totalWaiting)
					.append(",\"estimatedWaitSec\":").append(estimatedWaitSec);
		}
		if (token != null) {
			sb.append(",\"token\":\"").append(token).append("\",\"expiresInSec\":").append(expiresInSec);
		}
		return sb.append('}').toString();
	}
}
