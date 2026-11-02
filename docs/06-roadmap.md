# 06. 로드맵

각 단계가 끝날 때마다 **실행해서 눈으로 확인할 수 있는 상태**가 되도록 나눴습니다.

## Phase 0. 뼈대
- [ ] `infra/docker-compose.yml` (MySQL, Redis, Kafka)
- [ ] `backend` Spring Boot 프로젝트 (Web, JPA, Data Redis, Kafka, Actuator)
- [ ] `frontend` Vite + React + TS 프로젝트
- **완료 기준**: `docker compose up` 후 백엔드 헬스체크 OK, 프론트엔드 기본 화면 표시

## Phase 1. 문제를 눈으로 보기 (NAIVE + DB 락)
- [ ] CouponEvent / CouponIssue 엔티티, 이벤트 생성/초기화/검증 API
- [ ] `IssueStrategy` 인터페이스 + `NAIVE`, `SYNCHRONIZED`, `DB_PESSIMISTIC`, `DB_OPTIMISTIC`
- [ ] 동시성 테스트 (JUnit + `ExecutorService` + `CountDownLatch`)
- **완료 기준**: 테스트로 NAIVE의 초과 발급을 재현하고, 락 전략은 통과

## Phase 2. 시뮬레이터 + 기본 대시보드
- [ ] 시뮬레이션 실행기 (Virtual Thread, 실제 HTTP 호출, HdrHistogram)
- [ ] SSE 스트림 (`tick`, `users`, `done`)
- [ ] 프론트: 설정 패널, KPI 카드, TPS/지연시간 차트, 최종 검증 결과
- **완료 기준**: 브라우저에서 5,000명 시뮬레이션을 실행하고 NAIVE vs DB_PESSIMISTIC 차이 확인

## Phase 3. Redis 전략 + 사용자 그리드
- [ ] `REDIS_LOCK` (Redisson), `REDIS_ATOMIC` (Lua)
- [ ] 프론트: canvas 사용자 그리드, 사용자 타임라인 드로어
- **완료 기준**: 10,000명 그리드가 끊기지 않고 갱신됨

## Phase 4. 대기열
- [ ] Redis ZSET 대기열, 입장 스케줄러, 입장 토큰
- [ ] 순번 알림 SSE + 폴링
- [ ] 프론트: 대기열 모달, "직접 참여" 모드, 미니 폰 뷰
- **완료 기준**: 시뮬레이션 1만 명이 대기하는 중에 직접 참여해서 순번이 줄어드는 모달 확인

## Phase 5. Kafka 비동기 발급
- [ ] `REDIS_KAFKA` 전략, Consumer(배치 insert, 멱등 처리, DLT)
- [ ] Consumer lag, DB 반영 지연 지표
- **완료 기준**: 5만 명 시나리오에서 응답 지연과 DB 반영 지연을 대시보드에서 구분해서 확인

## Phase 6. 비교 & 마무리 (선택)
- [ ] 실행 기록, 실행 비교 화면
- [ ] k6 스크립트로 외부 부하 교차 검증
- [ ] Prometheus/Grafana로 JVM, 커넥션 풀, Redis 지표 확인
- [ ] 백엔드 2 인스턴스 + Nginx로 `SYNCHRONIZED`가 깨지는 모습 시연
- [ ] 장애 주입 (Redis 정지, Kafka 지연)
- [ ] 결과 정리 문서 (전략별 벤치마크 표 + 결론)
