package com.skwzz.fcfs.api.queue;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 입장 스케줄러와 순번 push를 돌린다. Virtual Thread를 켜 두었으므로 작업마다 가상 스레드에서 실행된다 */
@Configuration
@EnableScheduling
class QueueConfig {
}
