package com.saferoute.guardian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1분마다 만료된 공유를 끝내고, 끝난 지 10분 지난 줄을 지운다.
 * 서버 두 대가 같이 돌아도 같은 결과라 괜찮다. guardian.cleanup=false 로 끌 수 있다 (테스트).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "guardian.cleanup", havingValue = "true", matchIfMissing = true)
public class GuardianCleanup {

    private static final Logger log = LoggerFactory.getLogger(GuardianCleanup.class);

    private final GuardianShareService service;

    public GuardianCleanup(GuardianShareService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${guardian.cleanup-ms:60000}", initialDelayString = "${guardian.cleanup-ms:60000}")
    public void run() {
        try {
            int[] n = service.cleanUp();
            if (n[0] + n[1] > 0) {
                log.info("보호자 공유 정리: 만료 {}건, 지움 {}건", n[0], n[1]);
            }
        } catch (DataAccessException e) {
            log.warn("보호자 공유 정리 실패: {}", e.getClass().getSimpleName());
        }
    }
}
