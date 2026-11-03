package com.saferoute.route;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RouteProperties.class)
public class RouteConfig {

    /** 밤 판단에 쓰는 시계. 테스트에서는 고정 시각으로 바꿔 끼운다 */
    @Bean
    Clock routeClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
