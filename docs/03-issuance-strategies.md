# 03. 발급 전략

모든 전략은 하나의 인터페이스로 구현하고, 이벤트 설정이나 요청 파라미터로 바꿔 끼웁니다.

```java
public interface IssueStrategy {
    StrategyType type();
    IssueResult issue(long eventId, long userId);   // ISSUED | SOLD_OUT | DUPLICATE | ACCEPTED(비동기) | ERROR
}
```

## 전략 목록

| # | 전략 | 핵심 아이디어 | 예상 결과 |
|---|---|---|---|
| 1 | `NAIVE` | 조회 → 확인 → 증가, 락 없음 | ❌ 초과 발급, 중복 발급 발생 (반면교사용) |
| 2 | `SYNCHRONIZED` | Java `synchronized` | ✅ 단일 인스턴스에서만 정합성 보장. 인스턴스를 2개 띄우면 깨짐 |
| 3 | `DB_PESSIMISTIC` | `SELECT ... FOR UPDATE` | ✅ 정합성 보장. 행 락 경합으로 TPS 낮음 |
| 4 | `DB_OPTIMISTIC` | `@Version` + 재시도 | ✅ 정합성 보장. 경합이 심하면 재시도가 폭증해 오히려 느림 |
| 5 | `REDIS_LOCK` | Redisson 분산 락 안에서 DB 처리 | ✅ 정합성 보장. 다중 인스턴스 OK. 락 대기가 병목 |
| 6 | `REDIS_ATOMIC` | Lua 스크립트로 중복 체크와 재고 차감을 원자적으로, DB 저장은 동기 | ✅ 정합성 보장. 빠름 |
| 7 | `REDIS_KAFKA` | Redis에서 원자적으로 판정, Kafka로 넘기고 바로 응답, Consumer가 DB 저장 | ✅ 판정은 정확. 응답 가장 빠름. DB 반영은 늦음(최종 일관성) |

그리고 어느 전략에든 **대기열(Waiting Queue)** 을 앞단에 붙일 수 있습니다(`queueEnabled`).

---

## 1. NAIVE

```java
CouponEvent e = repo.findById(eventId);
if (issueRepo.existsByEventIdAndUserId(eventId, userId)) return DUPLICATE;
if (e.getIssuedQuantity() >= e.getTotalQuantity()) return SOLD_OUT;
e.increase();                       // issued_quantity + 1
issueRepo.save(new CouponIssue(eventId, userId));
```

여러 스레드가 동시에 "재고 있음"을 읽고 모두 통과합니다(Race condition, Lost update).
**이 전략에서 초과 발급이 실제로 나는 걸 보여주는 것이 프로젝트의 출발점입니다.**

## 2. SYNCHRONIZED

`synchronized` 메서드로 감쌉니다. 주의할 점이 있습니다. `@Transactional`과 같이 쓰면 **락이 풀린 다음에 커밋**되기 때문에 여전히 깨질 수 있습니다.
트랜잭션 바깥에서 락을 잡아야 합니다. 이 함정을 직접 보여주는 것도 학습 포인트입니다.

## 3. DB_PESSIMISTIC

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select e from CouponEvent e where e.id = :id")
Optional<CouponEvent> findByIdForUpdate(long id);
```

모든 요청이 이벤트 행 하나에 몰리기 때문에 사실상 직렬 처리됩니다. 커넥션 풀 고갈과 락 대기 타임아웃을 관찰할 수 있습니다.

## 4. DB_OPTIMISTIC

`@Version` 충돌(`ObjectOptimisticLockingFailureException`)이 나면 재시도합니다(최대 N회, 백오프).
지표에 **재시도 횟수**를 따로 집계해서, 경합이 심할 때 낙관적 락이 왜 불리한지 보여줍니다.

## 5. REDIS_LOCK

```java
RLock lock = redisson.getLock("lock:coupon:" + eventId);
if (!lock.tryLock(waitMs, leaseMs, MILLISECONDS)) return ERROR(LOCK_TIMEOUT);
try { /* NAIVE와 같은 로직 (트랜잭션은 락 안에서 시작하고 끝냄) */ }
finally { lock.unlock(); }
```

## 6. REDIS_ATOMIC (Lua)

```lua
-- KEYS[1] = coupon:{id}:issued, KEYS[2] = coupon:{id}:stock, ARGV[1] = userId
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end  -- DUPLICATE
local stock = tonumber(redis.call('GET', KEYS[2]))
if stock <= 0 then return 0 end                                        -- SOLD_OUT
redis.call('DECR', KEYS[2])
redis.call('SADD', KEYS[1], ARGV[1])
return 1                                                               -- OK
```

판정에 통과하면 DB에 동기로 저장합니다. DB 저장이 실패하면 Redis를 보상 처리(`INCR` + `SREM`)합니다.

## 7. REDIS_KAFKA

1. Lua로 판정 (6번과 같음)
2. 통과하면 `coupon-issue` 토픽으로 produce하고 바로 `ACCEPTED` 응답
3. Consumer가 배치로 DB insert (멱등)

지표: API 응답 지연(매우 낮음), **Consumer lag**, **DB 반영 완료까지 걸린 시간**.
"사용자는 성공을 받았는데 DB에는 아직 없다"는 구간을 대시보드에서 볼 수 있게 합니다.

---

## 대기열 (Waiting Queue)

발급 전략과 별개로 앞단에 붙는 **유량 제어** 계층입니다. 정합성이 아니라 **서버 보호와 사용자 경험**을 위한 것입니다.

```
[사용자] ──enter──▶ ZADD queue:{id}:waiting  (score = now)
   │                       │
   │◀── 순번(ZRANK), 예상 대기 = 순번 / admitPerSecond
   │
   │     [Admission Scheduler: 1초마다]
   │       ZPOPMIN waiting K개 → ZADD active (score = 만료시각)
   │       → 해당 사용자에게 입장 토큰 발급 (SSE로 통지)
   │
   └──issue(token)──▶ active 검증 → IssueStrategy 호출 → active에서 제거
```

- 순번 알림은 **SSE push**를 기본으로 하고, 폴링 방식(`GET /rank`)도 같이 제공해서 비교할 수 있게 합니다.
- 재고가 소진되면 대기 중인 사용자 전원에게 `SOLD_OUT`을 일괄 통지하고 대기열을 비웁니다. 의미 없는 대기를 막기 위해서입니다.
- 입장 토큰은 TTL(예: 30초)이 있고, 만료되면 active에서 제거합니다.
