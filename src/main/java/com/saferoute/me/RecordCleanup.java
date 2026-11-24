package com.saferoute.me;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매일 기록 정리 (2026-11-24, 공개 배포 준비 DB 8). 새벽 4시 40분(한국 시각), 신고 정리(4시 30분) 다음.
 * <ul>
 *   <li>비서가 못 한 요청(assistant_unmet): 90일 지난 것</li>
 *   <li>1년 동안 안 쓴 게스트: guest_token 의 마지막 확인 날짜(없으면 등록 날짜)가 1년 지난 사람의 기록을
 *       "내 기록 지우기"와 같은 코드(MeRepository.deleteAll)로 모두 지우고 토큰 줄도 지운다 (개인정보 보관 기간).
 *       토큰이 없는 예전 앱 사용자는 언제 마지막으로 썼는지 몰라 지우지 않는다</li>
 *   <li>ODsay 하루 호출 수(odsay_call_count): 30일, 외부 API 호출 수(api_call_count): 90일</li>
 * </ul>
 * 서버 두 대가 같이 돌아도 결과가 같다. records.cleanup=false 로 끌 수 있다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "records.cleanup", havingValue = "true", matchIfMissing = true)
public class RecordCleanup {

    private static final Logger log = LoggerFactory.getLogger(RecordCleanup.class);
    /** 한 번에 지우는 게스트 수 (나머지는 다음 날) */
    static final int GUEST_BATCH = 500;
    static final String STALE = "COALESCE(last_used_on, DATE(created_at)) < ?";

    private final JdbcTemplate jdbc;
    private final MeRepository me;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int unmetDays;
    private final int guestDays;
    private final int odsayDays;
    private final int apiDays;

    public RecordCleanup(JdbcTemplate jdbc, MeRepository me, TransactionTemplate tx, Clock clock,
                         @Value("${records.unmet-days:90}") int unmetDays,
                         @Value("${records.guest-days:365}") int guestDays,
                         @Value("${records.odsay-days:30}") int odsayDays,
                         @Value("${records.api-days:90}") int apiDays) {
        this.jdbc = jdbc;
        this.me = me;
        this.tx = tx;
        this.clock = clock;
        this.unmetDays = unmetDays;
        this.guestDays = guestDays;
        this.odsayDays = odsayDays;
        this.apiDays = apiDays;
    }

    @Scheduled(cron = "${records.cleanup-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        Result r = cleanUp();
        if (r.total() > 0) {
            log.info("기록 정리: 못 한 요청 {}건, 안 쓴 게스트 {}명, ODsay 날짜 {}줄, API 날짜 {}줄",
                    r.unmet(), r.guests(), r.odsay(), r.api());
        }
    }

    record Result(int unmet, int guests, int odsay, int api) {
        int total() {
            return unmet + guests + odsay + api;
        }
    }

    /** 하나가 실패해도(표가 없는 등) 나머지는 한다 */
    Result cleanUp() {
        LocalDate today = LocalDate.now(clock);
        int unmet = delete("assistant_unmet", "DELETE FROM assistant_unmet WHERE asked_on < ?", today.minusDays(unmetDays));
        int guests = staleGuests(today.minusDays(guestDays));
        int odsay = delete("odsay_call_count", "DELETE FROM odsay_call_count WHERE day < ?", today.minusDays(odsayDays));
        int api = delete("api_call_count", "DELETE FROM api_call_count WHERE day < ?", today.minusDays(apiDays));
        return new Result(unmet, guests, odsay, api);
    }

    private int delete(String table, String sql, LocalDate before) {
        try {
            return jdbc.update(sql, before);
        } catch (DataAccessException e) {
            log.warn("기록 정리 실패 ({}): {}", table, e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 토큰 줄을 먼저 "아직도 오래됐을 때만" 지우고, 지워졌을 때만 나머지 기록을 지운다.
     * 고른 뒤 지우기 전에 그 사람이 돌아왔으면(last_used_on 이 오늘로 바뀜) 아무것도 지우지 않는다
     */
    int staleGuests(LocalDate before) {
        List<String> ids;
        try {
            ids = jdbc.queryForList("SELECT guest_id FROM guest_token WHERE " + STALE + " ORDER BY guest_id LIMIT "
                    + GUEST_BATCH, String.class, before);
        } catch (DataAccessException e) {
            log.warn("기록 정리 실패 (guest_token): {}", e.getClass().getSimpleName());
            return 0;
        }
        int n = 0;
        for (String g : ids) {
            try {
                Boolean done = tx.execute(s -> {
                    if (jdbc.update("DELETE FROM guest_token WHERE guest_id = ? AND " + STALE, g, before) == 0) {
                        return false;
                    }
                    me.deleteAll(g);
                    return true;
                });
                n += Boolean.TRUE.equals(done) ? 1 : 0;
            } catch (DataAccessException e) {
                log.warn("안 쓴 게스트 지우기 실패: {}… {}", g.substring(0, Math.min(8, g.length())),
                        e.getClass().getSimpleName());
            }
        }
        return n;
    }
}
