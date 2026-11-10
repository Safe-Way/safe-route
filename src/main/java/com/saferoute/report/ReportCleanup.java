package com.saferoute.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 하루 한 번(새벽 4시 30분, 한국 시각) 결정 59 보관 기간이 지난 신고를 지운다.
 * 사진: 신고 기간이 끝나고 7일 뒤(숨긴 신고는 바로), 신고 줄: 기간이 끝나고 30일 뒤.
 * 같은 종류 30m 안에 아직 남은 신고가 있으면 지우지 않는다 (SQL 은 scripts/review_reports.py 와 같음).
 * 서버 두 대가 같이 돌아도 같은 결과라 괜찮다. report.cleanup=false 로 끌 수 있다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "report.cleanup", havingValue = "true", matchIfMissing = true)
public class ReportCleanup {

    private static final Logger log = LoggerFactory.getLogger(ReportCleanup.class);

    private final ReportRepository repo;

    public ReportCleanup(ReportRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "${report.cleanup-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        try {
            int[] n = repo.cleanUp();
            if (n[0] + n[1] > 0) {
                log.info("신고 정리 (결정 59): 사진 {}장, 신고 {}건 지움", n[0], n[1]);
            }
        } catch (DataAccessException e) {
            log.warn("신고 정리 실패: {}", e.getClass().getSimpleName());
        }
    }
}
