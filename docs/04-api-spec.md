# 04. API & 실시간 이벤트 명세

Base URL: `http://localhost:8080/api`
사용자 식별은 인증 없이 `X-User-Id` 헤더로 합니다(시뮬레이터가 생성).

## 1. 쿠폰 이벤트

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/events` | 이벤트 생성 |
| `GET` | `/events/{eventId}` | 이벤트 조회 (남은 재고 포함) |
| `POST` | `/events/{eventId}/reset` | 발급 이력, Redis 재고, 대기열 초기화 |
| `GET` | `/events/{eventId}/verification` | DB 기준 정합성 검증 결과 |

```jsonc
// POST /events
{ "name": "11월 선착순 100장", "totalQuantity": 100, "perUserLimit": 1,
  "strategy": "REDIS_ATOMIC", "queueEnabled": true, "admitPerSecond": 200,
  "uniqueConstraintEnabled": true }

// GET /events/{id}/verification
{ "totalQuantity": 100, "issuedInDb": 137, "overIssued": 37, "duplicateIssued": 12, "passed": false }
```

## 2. 발급

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/events/{eventId}/issue` | 쿠폰 발급 요청 |

요청 헤더: `X-User-Id`, (대기열 사용 시) `X-Queue-Token`

| HTTP | `result` | 의미 |
|---|---|---|
| 200 | `ISSUED` | 발급 성공 (동기 전략) |
| 202 | `ACCEPTED` | 발급 확정, DB 반영 대기 중 (`REDIS_KAFKA`) |
| 409 | `DUPLICATE` | 이미 발급받음 |
| 410 | `SOLD_OUT` | 재고 소진 |
| 403 | `NOT_ADMITTED` | 대기열 사용 이벤트인데 입장 토큰이 없거나 만료됨 |
| 503 | `ERROR` | 락 타임아웃 등 (`reason` 필드 포함) |

```json
{ "result": "SOLD_OUT", "reason": null, "issuedAt": null }
```

## 3. 대기열

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/events/{eventId}/queue` | 대기열 진입 → `{ rank, estimatedWaitSec }` |
| `GET` | `/events/{eventId}/queue/me` | 내 상태 조회 (폴링용) |
| `GET` | `/events/{eventId}/queue/me/stream` | 내 상태 SSE (push용) |
| `DELETE` | `/events/{eventId}/queue/me` | 대기 취소 (이탈) |

```jsonc
// queue/me 응답, 또는 queue/me/stream 의 각 이벤트
{ "status": "WAITING", "rank": 3214, "totalWaiting": 8120, "estimatedWaitSec": 16 }
{ "status": "ADMITTED", "token": "q_7f3a...", "expiresInSec": 30 }
{ "status": "SOLD_OUT" }
```

## 4. 시뮬레이션

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/simulations` | 시뮬레이션 시작 → `{ runId }` |
| `GET` | `/simulations` | 실행 목록 |
| `GET` | `/simulations/{runId}` | 결과 요약 |
| `POST` | `/simulations/{runId}/stop` | 중단 |
| `GET` | `/simulations/{runId}/stream` | **실시간 SSE** |
| `GET` | `/simulations/{runId}/users/{userId}` | 사용자 한 명의 타임라인 |
| `GET` | `/simulations/compare?ids=1,2,3` | 실행끼리 비교 |

```jsonc
// POST /simulations
{
  "eventId": 1,                 // 없으면 아래 설정으로 이벤트 자동 생성
  "stock": 100,
  "strategy": "DB_PESSIMISTIC",
  "queueEnabled": false,
  "userCount": 5000,
  "requestsPerUser": 1,         // 2 이상이면 연타(중복 요청) 시나리오
  "rampUpMs": 0,                // 0 = 모두 동시에 출발 (CountDownLatch)
  "queueNotifyMode": "SSE",     // SSE | POLLING
  "trackedUserLimit": 2000      // 개별 상태를 스트리밍할 최대 사용자 수
}
```

## 5. 실시간 SSE 이벤트 포맷 (`/simulations/{runId}/stream`)

사용자 1만 명의 상태 변화를 하나하나 보내면 브라우저가 감당하지 못하므로, **200ms 단위로 묶어서** 보냅니다.

### `tick`: 집계 지표 (200ms마다)
```json
{
  "t": 3200,
  "stockLeft": 12,
  "counts": { "IDLE": 0, "REQUESTING": 310, "WAITING": 4100, "ADMITTED": 40,
              "ISSUED": 88, "SOLD_OUT": 450, "DUPLICATE": 0, "ERROR": 12 },
  "tps": 1840,
  "latencyMs": { "p50": 12, "p95": 85, "p99": 230 },
  "queueLength": 4100,
  "retries": 0,
  "kafkaLag": 0
}
```

### `users`: 사용자 상태 변경분 (200ms마다, 바뀐 사용자만)
```json
{ "changes": [ [17, "WAITING", 3214], [18, "ISSUED"], [19, "SOLD_OUT"] ] }
```
배열 형식 `[userId, status, rank?]`으로 보내서 크기를 줄입니다. `trackedUserLimit`을 넘는 사용자는 집계에만 반영됩니다.

### `done`: 종료 + 최종 결과
```json
{ "durationMs": 8123, "verification": { "issuedInDb": 100, "overIssued": 0, "duplicateIssued": 0, "passed": true },
  "latencyMs": { "p50": 14, "p95": 90, "p99": 260, "max": 1203 }, "avgTps": 615 }
```

## 6. 가상 사용자 상태 머신

```
IDLE ──▶ REQUESTING ──┬──────────────────────────────▶ ISSUED / ACCEPTED
                      │                                 SOLD_OUT / DUPLICATE / ERROR
                      │ (대기열 ON)
                      └──▶ WAITING(rank↓) ──▶ ADMITTED ──▶ (위와 같음)
                               │
                               └──▶ SOLD_OUT (재고 소진으로 일괄 종료)
```
