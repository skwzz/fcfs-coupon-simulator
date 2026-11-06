package com.skwzz.fcfs.simulator.client;

import com.skwzz.fcfs.common.issue.IssueResponse;
import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.simulator.SimulatorProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
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

	/** @param admitPerSecond 대기열을 쓰지 않으면 null */
	public EventInfo createEvent(String name, int totalQuantity, StrategyType strategy, boolean queueEnabled,
			Integer admitPerSecond) {
		var body = new CreateEventCommand(name, totalQuantity, strategy, queueEnabled, admitPerSecond);
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
	public IssueOutcome issue(long eventId, long userId, StrategyType strategy, String requestId, String queueToken,
			Duration timeout) throws InterruptedException {
		var builder = HttpRequest.newBuilder(URI.create(props.targetBaseUrl() + "/api/events/" + eventId + "/issue?strategy=" + strategy))
				.timeout(timeout)
				.header("X-User-Id", Long.toString(userId))
				.header("X-Request-Id", requestId);
		if (queueToken != null) {
			builder.header("X-Queue-Token", queueToken);
		}
		var request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
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

	// ---- 대기열. 발급처럼 예외 대신 결과(ERROR 포함)로 돌려준다 ----

	/** 줄 서기. 이미 서 있으면 그 자리 그대로 */
	public QueueResponse enterQueue(long eventId, long userId, Duration timeout) throws InterruptedException {
		return queueCall(queueRequest(eventId, "", userId, timeout).POST(HttpRequest.BodyPublishers.noBody()).build());
	}

	/** 폴링 한 번 */
	public QueueResponse queueStatus(long eventId, long userId, Duration timeout) throws InterruptedException {
		return queueCall(queueRequest(eventId, "/me", userId, timeout).GET().build());
	}

	/** 대기 취소 (이탈, 새로고침) */
	public void leaveQueue(long eventId, long userId, Duration timeout) throws InterruptedException {
		try {
			http.send(queueRequest(eventId, "/me", userId, timeout).DELETE().build(), HttpResponse.BodyHandlers.discarding());
		} catch (IOException e) {
			// 나가는 요청이 실패해도 서버 쪽 자리는 다음 진입 때 그대로 쓰인다
		}
	}

	/**
	 * 순번 SSE 구독. 서버가 대기가 끝날 때까지 상태를 보내고 닫는다.
	 * 연결을 맺는 데만 timeout을 적용하고, 연결된 뒤에는 이벤트가 올 때마다 읽는다.
	 */
	public QueueStream queueStream(long eventId, long userId, Duration timeout) throws InterruptedException {
		var request = queueRequest(eventId, "/me/stream", userId, timeout).header("Accept", "text/event-stream").GET().build();
		try {
			HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
			if (response.statusCode() != 200) {
				response.body().close();
				return new QueueStream(null, "HTTP_" + response.statusCode());
			}
			return new QueueStream(response.body(), null);
		} catch (HttpConnectTimeoutException e) {
			return new QueueStream(null, "CONNECT_TIMEOUT");
		} catch (HttpTimeoutException e) {
			return new QueueStream(null, "TIMEOUT");
		} catch (IOException e) {
			return new QueueStream(null, "IO_" + e.getClass().getSimpleName());
		}
	}

	/** SSE 한 연결. next()로 상태를 하나씩 읽는다 (다음 이벤트가 올 때까지 막힌다) */
	public final class QueueStream implements AutoCloseable {

		private final Stream<String> lines;
		private final Iterator<String> it;
		private final String error;

		private QueueStream(Stream<String> lines, String error) {
			this.lines = lines;
			this.it = lines == null ? null : lines.iterator();
			this.error = error;
		}

		/** 다음 상태. 연결을 못 맺었거나 끊기면 ERROR, 서버가 정상적으로 닫으면 null */
		public QueueResponse next() {
			if (error != null) {
				return QueueResponse.error(error);
			}
			try {
				while (it.hasNext()) {
					String line = it.next();
					if (line.startsWith("data:")) {
						return parseQueue(line.substring(5));
					}
				}
				return null;
			} catch (UncheckedIOException e) {
				return QueueResponse.error("STREAM_CLOSED");
			}
		}

		@Override
		public void close() {
			if (lines != null) {
				lines.close();
			}
		}
	}

	private HttpRequest.Builder queueRequest(long eventId, String path, long userId, Duration timeout) {
		return HttpRequest.newBuilder(URI.create(props.targetBaseUrl() + "/api/events/" + eventId + "/queue" + path))
				.timeout(timeout)
				.header("X-User-Id", Long.toString(userId));
	}

	private QueueResponse queueCall(HttpRequest request) throws InterruptedException {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return QueueResponse.error("HTTP_" + response.statusCode());
			}
			return parseQueue(response.body());
		} catch (HttpConnectTimeoutException e) {
			return QueueResponse.error("CONNECT_TIMEOUT");
		} catch (HttpTimeoutException e) {
			return QueueResponse.error("TIMEOUT");
		} catch (IOException e) {
			return QueueResponse.error("IO_" + e.getClass().getSimpleName());
		}
	}

	private QueueResponse parseQueue(String body) {
		try {
			return json.readValue(body, QueueResponse.class);
		} catch (JacksonException e) {
			return QueueResponse.error("BAD_RESPONSE");
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

	record CreateEventCommand(String name, int totalQuantity, StrategyType strategy, boolean queueEnabled,
			Integer admitPerSecond) {
	}
}
