package com.saferoute.situation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 상황 판단(C 파트) 설정을 스프링에 등록한다 */
@Configuration
@EnableConfigurationProperties(SituationProperties.class)
public class SituationConfig {
}
