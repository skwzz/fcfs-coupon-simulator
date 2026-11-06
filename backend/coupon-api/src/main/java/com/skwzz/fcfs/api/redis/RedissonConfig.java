package com.skwzz.fcfs.api.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 분산 락(REDIS_LOCK)용 Redisson 클라이언트. Lua 스크립트와 일반 명령은 Spring Data Redis(Lettuce)로 보낸다.
 * 접속 정보는 spring.data.redis 설정을 같이 쓴다.
 */
@Configuration
class RedissonConfig {

	@Bean(destroyMethod = "shutdown")
	RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
			@Value("${spring.data.redis.port}") int port,
			@Value("${spring.data.redis.database:0}") int database) {
		Config config = new Config();
		config.useSingleServer()
				.setAddress("redis://" + host + ":" + port)
				.setDatabase(database);
		return Redisson.create(config);
	}
}
