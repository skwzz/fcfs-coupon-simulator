package com.skwzz.fcfs.api.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.skwzz.fcfs.api.event.CouponEventService;
import com.skwzz.fcfs.api.event.CreateEventRequest;
import com.skwzz.fcfs.api.event.VerificationResponse;
import com.skwzz.fcfs.common.issue.IssueStatus;
import com.skwzz.fcfs.common.issue.StrategyType;
import com.skwzz.fcfs.common.redis.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 같은 쿠폰에 동시에 요청이 몰릴 때 전략별 정합성을 확인한다.
 * 실행 중인 Docker MySQL이 필요하다: ./gradlew :coupon-api:integrationTest
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CouponIssueConcurrencyTest {

	private static final int THREADS = 100;

	@Autowired
	CouponEventService eventService;

	@Autowired
	CouponIssueService issueService;

	@Autowired
	StringRedisTemplate redis;

	@Test
	@DisplayName("NAIVE: 1,000명이 재고 100개에 동시에 요청하면 초과 발급이 난다")
	void naiveOverIssues() throws InterruptedException {
		long eventId = createEvent(StrategyType.NAIVE, 100, true);

		Map<String, Long> results = runConcurrently(eventId, StrategyType.NAIVE, distinctUsers(1_000));
		VerificationResponse verification = report("NAIVE 초과 발급", eventId, results);

		assertThat(verification.overIssued()).isPositive();
		// 갱신 손실 때문에 카운터는 실제 발급 행 수보다 작게 남는다
		assertThat(verification.issuedQuantityCounter()).isLessThan((int) verification.issuedInDb());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(names = { "SYNCHRONIZED", "DB_PESSIMISTIC", "DB_OPTIMISTIC" })
	@DisplayName("락 전략: 1,000명이 재고 100개에 동시에 요청해도 정확히 100개만 발급한다")
	void lockStrategiesIssueExactlyStock(StrategyType strategy) throws InterruptedException {
		long eventId = createEvent(strategy, 100, true);

		Map<String, Long> results = runConcurrently(eventId, strategy, distinctUsers(1_000));
		VerificationResponse verification = report(strategy + " 재고 경합", eventId, results);

		assertThat(verification.passed()).isTrue();
		assertThat(verification.issuedInDb()).isEqualTo(100);
		assertThat(verification.issuedQuantityCounter()).isEqualTo(100);
		assertThat(results).containsEntry("ISSUED", 100L).containsEntry("SOLD_OUT", 900L);
	}

	@Test
	@DisplayName("NAIVE: UNIQUE 제약을 끄면 같은 사용자의 연타로 중복 발급이 난다")
	void naiveDuplicatesWithoutConstraint() throws InterruptedException {
		long eventId = createEvent(StrategyType.NAIVE, 10_000, false);

		Map<String, Long> results = runConcurrently(eventId, StrategyType.NAIVE, repeatedUsers(200, 5));
		VerificationResponse verification = report("NAIVE 연타 (제약 끔)", eventId, results);

		assertThat(verification.duplicateIssued()).isPositive();
	}

	@Test
	@DisplayName("NAIVE라도 UNIQUE 제약이 켜져 있으면 중복 발급은 DB가 막는다")
	void uniqueConstraintBlocksDuplicates() throws InterruptedException {
		long eventId = createEvent(StrategyType.NAIVE, 10_000, true);

		Map<String, Long> results = runConcurrently(eventId, StrategyType.NAIVE, repeatedUsers(200, 5));
		VerificationResponse verification = report("NAIVE 연타 (제약 켬)", eventId, results);

		assertThat(verification.duplicateIssued()).isZero();
		assertThat(verification.issuedInDb()).isEqualTo(200);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(names = { "SYNCHRONIZED", "DB_PESSIMISTIC", "DB_OPTIMISTIC" })
	@DisplayName("락 전략: UNIQUE 제약이 없어도 같은 사용자의 연타는 한 번만 발급한다")
	void lockStrategiesPreventDuplicates(StrategyType strategy) throws InterruptedException {
		long eventId = createEvent(strategy, 10_000, false);

		Map<String, Long> results = runConcurrently(eventId, strategy, repeatedUsers(200, 5));
		VerificationResponse verification = report(strategy + " 연타 (제약 끔)", eventId, results);

		assertThat(verification.passed()).isTrue();
		assertThat(verification.issuedInDb()).isEqualTo(200);
		assertThat(results).containsEntry("ISSUED", 200L).containsEntry("DUPLICATE", 800L);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(names = { "REDIS_LOCK", "REDIS_ATOMIC" })
	@DisplayName("Redis 전략: 1,000명이 재고 100개에 동시에 요청해도 정확히 100개만 발급한다")
	void redisStrategiesIssueExactlyStock(StrategyType strategy) throws InterruptedException {
		long eventId = createEvent(strategy, 100, true);

		Map<String, Long> results = runConcurrently(eventId, strategy, distinctUsers(1_000));
		VerificationResponse verification = report(strategy + " 재고 경합", eventId, results);

		assertThat(verification.passed()).isTrue();
		assertThat(verification.issuedInDb()).isEqualTo(100);
		assertThat(results).containsEntry("ISSUED", 100L).containsEntry("SOLD_OUT", 900L);
		if (strategy == StrategyType.REDIS_ATOMIC) {
			// Redis가 판정하므로 Redis 재고와 발급 Set이 DB와 맞아야 한다 (DB 카운터는 쓰지 않음)
			assertThat(verification.redisStock()).isZero();
			assertThat(verification.redisIssuedUsers()).isEqualTo(100);
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(names = { "REDIS_LOCK", "REDIS_ATOMIC" })
	@DisplayName("Redis 전략: UNIQUE 제약이 없어도 같은 사용자의 연타는 한 번만 발급한다")
	void redisStrategiesPreventDuplicates(StrategyType strategy) throws InterruptedException {
		long eventId = createEvent(strategy, 10_000, false);

		Map<String, Long> results = runConcurrently(eventId, strategy, repeatedUsers(200, 5));
		VerificationResponse verification = report(strategy + " 연타 (제약 끔)", eventId, results);

		assertThat(verification.passed()).isTrue();
		assertThat(verification.issuedInDb()).isEqualTo(200);
		assertThat(results).containsEntry("ISSUED", 200L).containsEntry("DUPLICATE", 800L);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(names = { "DB_PESSIMISTIC", "REDIS_ATOMIC" })
	@DisplayName("같은 X-Request-Id로 재시도하면 중복이 아니라 처음과 같은 성공을 돌려준다")
	void sameRequestIdIsIdempotent(StrategyType strategy) {
		long eventId = createEvent(strategy, 10, true);

		IssueResult first = issueService.issue(new IssueCommand(eventId, 1, "req-1"), strategy);
		IssueResult retry = issueService.issue(new IssueCommand(eventId, 1, "req-1"), strategy);
		IssueResult otherClick = issueService.issue(new IssueCommand(eventId, 1, "req-2"), strategy);

		assertThat(first.status()).isEqualTo(IssueStatus.ISSUED);
		assertThat(retry.status()).isEqualTo(IssueStatus.ISSUED);
		assertThat(retry.reason()).isEqualTo("IDEMPOTENT_REPLAY");
		assertThat(otherClick.status()).isEqualTo(IssueStatus.DUPLICATE);
		assertThat(eventService.verify(eventId).issuedInDb()).isEqualTo(1);
	}

	@Test
	@DisplayName("REDIS_ATOMIC: Redis 재고 키가 사라져도 DB 기준으로 다시 채우고 이어서 발급한다")
	void redisAtomicRebuildsMissingKeysFromDb() {
		long eventId = createEvent(StrategyType.REDIS_ATOMIC, 10, true);
		issueService.issue(eventId, 1, StrategyType.REDIS_ATOMIC);
		issueService.issue(eventId, 2, StrategyType.REDIS_ATOMIC);

		redis.delete(List.of(RedisKeys.stock(eventId), RedisKeys.issuedUsers(eventId))); // Redis 재시작으로 유실

		assertThat(issueService.issue(eventId, 1, StrategyType.REDIS_ATOMIC).status()).isEqualTo(IssueStatus.DUPLICATE);
		assertThat(issueService.issue(eventId, 3, StrategyType.REDIS_ATOMIC).status()).isEqualTo(IssueStatus.ISSUED);
		VerificationResponse v = eventService.verify(eventId);
		assertThat(v.issuedInDb()).isEqualTo(3);
		assertThat(v.redisStock()).isEqualTo(7);
		assertThat(v.redisIssuedUsers()).isEqualTo(3);
	}

	private long createEvent(StrategyType strategy, int stock, boolean uniqueConstraintEnabled) {
		return eventService.create(new CreateEventRequest("concurrency-" + strategy, stock, strategy, false, null,
				uniqueConstraintEnabled, null)).id();
	}

	private static List<Long> distinctUsers(int count) {
		List<Long> users = new ArrayList<>(count);
		for (long userId = 1; userId <= count; userId++) {
			users.add(userId);
		}
		return users;
	}

	/** 같은 사용자의 요청이 붙어서 제출되도록 [1,1,1,1,1,2,2,...] 순서로 만든다. */
	private static List<Long> repeatedUsers(int users, int clicksPerUser) {
		List<Long> requests = new ArrayList<>(users * clicksPerUser);
		for (long userId = 1; userId <= users; userId++) {
			for (int i = 0; i < clicksPerUser; i++) {
				requests.add(userId);
			}
		}
		return requests;
	}

	/** 모든 스레드를 출발선에 세웠다가 동시에 출발시킨다. 결과는 IssueStatus 또는 예외 클래스 이름별 건수. */
	private Map<String, Long> runConcurrently(long eventId, StrategyType strategy, List<Long> userIds)
			throws InterruptedException {
		Map<String, LongAdder> counts = new ConcurrentHashMap<>();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(userIds.size());

		try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
			for (long userId : userIds) {
				executor.submit(() -> {
					String outcome;
					try {
						start.await();
						outcome = issueService.issue(eventId, userId, strategy).status().name();
					} catch (Exception e) {
						outcome = e.getClass().getSimpleName();
					} finally {
						done.countDown();
					}
					counts.computeIfAbsent(outcome, k -> new LongAdder()).increment();
				});
			}
			start.countDown();
			assertThat(done.await(2, TimeUnit.MINUTES)).as("2분 안에 끝나야 함").isTrue();
		}

		Map<String, Long> results = new TreeMap<>();
		counts.forEach((k, v) -> results.put(k, v.sum()));
		return results;
	}

	private VerificationResponse report(String title, long eventId, Map<String, Long> results) {
		VerificationResponse v = eventService.verify(eventId);
		System.out.printf("%n[%s] 응답=%s%n  DB 발급 %d / 재고 %d, 카운터 %d, 초과 %d, 중복 %d → %s%n", title, results,
				v.issuedInDb(), v.totalQuantity(), v.issuedQuantityCounter(), v.overIssued(), v.duplicateIssued(),
				v.passed() ? "통과" : "실패");
		return v;
	}
}
