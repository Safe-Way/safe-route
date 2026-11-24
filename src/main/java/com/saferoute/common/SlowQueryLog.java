package com.saferoute.common;

import java.util.List;

import javax.sql.DataSource;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 느린 쿼리 로그 (2026-11-23). 학교 MySQL의 slow log는 공용 서버 파일이라 우리 계정으로 못 읽는다.
 * 그래서 DataSource를 감싸 SQL마다 걸린 시간을 재고, db.slow-query-ms 보다 오래 걸리면 WARN으로 남긴다.
 * 넣은 값(게스트 번호·위치)은 남기지 않고 SQL 글자만 남긴다. 1주쯤 모아 인덱스를 더할 곳을 찾는다.
 */
@Component
public class SlowQueryLog implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryLog.class);
    static final int MAX_SQL = 300;

    private final long thresholdMs;

    public SlowQueryLog(@Value("${db.slow-query-ms:500}") long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (thresholdMs <= 0 || !(bean instanceof DataSource ds) || bean instanceof ProxyDataSource) {
            return bean;
        }
        return ProxyDataSourceBuilder.create(ds).name(beanName).listener(new Listener(thresholdMs)).build();
    }

    /** 걸린 시간이 한도를 넘은 실행만 남긴다 */
    static final class Listener implements QueryExecutionListener {
        private final long thresholdMs;

        Listener(long thresholdMs) {
            this.thresholdMs = thresholdMs;
        }

        @Override
        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queries) {
        }

        @Override
        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queries) {
            if (execInfo.getElapsedTime() < thresholdMs) {
                return;
            }
            log.warn("느린 쿼리 {}ms{}: {}", execInfo.getElapsedTime(),
                    execInfo.isBatch() ? " (묶음 " + execInfo.getBatchSize() + "건)" : "", sql(queries));
        }
    }

    /** 여러 SQL은 " ; "로 잇고, 줄바꿈·공백을 하나로 줄여 MAX_SQL 자까지 */
    static String sql(List<QueryInfo> queries) {
        String joined = String.join(" ; ", queries.stream().map(QueryInfo::getQuery).toList())
                .replaceAll("\\s+", " ").trim();
        return joined.length() <= MAX_SQL ? joined : joined.substring(0, MAX_SQL) + "…";
    }
}
