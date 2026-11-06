package com.skwzz.fcfs.api.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.skwzz.fcfs.api.event.CouponEventService;
import com.skwzz.fcfs.api.event.CreateEventRequest;
import com.skwzz.fcfs.api.issue.CouponIssueService;
import com.skwzz.fcfs.api.issue.IssueCommand;
import com.skwzz.fcfs.api.queue.QueueStatus.State;
import com.skwzz.fcfs.common.issue.IssueStatus;
import com.skwzz.fcfs.common.issue.StrategyType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 대기열: 순번, 입장 속도, 입장 토큰, 매진 시 닫기. 입장 스케줄러가 실제로 돌면서 Redis 시계로 입장시킨다.
 * 실행 중인 Docker MySQL·Redis가 필요하다: ./gradlew :coupon-api:integrationTest
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class WaitingQueueTest {

	@Autowired
	CouponEventService eventService;

	@Autowired
	CouponIssueService issueService;

	@Autowired
	WaitingQueue queue;

	@Test
	@DisplayName("들어온 순서대로 순번을 받고, 다시 눌러도 순번이 그대로이며, 앞사람이 나가면 당겨진다")
	void ranksInArrivalOrder() {
		// 입장 속도를 1명/초로 두고 바로 확인해서, 확인하는 동안 입장으로 순번이 바뀌지 않게 한다
		long eventId = createEvent(1_000, 1);
		for (long user = 1; user <= 10; user++) {
			QueueStatus status = queue.enter(eventId, user);
			assertThat(status.status()).isEqualTo(State.WAITING);
			assertThat(status.rank()).isEqualTo((int) user);
		}

		assertThat(queue.enter(eventId, 5).rank()).as("이미 서 있으면 자리 그대로").isEqualTo(5);
		assertThat(queue.leave(eventId, 3)).isTrue();
		assertThat(queue.status(eventId, 5).rank()).as("앞사람이 나가면 한 칸 당겨진다").isEqualTo(4);
		assertThat(queue.enter(eventId, 3).rank()).as("나갔다가 다시 들어오면 맨 뒤").isEqualTo(10);
		assertThat(queue.status(eventId, 99).status()).isEqualTo(State.NOT_IN_QUEUE);
	}

	@Test
	@DisplayName("초당 입장 수만큼만 입장시킨다 (API 인스턴스가 몇 대든 Redis 시계 기준)")
	void admitsAtConfiguredRate() throws InterruptedException {
		long eventId = createEvent(1_000, 100);
		List<Long> users = LongStream.rangeClosed(1, 500).boxed().toList();
		long start = System.currentTimeMillis(); // 첫 사람이 줄을 선 시각부터 입장이 시작된다
		users.forEach(u -> queue.enter(eventId, u));

		Thread.sleep(2_000);
		Map<State, Long> counts = counts(eventId, users);
		long expected = (System.currentTimeMillis() - start) * 100 / 1000;

		System.out.println("입장 속도 100명/초, 기대 " + expected + "명: " + counts);
		assertThat(counts.get(State.ADMITTED)).isBetween(expected * 85 / 100, expected + 5);
		assertThat(counts.get(State.WAITING)).isEqualTo(500 - counts.get(State.ADMITTED));
	}

	@Test
	@DisplayName("입장 토큰이 없거나 다른 사람 것이면 NOT_ADMITTED, 내 토큰이면 발급한다")
	void requiresAdmissionToken() throws InterruptedException {
		long eventId = createEvent(1_000, 100);
		queue.enter(eventId, 1);
		queue.enter(eventId, 2);
		QueueStatus admitted = awaitAdmitted(eventId, 1);

		assertThat(issue(eventId, 1, null)).isEqualTo(IssueStatus.NOT_ADMITTED);
		assertThat(issue(eventId, 1, "forged")).isEqualTo(IssueStatus.NOT_ADMITTED);
		assertThat(issue(eventId, 2, admitted.token())).as("다른 사용자 토큰").isEqualTo(IssueStatus.NOT_ADMITTED);
		assertThat(issue(eventId, 1, admitted.token())).isEqualTo(IssueStatus.ISSUED);
		assertThat(issue(eventId, 3, null)).as("줄을 서지 않은 사용자").isEqualTo(IssueStatus.NOT_ADMITTED);
	}

	@Test
	@DisplayName("재고가 소진되면 대기열을 닫고, 기다리던 사용자는 SOLD_OUT을 받는다")
	void closesQueueWhenSoldOut() throws InterruptedException {
		long eventId = createEvent(5, 10);
		List<Long> users = LongStream.rangeClosed(1, 50).boxed().toList();
		users.forEach(u -> queue.enter(eventId, u));

		int issued = 0;
		boolean soldOut = false;
		for (long user = 1; !soldOut; user++) {
			IssueStatus result = issue(eventId, user, awaitAdmitted(eventId, user).token());
			issued += result == IssueStatus.ISSUED ? 1 : 0;
			soldOut = result == IssueStatus.SOLD_OUT;
		}

		Map<State, Long> counts = counts(eventId, users);
		System.out.println("재고 5, 매진 뒤: " + counts);
		assertThat(issued).isEqualTo(5);
		assertThat(counts.get(State.WAITING)).isZero();
		assertThat(counts.get(State.SOLD_OUT)).isPositive();
		assertThat(queue.enter(eventId, 1_000).status()).as("닫힌 뒤 새로 온 사용자").isEqualTo(State.SOLD_OUT);
	}

	private long createEvent(int stock, int admitPerSecond) {
		return eventService.create(new CreateEventRequest("queue test", stock, StrategyType.REDIS_ATOMIC, true,
				admitPerSecond, true, null)).id();
	}

	private IssueStatus issue(long eventId, long userId, String token) {
		return issueService.issue(new IssueCommand(eventId, userId, null), null, token).status();
	}

	private QueueStatus awaitAdmitted(long eventId, long userId) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			QueueStatus status = queue.status(eventId, userId);
			if (status.status() == State.ADMITTED) {
				return status;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("user " + userId + " was not admitted in 10s");
	}

	private Map<State, Long> counts(long eventId, List<Long> users) {
		Map<State, Long> counts = queue.statuses(eventId, users).stream()
				.collect(Collectors.groupingBy(QueueStatus::status, Collectors.counting()));
		for (State s : State.values()) {
			counts.putIfAbsent(s, 0L);
		}
		return counts;
	}
}
