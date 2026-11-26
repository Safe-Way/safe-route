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

/**
 * 매일 기록 정리: 기간 계산, 돌아온 게스트는 남기기, 하나가 실패해도 나머지.
 * 무엇을: RecordCleanup 이 표마다 보관 기간이 지난 줄을 지우고, 1년 안 쓴 게스트를 "내 기록 지우기"처럼 지우는지.
 * 어떻게: JdbcTemplate·MeRepository·TransactionTemplate 은 Mockito 가짜, 시계는 고정(Clock.fixed)이다.
 *        TransactionTemplate 가짜는 받은 콜백을 바로 실행만 한다 (진짜 트랜잭션 없음).
 * 왜: 날짜를 하루 틀리면 아직 남겨야 할 기록을 지우고, 돌아온 사람을 못 거르면 지금 쓰는 사람의 기록이 사라지며,
 *     표 하나의 오류로 정리가 멈추면 개인정보가 보관 기간을 넘겨 남는다.
 */
@DisplayName("매일 기록 정리")
class RecordCleanupTests {

    // UTC로는 아직 11-24, 한국으로는 11-25 04:40(정리가 도는 시각). UTC 날짜로 계산하면 하루 틀려서 걸린다
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T19:40:00Z"), ZoneId.of("Asia/Seoul")); // 11-25 04:40
    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    // 토큰 줄을 "아직도 오래됐을 때만" 지우는 SQL (운영 코드와 같은 글자여야 when(...) 이 맞는다)
    static final String STALE_TOKEN = "DELETE FROM guest_token WHERE guest_id = ? AND " + RecordCleanup.STALE;

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final MeRepository me = mock(MeRepository.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    // 보관 일수: 못 한 요청 90, 게스트 365, ODsay 30, API 호출 수 90 (운영 기본값과 같음)
    final RecordCleanup cleanup = new RecordCleanup(jdbc, me, tx, CLOCK, 90, 365, 30, 90);

    RecordCleanupTests() {
        // tx.execute(콜백) → 콜백을 바로 돌려 그 결과를 돌려준다
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    @DisplayName("한국 날짜로 기간을 계산해 오래된 줄을 지운다")
    void deletesOldRowsByKoreanDate() {
        // 기준 날짜가 한국 날짜(11-25)에서 계산돼야 정한 when(...) 이 맞아 3·2·4 가 나온다. 하루 틀리면 0이 된다.
        // 게스트 목록은 정하지 않아 가짜가 빈 목록을 돌려주므로 guests = 0
        when(jdbc.update("DELETE FROM assistant_unmet WHERE asked_on < ?", TODAY.minusDays(90))).thenReturn(3);
        when(jdbc.update("DELETE FROM odsay_call_count WHERE day < ?", TODAY.minusDays(30))).thenReturn(2);
        when(jdbc.update("DELETE FROM api_call_count WHERE day < ?", TODAY.minusDays(90))).thenReturn(4);

        RecordCleanup.Result r = cleanup.cleanUp();

        assertThat(r).isEqualTo(new RecordCleanup.Result(3, 0, 2, 4));
        assertThat(TODAY.minusDays(90)).isEqualTo(LocalDate.of(2026, 8, 27));   // 읽는 사람을 위한 확인: 90일 전 = 8-27
    }

    @Test
    @DisplayName("1년 안 쓴 게스트는 내 기록 지우기처럼 지우고, 그사이 돌아온 사람은 남긴다")
    void staleGuestIsDeletedLikeDeleteAllButOneWhoCameBackIsKept() {
        // 목록을 고른 뒤 지우기 전에 그 사람이 앱을 다시 쓰면 last_used_on 이 오늘로 바뀐다.
        // 토큰 줄 DELETE 가 0줄이면 돌아온 것이므로 나머지 기록도 지우면 안 된다
        LocalDate before = TODAY.minusDays(365);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(before))).thenReturn(List.of("old", "back"));
        when(jdbc.update(STALE_TOKEN, "old", before)).thenReturn(1);
        when(jdbc.update(STALE_TOKEN, "back", before)).thenReturn(0); // 고른 뒤 다시 써서 last_used_on 이 오늘

        assertThat(cleanup.staleGuests(before)).isEqualTo(1);
        verify(me).deleteAll("old");
        verify(me, never()).deleteAll("back");   // 돌아온 사람의 기록은 그대로
    }

    @Test
    @DisplayName("하나가 실패해도 나머지는 계속 지운다")
    void oneFailureDoesNotStopTheRest() {
        // 표 하나가 없거나 DB가 잠깐 안 돼도 나머지 표는 지워야 한다. 못 한 요청 표와 guest_token 이 실패해도 마지막 API 표는 지운다
        // (ODsay 는 정하지 않아 가짜가 0을 돌려준다)
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
        // 게스트 하나를 지우다 잠금 오류가 나도 나머지 게스트는 계속 지운다. 실패한 사람은 다음 날 다시 고른다
        LocalDate before = TODAY.minusDays(365);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(before))).thenReturn(List.of("a", "b"));
        when(jdbc.update(STALE_TOKEN, "a", before)).thenThrow(new DataAccessResourceFailureException("lock"));   // a 는 토큰 줄부터 실패
        when(jdbc.update(STALE_TOKEN, "b", before)).thenReturn(1);

        assertThat(cleanup.staleGuests(before)).isEqualTo(1);
        verify(me).deleteAll("b");
    }
}
