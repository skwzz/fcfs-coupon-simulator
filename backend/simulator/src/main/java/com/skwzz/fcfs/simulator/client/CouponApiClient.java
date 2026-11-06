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
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * coupon-api 호출. 가상 사용자는 브라우저가 아니라 이 클라이언트로 실제 HTTP 요청을 보낸다.
 * 요청 하나가 Virtual Thread 하나를 잠깐 막는 방식이라, 사용자 수만큼 스레드를 만들어도 부담이 적다.
 */
@Component
public class CouponApiClient {

	public static final String SERVED_BY = "X-Served-By";

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

	/**
	 * 발급 요청 한 번. 예외를 던지지 않고 결과(에러 포함)로 돌려준다.
	 *
	 * @param requestId 멱등 키. 같은 클릭의 재시도는 같은 값을 보낸다
	 * @param timeout 클라이언트가 기다리는 최대 시간. 넘기면 TIMEOUT (서버는 계속 처리할 수 있다)
	 */
	public IssueOutcome issue(long eventId, long userId, StrategyType strategy, String requestId, Duration timeout)
			throws InterruptedException {
		var request = HttpRequest.newBuilder(URI.create(props.targetBaseUrl() + "/api/events/" + eventId + "/issue?strategy=" + strategy))
				.timeout(timeout)
				.header("X-User-Id", Long.toString(userId))
				.header("X-Request-Id", requestId)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			String servedBy = response.headers().firstValue(SERVED_BY).orElse(null);
			return IssueOutcome.from(response.statusCode(), parseIssue(response.body()), servedBy);
		} catch (HttpConnectTimeoutException e) {
			return IssueOutcome.clientError("CONNECT_TIMEOUT");
		} catch (HttpTimeoutException e) {
			return IssueOutcome.clientError("TIMEOUT");
		} catch (IOException e) {
			return IssueOutcome.clientError("IO_" + e.getClass().getSimpleName());
		}
	}

	/**
	 * 인스턴스 하나의 Actuator 지표 값 (예: hikaricp.connections.pending).
	 * 로드밸런서를 거치지 않고 baseUrl의 인스턴스에 직접 묻는다.
	 */
	public MetricValue metric(String baseUrl, String name) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/metrics/" + name))
				.timeout(Duration.ofSeconds(5)) // CPU가 포화된 인스턴스는 지표 응답도 느리다
				.GET()
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException(name + " → HTTP " + response.statusCode());
		}
		JsonNode measurements = json.readTree(response.body()).path("measurements");
		double value = measurements.isEmpty() ? 0 : measurements.get(0).path("value").asDouble();
		return new MetricValue(response.headers().firstValue(SERVED_BY).orElse(baseUrl), value);
	}

	/** @param servedBy 지표를 돌려준 인스턴스 이름 */
	public record MetricValue(String servedBy, double value) {
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
