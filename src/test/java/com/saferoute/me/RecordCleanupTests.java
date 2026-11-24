package com.saferoute.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 매일 기록 정리: 기간 계산, 돌아온 게스트는 남기기, 하나가 실패해도 나머지 */
@DisplayName("매일 기록 정리")
class RecordCleanupTests {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T19:40:00Z"), ZoneId.of("Asia/Seoul")); // 11-25 04:40
    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    static final String STALE_TOKEN = "DELETE FROM guest_token WHERE guest_id = ? AND " + RecordCleanup.STALE;

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final MeRepository me = mock(MeRepository.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    final RecordCleanup cleanup = new RecordCleanup(jdbc, me, tx, CLOCK, 90, 365, 30, 90);

    RecordCleanupTests() {
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    @DisplayName("한국 날짜로 기간을 계산해 오래된 줄을 지운다")
    void deletesOldRowsByKoreanDate() {
        when(jdbc.update("DELETE FROM assistant_unmet WHERE asked_on < ?", TODAY.minusDays(90))).thenReturn(3);
        when(jdbc.update("DELETE FROM odsay_call_count WHERE day < ?", TODAY.minusDays(30))).thenReturn(2);
        when(jdbc.update("DELETE FROM api_call_count WHERE day < ?", TODAY.minusDays(90))).thenReturn(4);

        RecordCleanup.Result r = cleanup.cleanUp();

        assertThat(r).isEqualTo(new RecordCleanup.Result(3, 0, 2, 4));
        assertThat(TODAY.minusDays(90)).isEqualTo(LocalDate.of(2026, 8, 27));
    }

    @Test
    @DisplayName("1년 안 쓴 게스트는 내 기록 지우기처럼 지우고, 그사이 돌아온 사람은 남긴다")
    void staleGuestIsDeletedLikeDeleteAllButOneWhoCameBackIsKept() {
        LocalDate before = TODAY.minusDays(365);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(before))).thenReturn(List.of("old", "back"));
        when(jdbc.update(STALE_TOKEN, "old", before)).thenReturn(1);
        when(jdbc.update(STALE_TOKEN, "back", before)).thenReturn(0); // 고른 뒤 다시 써서 last_used_on 이 오늘

        assertThat(cleanup.staleGuests(before)).isEqualTo(1);
        verify(me).deleteAll("old");
        verify(me, never()).deleteAll("back");
    }

    @Test
    @DisplayName("하나가 실패해도 나머지는 계속 지운다")
    void oneFailureDoesNotStopTheRest() {
        when(jdbc.update("DELETE FROM assistant_unmet WHERE asked_on < ?", TODAY.minusDays(90)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        when(jdbc.queryForList(anyString(), eq(String.class), any(LocalDate.class)))
                .thenThrow(new DataAccessResourceFailureException("Table 'guest_token' doesn't exist"));
        when(jdbc.update("DELETE FROM api_call_count WHERE day < ?", TODAY.minusDays(90))).thenReturn(1);

        assertThat(cleanup.cleanUp()).isEqualTo(new RecordCleanup.Result(0, 0, 0, 1));
    }

    @Test
    @DisplayName("지우다 실패한 게스트는 건너뛴다")
    void failedGuestIsSkipped() {
        LocalDate before = TODAY.minusDays(365);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(before))).thenReturn(List.of("a", "b"));
        when(jdbc.update(STALE_TOKEN, "a", before)).thenThrow(new DataAccessResourceFailureException("lock"));
        when(jdbc.update(STALE_TOKEN, "b", before)).thenReturn(1);

        assertThat(cleanup.staleGuests(before)).isEqualTo(1);
        verify(me).deleteAll("b");
    }
}
