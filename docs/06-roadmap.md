# 06. 로드맵

각 단계가 끝날 때마다 **실행해서 눈으로 확인할 수 있는 상태**가 되도록 나눴습니다.
여기 없는 항목은 [07. 확장 백로그](07-backlog.md)에 모아 두고, 원할 때 골라서 추가합니다.

## Phase 0. 뼈대
- [x] `frontend` 목업 (React + Vite + TS, 가짜 데이터)
- [ ] `infra/docker-compose.yml` (MySQL, Redis(AOF), Kafka)
- [ ] `backend` Gradle 멀티모듈: `common`, `coupon-api`, `coupon-worker`, `simulator`
- **완료 기준**: 인프라를 띄우고, 네 모듈이 각각 실행되어 헬스체크 OK

## Phase 1. 문제를 눈으로 보기 (NAIVE + DB 락)
- [ ] CouponEvent / CouponIssue 엔티티, 이벤트 생성/초기화/검증 API
- [ ] `IssueStrategy` 인터페이스 + `NAIVE`, `SYNCHRONIZED`, `DB_PESSIMISTIC`, `DB_OPTIMISTIC`
- [ ] 동시성 테스트 (JUnit + `ExecutorService` + `CountDownLatch`)
- **완료 기준**: 테스트로 NAIVE의 초과 발급을 재현하고, 락 전략은 통과

## Phase 2. 시뮬레이터 + 대시보드 연결
- [ ] `simulator` 앱: 가상 사용자(Virtual Thread, 실제 HTTP), HdrHistogram, 실행/중단 API
- [ ] 행동 모델 `IDEAL` 프리셋 (1회 요청)
- [ ] SSE 스트림 (`tick`, `users`, `done`)
- [ ] 프론트: 목업의 `mock/`을 실제 REST/SSE 연결로 교체
- **완료 기준**: 브라우저에서 5,000명 시뮬레이션을 실행하고 NAIVE vs DB_PESSIMISTIC 차이 확인

## Phase 3. 실무형 배포 구성 (실측 모드)
- [ ] 각 앱 Dockerfile
- [ ] `infra/docker-compose.app.yml`: nginx + coupon-api ×2 + coupon-worker + simulator, 컨테이너 자원 제한
- [ ] `X-Served-By` 응답 헤더, 인스턴스별 처리 비율 표시
- [ ] 서버 측 핵심 지표 (HikariCP 대기/활성) 대시보드 표시
- **완료 기준**: 요청이 두 인스턴스로 나뉘는 것을 확인하고, `SYNCHRONIZED`가 개발 모드에서는 통과하고 실측 모드에서는 초과 발급되는 것을 재현 (시나리오 S7)

## Phase 4. Redis 전략 + 사용자 행동 모델
- [ ] `REDIS_LOCK` (Redisson), `REDIS_ATOMIC` (Lua + 실패 시 보상)
- [ ] 행동 모델 `REALISTIC`, `AGGRESSIVE` 프리셋: 연타, 재시도(백오프/지터), `X-Request-Id` 재사용
- [ ] 프론트: 실제 데이터로 사용자 그리드와 타임라인(시도 횟수 포함), 행동 모델 설정 UI
- **완료 기준**: 10,000명 그리드가 끊기지 않고 갱신되고, `AGGRESSIVE`에서 재시도 폭주로 p99가 나빠지는 것 확인 (시나리오 S6)

## Phase 5. 대기열
- [ ] Redis ZSET 대기열, 입장 스케줄러(Redis `TIME` 기준), 입장 토큰
- [ ] 순번 알림 SSE + 폴링
- [ ] 행동 모델: 대기 중 새로고침, 이탈(`ABANDONED`)
- [ ] 프론트: 대기열 모달, "직접 참여" 모드를 실제 대기열에 연결, 미니 폰 뷰
- **완료 기준**: 시뮬레이션 1만 명이 대기하는 중에 직접 참여해서, 가상 사용자들 뒤에 줄을 서고 순번이 줄어드는 모달 확인

## Phase 6. Kafka 비동기 발급 + 정합성 보장 장치
- [ ] `REDIS_KAFKA` 전략: 멱등 producer(`acks=all`), ack 후 응답, 전송 실패 시 Redis 보상
- [ ] `coupon-worker`: 배치 Consumer, DB 커밋 후 오프셋 커밋, 재시도 → DLT, DLT 재처리
- [ ] 대사(reconciliation) 배치와 결과 기록
- [ ] Consumer lag, DB 반영 지연 지표
- **완료 기준**: 5만 명 시나리오에서 응답 지연과 DB 반영 지연을 구분해서 확인하고, coupon-worker를 중간에 재시작해도 최종 정합성 검증이 통과

## Phase 7. 비교 & 정리
- [ ] 실행 기록, 실행 비교 화면 (실제 데이터)
- [ ] 결과 정리 문서 (전략별 벤치마크 표 + 결론)
