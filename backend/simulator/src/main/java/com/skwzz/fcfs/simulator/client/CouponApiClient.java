package com.skwzz.fcfs.simulator.client;

import com.skwzz.fcfs.common.issue.IssueResponse;
import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.simulator.SimulatorProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * coupon-api 호출. 가상 사용자는 브라우저가 아니라 이 클라이언트로 실제 HTTP 요청을 보낸다.
 * 요청 하나가 Virtual Thread 하나를 잠깐 막는 방식이라, 사용자 수만큼 스레드를 만들어도 부담이 적다.
 */
@Component
public class CouponApiClient {

	private final HttpClient http;
	private final JsonMapper json;
	private final SimulatorProperties props;

	public CouponApiClient(JsonMapper json, SimulatorProperties props) {
		this.json = json;
		this.props = props;
		this.http = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(props.connectTimeout())
				.executor(Executors.newVirtualThreadPerTaskExecutor())
				.build();
	}

	public EventInfo createEvent(String name, int totalQuantity, StrategyType strategy) {
		var body = new CreateEventCommand(name, totalQuantity, strategy);
		return send(post("/api/events", json.writeValueAsString(body)), EventInfo.class);
	}

	public EventInfo resetEvent(long eventId) {
		return send(post("/api/events/" + eventId + "/reset", ""), EventInfo.class);
	}

	public Verification verify(long eventId) {
		var request = request("/api/events/" + eventId + "/verification").GET().build();
		return send(request, Verification.class);
	}

	/** 발급 요청 한 번. 예외를 던지지 않고 결과(에러 포함)로 돌려준다. */
	public IssueOutcome issue(long eventId, long userId, StrategyType strategy) throws InterruptedException {
		var request = request("/api/events/" + eventId + "/issue?strategy=" + strategy)
				.header("X-User-Id", Long.toString(userId))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return IssueOutcome.from(response.statusCode(), parseIssue(response.body()));
		} catch (HttpConnectTimeoutException e) {
			return IssueOutcome.clientError("CONNECT_TIMEOUT");
		} catch (HttpTimeoutException e) {
			return IssueOutcome.clientError("TIMEOUT");
		} catch (IOException e) {
			return IssueOutcome.clientError("IO_" + e.getClass().getSimpleName());
		}
	}

	private IssueResponse parseIssue(String body) {
		try {
			return json.readValue(body, IssueResponse.class);
		} catch (JacksonException e) {
			return null; // ProblemDetail 등 발급 응답이 아닌 본문
		}
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(props.targetBaseUrl() + path)).timeout(props.requestTimeout());
	}

	private HttpRequest post(String path, String body) {
		return request(path)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
	}

	private <T> T send(HttpRequest request, Class<T> type) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300) {
				throw new CouponApiException(request.uri() + " → HTTP " + response.statusCode() + " " + response.body());
			}
			return json.readValue(response.body(), type);
		} catch (IOException e) {
			throw new CouponApiException("coupon-api에 연결할 수 없습니다 (" + props.targetBaseUrl() + "): " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CouponApiException("coupon-api 호출이 중단되었습니다");
		}
	}

	record CreateEventCommand(String name, int totalQuantity, StrategyType strategy) {
	}
}
