# FCFS Coupon Simulator

선착순(First-Come, First-Served) 쿠폰 발급 시스템을 직접 만들고, **N명이 동시에 요청하는 상황을 시뮬레이션**하면서
발급 전략별로 정합성과 성능이 어떻게 다른지 **대시보드에서 실시간으로 확인**하는 프로젝트입니다.

## 한 줄 요약

> "쿠폰 100장, 사용자 10,000명" 같은 상황을 버튼 하나로 재현하고, 어떤 전략에서 초과 발급이나 중복 발급이 생기는지,
> 사용자들이 대기열에서 어떻게 움직이는지 눈으로 확인합니다.

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| Backend | Spring Boot 4.1, Java 21, Gradle 9 (멀티모듈) | Virtual Thread 사용 |
| DB | MySQL 8 | 발급 이력과 재고의 최종 저장소 |
| Cache / 대기열 | Redis 7 | 원자적 재고 차감, 대기열(Sorted Set), 분산 락 |
| Messaging | Kafka (KRaft 모드) | 비동기 발급 전략에 사용 |
| Frontend | React 18 + Vite + TypeScript | 대시보드와 가상 사용자 시각화 |
| Infra | Docker Compose | nginx + API 2대 + Consumer 워커 + 시뮬레이터, MySQL, Redis, Kafka |

실무 구성(로드밸런서, 다중 인스턴스, 별도 Consumer 워커, 별도 부하 생성기)을 그대로 두고,
컨테이너 자원만 작게 제한해서 **작은 규모로 실무 병목을 재현**하는 것이 목표입니다.

## 로컬 실행 (개발 모드)

필요한 것: Docker Desktop, JDK 21, Node.js 20+

**1. 인프라 (MySQL, Redis, Kafka)**

```bash
docker compose -f infra/docker-compose.yml up -d --wait
```

Kafka UI까지 보고 싶으면 `--profile tools`를 붙이면 됩니다(http://localhost:8085).

**2. 백엔드** (`backend/` 폴더에서, 각각 다른 터미널)

```bash
./gradlew :coupon-api:bootRun
./gradlew :coupon-worker:bootRun
./gradlew :simulator:bootRun
```

| 앱 | 포트 | 헬스체크 |
|---|---|---|
| coupon-api | 8080 | http://localhost:8080/actuator/health |
| coupon-worker | 8082 | http://localhost:8082/actuator/health |
| simulator | 8090 | http://localhost:8090/actuator/health |

**3. 프론트엔드**

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

http://localhost:5173

**정리**

```bash
docker compose -f infra/docker-compose.yml down      # 컨테이너만 내림 (데이터 유지)
docker compose -f infra/docker-compose.yml down -v   # 데이터까지 삭제
```

### 문제 해결: `Unable to establish loopback connection`

Windows에서 Java는 내부 통신에 임시 폴더의 소켓 파일을 씁니다. 보안 프로그램 등이 사용자 임시 폴더(`AppData\Local\Temp`)의 소켓 파일을 막으면 Gradle과 Spring 앱이 이 에러로 실행되지 않습니다.
다른 폴더를 쓰도록 지정하면 해결됩니다.

```bash
setx JAVA_TOOL_OPTIONS "-Djdk.net.unixdomain.tmpdir=D:\tmp\jdk-uds"
```

`D:\tmp\jdk-uds` 폴더를 먼저 만들고 실행하세요. 새로 연 터미널과 IDE부터 적용됩니다.

## 문서

| 문서 | 내용 |
|---|---|
| [01. 요구사항 & 시나리오](docs/01-requirements.md) | 무엇을 보고 싶은지, 기능/비기능 요구사항 |
| [02. 아키텍처](docs/02-architecture.md) | 전체 구성, 모듈, 도메인 모델, 인프라 |
| [03. 발급 전략](docs/03-issuance-strategies.md) | 비교할 동시성 제어 전략 7가지 |
| [04. API & 실시간 이벤트 명세](docs/04-api-spec.md) | REST API, SSE 이벤트 포맷 |
| [05. 프론트엔드 설계](docs/05-frontend.md) | 화면 구성, 사용자 상태 시각화, 대기열 팝업 |
| [06. 로드맵](docs/06-roadmap.md) | 단계별 구현 계획 |
| [07. 확장 백로그](docs/07-backlog.md) | 실무에 더 가깝게 만들고 싶을 때 골라서 추가할 항목 |
