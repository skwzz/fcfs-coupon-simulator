create table coupon_event
(
    id                        bigint       not null auto_increment primary key,
    name                      varchar(100) not null,
    total_quantity            int          not null,
    issued_quantity           int          not null default 0 comment 'DB 전략에서 쓰는 발급 수량 카운터',
    strategy                  varchar(30)  not null comment '기본 발급 전략 (요청마다 바꿀 수 있음)',
    queue_enabled             boolean      not null default false,
    admit_per_second          int          not null default 200,
    unique_constraint_enabled boolean      not null default true comment 'false면 coupon_issue 중복 방지 제약을 우회 (NAIVE의 중복 발급을 보여주기 위함)',
    start_at                  datetime(6)  null,
    version                   bigint       not null default 0 comment '낙관적 락',
    created_at                datetime(6)  not null
);

-- 발급 이력. 대량 insert를 고려해 coupon_event로의 FK는 두지 않는다.
create table coupon_issue
(
    id         bigint      not null auto_increment primary key,
    event_id   bigint      not null,
    user_id    bigint      not null,
    strategy   varchar(30) not null,
    request_id varchar(64) null comment '멱등 키 (Kafka 경로)',
    dup_guard  bigint      not null default 0 comment '0이면 (event_id, user_id) 중복 금지. 제약을 끈 이벤트는 행마다 다른 값을 넣어 우회',
    issued_at  datetime(6) not null,
    constraint uk_coupon_issue_event_user unique (event_id, user_id, dup_guard)
);
