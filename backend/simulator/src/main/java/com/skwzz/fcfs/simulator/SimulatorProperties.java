package com.skwzz.fcfs.simulator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param targetBaseUrl 가상 사용자가 요청을 보낼 곳 (개발 모드는 coupon-api, 실측 모드는 nginx)
 * @param maxKeptRuns 메모리에 남겨 둘 최근 실행 수
 */
@ConfigurationProperties("simulator")
public record SimulatorProperties(String targetBaseUrl, Duration connectTimeout, Duration requestTimeout, int maxKeptRuns) {
}
