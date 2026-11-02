# 07. 확장 백로그

[로드맵](06-roadmap.md)에는 넣지 않았지만, 실무에 더 가깝게 만들고 싶을 때 하나씩 골라서 추가할 항목입니다.
항목마다 **왜 필요한지, 무엇을 만드는지, 언제 끝난 것으로 보는지**를 적어 두었습니다. 시작할 때 이 문서의 항목을 로드맵 Phase로 옮기면 됩니다.

| 상태 표시 | 의미 |
|---|---|
| ⬜ | 아직 안 함 |
| 🔨 | 진행 중 (로드맵으로 옮김) |
| ✅ | 완료 |

## 한눈에 보기

| ID | 항목 | 분류 | 난이도 | 먼저 필요한 것 |
|---|---|---|---|---|
| B1 | 서명된 입장 토큰 + 대기열 분리 | 보안·구조 | 중 | Phase 5 |
| B2 | 요청 횟수 제한 (rate limit) | 보안 | 하 | Phase 3 |
| B3 | 가짜 로그인 + JWT 사용자 식별 | 보안 | 하 | Phase 2 |
| B4 | Prometheus + Grafana 모니터링 | 관측 | 중 | Phase 3 |
| B5 | 분산 추적 (OpenTelemetry) | 관측 | 중 | B4 |
| B6 | 장애 주입 시나리오 | 장애 대응 | 중 | Phase 6 |
| B7 | Redis 고가용성 (Sentinel / Cluster) | 인프라 | 상 | Phase 4 |
| B8 | 핫 키 분산 (재고 버킷 샤딩) | 성능 | 상 | Phase 4 |
| B9 | 이벤트 오픈 준비 (재고 미리 적재, 오픈 시각 차단) | 운영 | 하 | Phase 4 |
| B10 | Testcontainers 통합 테스트 | 테스트 | 하 | Phase 1 |
| B11 | 부하 테스트 방법론 (k6, 별도 장비, 반복 측정) | 측정 | 중 | Phase 3 |
| B12 | Transactional Outbox 전략 | 정합성 | 상 | Phase 6 |
| B13 | 쿠폰 도메인 확장 (정책·유효기간·사용 처리) | 도메인 | 중 | Phase 1 |

---

## B1. 서명된 입장 토큰 + 대기열 분리 ⬜

- **왜**: 지금 설계는 대기열과 발급 API가 같은 앱에 있고, 입장 여부를 Redis에서 조회합니다. 실무에서는 대기열이 별도 서비스나 게이트웨이에 있고, 입장 토큰을 **서버가 서명**해서 발급 API가 Redis 조회 없이 검증합니다. 토큰을 위조하거나 다른 사람 토큰을 재사용하는 것도 막습니다.
- **무엇을**
  - `queue-service` 모듈 분리 (대기열 진입, 순번 조회, 입장 스케줄러)
  - 입장 토큰을 HMAC 서명 토큰으로 발급: `{eventId, userId, exp}` + 서명
  - coupon-api는 서명과 만료만 검증 (Redis 조회 없음). 한 번 쓴 토큰은 재사용 불가 처리
  - (선택) nginx 또는 Spring Cloud Gateway에서 토큰 검증
- **완료 기준**: 위조 토큰, 만료 토큰, 다른 사용자 토큰으로 발급하면 모두 `403`. 토큰 검증 추가 전후 p99 비교

## B2. 요청 횟수 제한 (rate limit) ⬜

- **왜**: 매크로나 재시도 폭주로 한 사용자가 수백 번 요청하는 것을 서버 입구에서 막아야 합니다.
- **무엇을**
  - 사용자별: Bucket4j + Redis로 초당 N회 제한 → 초과 시 `429 Too Many Requests` + `Retry-After`
  - IP별: nginx `limit_req`
  - 시뮬레이터 행동 모델이 `Retry-After`를 따르게 할지 선택 가능하게
- **완료 기준**: `AGGRESSIVE` 프리셋에서 rate limit 켜기/끄기에 따라 서버 부하와 p99가 어떻게 달라지는지 비교

## B3. 가짜 로그인 + JWT 사용자 식별 ⬜

- **왜**: `X-User-Id` 헤더는 누구나 바꿀 수 있습니다. 실무에서는 인증 토큰에서 사용자를 꺼냅니다.
- **무엇을**: 비밀번호 없는 가짜 로그인 API(`POST /auth/dev-login {userId}`)로 JWT 발급, API는 JWT에서 userId 추출. 시뮬레이터는 시작 전에 사용자별 토큰을 미리 발급
- **완료 기준**: 헤더 위조로 다른 사용자 이름으로 발급할 수 없음

## B4. Prometheus + Grafana 모니터링 ⬜

- **왜**: 실무에서는 서버가 스스로 내보내는 지표로 병목을 찾습니다. 우리 대시보드는 데모용이고, Grafana는 운영 관점용으로 나눠 씁니다.
- **무엇을**
  - 모든 앱에 Micrometer + `/actuator/prometheus`
  - docker-compose에 Prometheus, Grafana, redis-exporter, mysqld-exporter, Kafka exporter 추가
  - Grafana 대시보드 JSON을 `infra/grafana/`에 저장 (자동 프로비저닝)
  - 볼 지표: HTTP 요청 수와 지연시간(인스턴스별), HikariCP 대기·활성, JVM GC·힙, Virtual Thread 고정(pinning), Redis 명령 지연, Kafka Consumer lag, MySQL 락 대기
- **완료 기준**: DB_PESSIMISTIC 실행 중에 "커넥션 풀 대기 증가 → p99 증가"를 Grafana에서 연결해서 설명할 수 있음

## B5. 분산 추적 (OpenTelemetry) ⬜

- **왜**: 느린 요청 하나가 nginx → API → Redis → Kafka → worker → DB 중 어디서 시간을 썼는지 봅니다.
- **무엇을**: OpenTelemetry Java agent, Tempo 또는 Jaeger, Kafka 헤더로 trace 전파. Grafana에서 지표 → trace로 이동
- **완료 기준**: p99에 걸린 요청 하나를 골라 구간별 소요 시간을 확인

## B6. 장애 주입 시나리오 ⬜

- **왜**: 실무 설계 리뷰와 면접에서 가장 많이 받는 질문이 "OO가 죽으면?"입니다. [정합성 보장 장치](03-issuance-strategies.md#정합성-보장-장치)가 실제로 동작하는지 확인합니다.
- **무엇을**
  - 도구: Toxiproxy (지연·끊김 주입), `docker pause`/`docker stop`
  - 대시보드에 "장애 주입" 패널: 시뮬레이션 중간에 버튼으로 주입

  | 시나리오 | 주입 방법 | 기대 결과 |
  |---|---|---|
  | Redis 응답 지연 200ms | Toxiproxy latency | p99 증가, 정합성 유지 |
  | Redis 중단 10초 | `docker pause redis` | 그동안 `ERROR`, 복구 후 정상. 초과 발급 없음 |
  | Kafka 브로커 중단 | `docker stop kafka` | `REDIS_KAFKA`가 Redis 보상 후 `ERROR`. 대사 후 정합성 통과 |
  | coupon-worker 중단 후 재시작 | `docker restart` | lag이 쌓였다가 해소. 중복 insert 없음 |
  | MySQL 느려짐 | Toxiproxy latency | DB 락 전략 TPS 급감, Redis 전략은 영향 적음 |
  | API 인스턴스 1대 중단 | `docker stop coupon-api-2` | nginx가 남은 1대로 보냄. 진행 중 요청 일부 에러 후 재시도 |

- **완료 기준**: 위 시나리오마다 결과 기록과 대사 결과가 문서에 정리됨

## B7. Redis 고가용성 (Sentinel / Cluster) ⬜

- **왜**: Redis 1대는 단일 장애점입니다. 실무에서는 Sentinel(자동 장애 조치)이나 Cluster를 씁니다.
- **무엇을**: Sentinel 구성(master 1, replica 2, sentinel 3) 또는 Cluster 6노드. 장애 조치 중 비동기 복제 때문에 **방금 차감한 재고가 사라질 수 있는** 문제 관찰
- **완료 기준**: master를 죽였을 때 장애 조치 시간과, 그 사이 정합성 영향(대사 배치로 복구되는지) 확인

## B8. 핫 키 분산 (재고 버킷 샤딩) ⬜

- **왜**: 모든 요청이 `coupon:{eventId}:stock` 키 하나에 몰려서, 아주 큰 규모에서는 Redis 노드 하나가 병목이 됩니다.
- **무엇을**: 재고를 N개 버킷 키로 나누고 사용자 해시로 버킷 선택. 버킷이 비면 다른 버킷 탐색
- **완료 기준**: Cluster 환경에서 버킷 수에 따른 처리량 비교. 남은 재고가 적을 때 버킷 탐색 비용도 함께 확인

## B9. 이벤트 오픈 준비 ⬜

- **왜**: 실무에서는 오픈 직전에 재고를 Redis에 미리 올려두고, 오픈 시각 전에 들어온 요청은 막습니다.
- **무엇을**: `start_at` 전 요청은 `425 Too Early`, 오픈 N분 전 재고 적재 스케줄러, 서버 시계가 아닌 Redis `TIME` 기준 판정. 시뮬레이터에 "오픈 시각 전부터 대기하다 정각에 몰리는" 패턴 추가
- **완료 기준**: 오픈 정각에 몰리는 트래픽 그래프와 정각 이전 요청 차단 확인

## B10. Testcontainers 통합 테스트 ⬜

- **왜**: H2나 Mock 대신 실제 MySQL, Redis, Kafka로 동시성 테스트를 해야 락과 Lua 동작이 실무와 같습니다.
- **무엇을**: 전략별 동시성 테스트를 Testcontainers로 실행. GitHub Actions에서 PR마다 실행
- **완료 기준**: PR을 올리면 모든 전략의 정합성 테스트가 CI에서 자동으로 돎

## B11. 부하 테스트 방법론 ⬜

- **왜**: 부하 생성기와 서버가 같은 노트북에 있으면 서로 CPU를 뺏어서 수치가 왜곡됩니다. 한 번 측정한 값은 편차가 큽니다.
- **무엇을**
  - k6 스크립트로 같은 시나리오를 외부에서 실행해 시뮬레이터 결과와 교차 검증
  - (가능하면) 부하 생성기를 다른 장비에서 실행
  - JVM 워밍업 실행 후 본 측정, 같은 조건 5회 반복, 평균과 편차 기록
- **완료 기준**: 결과 정리 문서의 모든 수치에 반복 횟수와 편차가 표기됨

## B12. Transactional Outbox 전략 ⬜

- **왜**: 지금 `REDIS_KAFKA`는 "Redis 차감 → Kafka 전송"이라 둘 사이 실패를 보상과 대사로 처리합니다. 다른 실무 방식은 DB 트랜잭션 안에 이벤트를 같이 기록하는 Outbox 패턴입니다.
- **무엇을**: 8번째 전략 `DB_OUTBOX`. 발급과 outbox 레코드를 한 트랜잭션에 저장, 별도 릴레이(폴링 또는 Debezium CDC)가 Kafka로 전송
- **완료 기준**: `REDIS_KAFKA`와 처리량, 지연, 장애 시 복구 방식 비교

## B13. 쿠폰 도메인 확장 ⬜

- **왜**: 실제 쿠폰 시스템은 "쿠폰 정책"과 "발급된 쿠폰"을 나누고, 유효기간과 사용 처리가 있습니다.
- **무엇을**: `CouponPolicy`(할인 방식, 금액, 유효기간)와 `IssuedCoupon`(상태: 발급/사용/만료) 분리, 쿠폰 사용 API와 동시 사용 방지, 만료 배치
- **완료 기준**: 같은 쿠폰을 동시에 두 번 사용하려 해도 한 번만 성공
