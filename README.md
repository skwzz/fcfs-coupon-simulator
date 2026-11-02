# FCFS Coupon Simulator

선착순(First-Come, First-Served) 쿠폰 발급 시스템을 직접 만들고, **N명이 동시에 요청하는 상황을 시뮬레이션**하면서
발급 전략별로 정합성과 성능이 어떻게 다른지 **대시보드에서 실시간으로 확인**하는 프로젝트입니다.

## 한 줄 요약

> "쿠폰 100장, 사용자 10,000명" 같은 상황을 버튼 하나로 재현하고, 어떤 전략에서 초과 발급이나 중복 발급이 생기는지,
> 사용자들이 대기열에서 어떻게 움직이는지 눈으로 확인합니다.

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| Backend | Spring Boot 3.x, Java 21 | Virtual Thread 사용 |
| DB | MySQL 8 | 발급 이력과 재고의 최종 저장소 |
| Cache / 대기열 | Redis 7 | 원자적 재고 차감, 대기열(Sorted Set), 분산 락 |
| Messaging | Kafka (KRaft 모드) | 비동기 발급 전략에 사용 |
| Frontend | React 18 + Vite + TypeScript | 대시보드와 가상 사용자 시각화 |
| Infra | Docker Compose | MySQL, Redis, Kafka, (선택) Prometheus/Grafana |

## 문서

| 문서 | 내용 |
|---|---|
| [01. 요구사항 & 시나리오](docs/01-requirements.md) | 무엇을 보고 싶은지, 기능/비기능 요구사항 |
| [02. 아키텍처](docs/02-architecture.md) | 전체 구성, 모듈, 도메인 모델, 인프라 |
| [03. 발급 전략](docs/03-issuance-strategies.md) | 비교할 동시성 제어 전략 7가지 |
| [04. API & 실시간 이벤트 명세](docs/04-api-spec.md) | REST API, SSE 이벤트 포맷 |
| [05. 프론트엔드 설계](docs/05-frontend.md) | 화면 구성, 사용자 상태 시각화, 대기열 팝업 |
| [06. 로드맵](docs/06-roadmap.md) | 단계별 구현 계획 |
