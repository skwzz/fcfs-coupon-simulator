# 02. 아키텍처

## 1. 핵심 설계 결정: "부하는 서버가 만들고, 브라우저는 지켜본다"

브라우저에서 사용자 10,000명의 요청을 직접 보내는 방식은 쓸 수 없습니다.

- 브라우저는 도메인당 동시 연결이 HTTP/1.1 기준 6개 정도로 제한되어 있어서 요청이 **브라우저 안에서 줄을 섭니다**. 서버에서 일어나는 경쟁을 재현할 수 없습니다.
- 수천 개의 Promise와 렌더링 때문에 탭이 먼저 멈춥니다.

그래서 역할을 이렇게 나눕니다.

| 구성요소 | 역할 |
|---|---|
| **Simulator** (Spring, 백엔드) | 가상 사용자 N명을 Virtual Thread로 띄워서 **실제 HTTP로** 발급 API를 호출. 각 사용자의 상태 변화를 수집 |
| **Coupon API** (Spring, 백엔드) | 실제 발급 로직. 시뮬레이터가 보내는 요청이든 사람이 보내는 요청이든 똑같이 처리 |
| **Dashboard** (React) | 시뮬레이션 설정과 실행, 집계 지표와 사용자 상태를 SSE로 받아서 표시 |

추가로 프론트엔드에 **"내가 직접 참여" 모드**를 둡니다. 시뮬레이션이 도는 중에 브라우저 사용자 한 명이 실제로 발급 버튼을 눌러서
대기열 팝업("현재 대기 순번 3,214번")을 직접 겪어보는 기능입니다. 데모로 보여주기에 가장 좋은 부분입니다.

## 2. 전체 구성도

```
┌──────────────────────────── React Dashboard ────────────────────────────┐
│  시뮬레이션 설정 / 실시간 차트 / 사용자 그리드 / "직접 참여" 모드     │
└───────────┬───────────────────────────────▲──────────────────────────────┘
            │ REST (실행, 조회)             │ SSE (집계 지표, 사용자 상태)
┌───────────▼───────────────────────────────┴──────────────────────────────┐
│                        Spring Boot (backend)                             │
│  ┌──────────────┐ HTTP  ┌──────────────┐   ┌──────────────────────────┐  │
│  │  Simulator   │──────▶│  Coupon API  │──▶│ IssueStrategy (교체 가능) │  │
│  │ (가상 사용자) │       │  + 대기열    │   └─────┬──────────┬─────────┘  │
│  └──────┬───────┘       └──────────────┘         │          │            │
│         │ 상태 이벤트                            │          │            │
│  ┌──────▼───────┐                                │          │            │
│  │ Metrics Hub  │── 200ms마다 집계 → SSE         │          │            │
│  └──────────────┘                                │          │            │
│  ┌──────────────────────┐                        │          │            │
│  │ Kafka Consumer       │◀── coupon-issue ───────┼──┐       │            │
│  │ (비동기 전략 DB 반영) │                        │  │       │            │
│  └──────────┬───────────┘                        │  │       │            │
└─────────────┼────────────────────────────────────┼──┼───────┼────────────┘
              ▼                                    ▼  │       ▼
          ┌───────┐                          ┌───────┐│  ┌─────────┐
          │ MySQL │◀─────────────────────────│ Redis ││  │  Kafka  │
          └───────┘                          └───────┘└─▶└─────────┘
```

## 3. 저장소 구조 (모노레포)

```
fcfs-coupon-simulator/
├── backend/                 # Spring Boot (Gradle)
│   └── src/main/java/.../
│       ├── coupon/          # 이벤트, 발급, IssueStrategy 구현체들
│       ├── queue/           # 대기열 (Redis ZSET), 입장 스케줄러, 입장 토큰
│       ├── simulation/      # 시뮬레이션 실행기, 가상 사용자
│       ├── metrics/         # 실시간 집계, SSE 브로드캐스트
│       └── common/
├── frontend/                # React + Vite + TS
├── infra/
│   └── docker-compose.yml   # mysql, redis, kafka, (선택) prometheus, grafana
├── loadtest/                # (선택) k6 스크립트: 외부 부하로 교차 검증
└── docs/
```

시뮬레이터와 API를 처음에는 **같은 Spring 애플리케이션**에 둡니다(구현이 단순함).
나중에 측정 정확도가 중요해지면 `simulator`를 별도 프로세스로 분리하거나, k6로 교차 검증합니다.

## 4. 도메인 모델

```
CouponEvent                         CouponIssue
─────────────────────────           ─────────────────────────
id            PK                    id          PK
name                                event_id    FK
total_quantity                      user_id
issued_quantity  (DB 전략용)        issued_at
version          (낙관적 락용)      strategy
per_user_limit                      UNIQUE(event_id, user_id)  ← 최후 방어선
queue_enabled
admit_per_second
start_at

SimulationRun                       SimulationUserResult (선택: 상세 저장 시)
─────────────────────────           ─────────────────────────
id            PK                    run_id, user_id
event_id                            final_status, first_rank
strategy, user_count, stock         requested_at, completed_at, latency_ms
ramp_up_ms, requests_per_user
status (RUNNING/DONE/FAILED)
started_at, finished_at
result_json   (TPS, p50/95/99, 결과별 건수, 초과/중복 발급 수)
```

> `UNIQUE(event_id, user_id)` 제약은 일부러 **옵션으로 끌 수 있게** 만듭니다.
> 제약이 켜져 있으면 중복 발급이 DB 예외로 가려져서, "락 없는 구현"이 어떻게 깨지는지 보이지 않기 때문입니다.

## 5. Redis 키 설계

| 키 | 타입 | 용도 |
|---|---|---|
| `coupon:{eventId}:stock` | String(int) | 남은 재고 (DECR) |
| `coupon:{eventId}:issued` | Set | 발급받은 userId (중복 방지) |
| `queue:{eventId}:waiting` | Sorted Set | 대기열, score = 진입 시각(ns) |
| `queue:{eventId}:active` | Sorted Set | 입장한 사용자, score = 토큰 만료 시각 |
| `lock:coupon:{eventId}` | (Redisson) | 분산 락 전략용 |

## 6. Kafka

| 토픽 | 키 | 값 | 비고 |
|---|---|---|---|
| `coupon-issue` | eventId | `{eventId, userId, requestedAt}` | 파티션 3개. 같은 이벤트는 같은 파티션으로 가서 순서가 보장됨 |
| `coupon-issue.DLT` | | 실패 메시지 | 재시도를 다 쓴 메시지 |

Consumer는 배치로 insert하고, 중복 메시지는 `UNIQUE` 제약 또는 `INSERT IGNORE`로 멱등 처리합니다.

## 7. 인프라 (docker-compose)

| 서비스 | 이미지 | 포트 |
|---|---|---|
| mysql | `mysql:8.4` | 3306 |
| redis | `redis:7.4` | 6379 |
| kafka | `apache/kafka:3.8.0` (KRaft, 단일 브로커) | 9092 |
| kafka-ui (선택) | `provectuslabs/kafka-ui` | 8085 |
| prometheus / grafana (선택) | | 9090 / 3000 |

백엔드와 프론트엔드는 개발 중에는 로컬에서 직접 실행하고(`./gradlew bootRun`, `npm run dev`), 인프라만 Docker로 띄웁니다.

## 8. 측정 방식

- **지연시간**: 가상 사용자가 요청 직전/응답 직후 `System.nanoTime()`으로 측정하고, HdrHistogram으로 p50/p95/p99 계산
- **TPS**: 1초 단위 완료 건수
- **정합성 판정** (실행 종료 후 DB 쿼리):
  - 초과 발급 = `count(coupon_issue where event_id=?) - total_quantity` (0보다 크면 실패)
  - 중복 발급 = `count(*) - count(distinct user_id)`
- **비동기 전략**: "API 응답 완료"와 "DB 반영 완료" 시점을 따로 기록하고, 그 차이(= 반영 지연)를 함께 표시
