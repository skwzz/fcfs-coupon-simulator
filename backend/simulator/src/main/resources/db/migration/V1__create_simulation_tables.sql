-- 시뮬레이션 실행 기록. 비교·정렬에 쓰는 핵심 지표는 컬럼으로, 단계가 진행되며 늘어나는 지표는 JSON으로 둔다.
CREATE TABLE simulation_run (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id         BIGINT       NOT NULL COMMENT 'coupon-api 이벤트 id. 다른 서비스의 DB라 FK 없음',
    strategy         VARCHAR(32)  NOT NULL,
    user_count       INT          NOT NULL,
    stock            INT          NOT NULL,
    ramp_up_ms       INT          NOT NULL,
    behavior_preset  VARCHAR(16)  NOT NULL,
    queue_enabled    BOOLEAN      NOT NULL,
    target_base_url  VARCHAR(255) NOT NULL COMMENT '요청 대상. coupon-api 직접(개발 모드)인지 nginx(실측 모드)인지 구분',
    config_json      JSON         NOT NULL COMMENT '요청 원문. 행동 모델 세부 값 등',
    state            VARCHAR(16)  NOT NULL COMMENT 'RUNNING, DONE, STOPPED, FAILED',
    started_at       DATETIME(3)  NOT NULL,
    finished_at      DATETIME(3)  NULL,
    duration_ms      BIGINT       NULL,
    avg_tps          INT          NULL,
    p50_ms           DOUBLE       NULL,
    p95_ms           DOUBLE       NULL,
    p99_ms           DOUBLE       NULL,
    max_ms           DOUBLE       NULL,
    issued_in_db     BIGINT       NULL COMMENT 'DB 기준 정합성 검증 결과',
    over_issued      BIGINT       NULL,
    duplicate_issued BIGINT       NULL,
    passed           BOOLEAN      NULL,
    counts_json      JSON         NULL COMMENT '최종 상태별 사용자 수',
    result_json      JSON         NULL COMMENT '종료 메시지(done) 전체. 이후 단계 지표(servedBy, reconciliation, kafka lag 등)도 여기에 담긴다',
    series_json      JSON         NULL COMMENT '1초 단위 시계열 [{t, tps, p50, p95, p99, queueLength}]',
    error            VARCHAR(1000) NULL,
    INDEX idx_simulation_run_started_at (started_at)
);

-- 사용자별 최종 결과. 1만 명 실행이면 1만 행이라 일괄 insert로 저장한다.
CREATE TABLE simulation_user_result (
    run_id          BIGINT       NOT NULL,
    user_id         INT          NOT NULL,
    final_status    VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL COMMENT '연타·재시도 포함 요청 수 (Phase 4부터 1보다 커짐)',
    first_rank      INT          NULL COMMENT '대기열에 처음 들어갔을 때 순번 (Phase 5)',
    requested_at_ms INT          NULL COMMENT '실행 시작 기준 첫 요청 시각',
    completed_at_ms INT          NULL,
    latency_ms      INT          NULL,
    reason          VARCHAR(255) NULL,
    timeline_json   JSON         NOT NULL COMMENT '상태 변화 기록 [{t, status, rank, note}]',
    PRIMARY KEY (run_id, user_id)
);
