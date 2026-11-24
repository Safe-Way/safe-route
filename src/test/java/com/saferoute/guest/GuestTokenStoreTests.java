package com.saferoute.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** 게스트 토큰: 처음 온 토큰을 등록하고 다음부터 같은지 본다 (037) */
@SuppressWarnings("unchecked")
@DisplayName("게스트 토큰: 처음 등록하고 다음부터 같은지 보기")
class GuestTokenStoreTests {

    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    static final String TOKEN = "k3J9-random-token-from-the-app-0123456789";

    /** 시험에서 날짜를 옮길 수 있는 시계 */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-23T01:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MovableClock clock = new MovableClock();
    private final GuestTokenStore store = new GuestTokenStore(jdbc, clock);

    @Test
    @DisplayName("토큰이 없으면 DB를 부르지 않는다")
    void missingTokenNeedsNoDb() {
        assertThat(store.check(GUEST, null)).isEqualTo(GuestTokenStore.Check.MISSING);
        assertThat(store.check(GUEST, "  ")).isEqualTo(GuestTokenStore.Check.MISSING);
        verify(jdbc, never()).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    @DisplayName("너무 짧거나 긴 토큰은 틀린 토큰")
    void tooShortOrLongTokenIsWrong() {
        assertThat(store.check(GUEST, "short")).isEqualTo(GuestTokenStore.Check.WRONG);
        assertThat(store.check(GUEST, "x".repeat(GuestTokenStore.MAX_TOKEN + 1))).isEqualTo(GuestTokenStore.Check.WRONG);
    }

    @Test
    @DisplayName("처음 온 토큰을 등록하고 기억한다")
    void firstTokenIsRegisteredThenRemembered() {
        String hash = GuestTokenStore.sha256(TOKEN);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST))).thenReturn(null, hash);

        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);
        verify(jdbc).update(eq("INSERT IGNORE INTO guest_token (guest_id, token_hash, last_used_on) VALUES (?, ?, ?)"),
                eq(GUEST), eq(hash), eq(LocalDate.of(2026, 11, 23)));

        // 같은 날 다시 오면 DB를 읽지 않고 메모리로 확인
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);
        assertThat(store.check(GUEST, "another-token-somebody-else-made-xxxxxx")).isEqualTo(GuestTokenStore.Check.WRONG);
        verify(jdbc, times(2)).query(anyString(), any(ResultSetExtractor.class), eq(GUEST));
    }

    @Test
    @DisplayName("다른 사람이 등록한 번호에 다른 토큰이면 틀린 토큰")
    void someoneElsesRegisteredTokenIsWrong() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST)))
                .thenReturn(GuestTokenStore.sha256("the-real-owners-token-0123456789-abcdef"));
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.WRONG);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("마지막 사용 날짜는 하루에 한 번만 고친다")
    void lastUsedIsTouchedOncePerDay() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST))).thenReturn(GuestTokenStore.sha256(TOKEN));
        store.check(GUEST, TOKEN);
        store.check(GUEST, TOKEN);
        verify(jdbc, never()).update(eq("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?"), any(Object[].class));

        clock.now = clock.now.plusSeconds(86_400);
        store.check(GUEST, TOKEN);
        store.check(GUEST, TOKEN);
        verify(jdbc, times(1)).update("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?",
                LocalDate.of(2026, 11, 24), GUEST);
    }

    @Test
    @DisplayName("정리로 지워진 게스트가 돌아오면 다시 등록한다")
    void cleanedUpRowIsRegisteredAgain() {
        // 1년 안 쓴 게스트 정리(RecordCleanup)가 토큰 줄을 지웠는데 메모리에는 남아 있는 경우
        String hash = GuestTokenStore.sha256(TOKEN);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST))).thenReturn(hash, null, hash);
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);

        clock.now = clock.now.plusSeconds(86_400);
        when(jdbc.update(eq("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?"), any(Object[].class)))
                .thenReturn(0);
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);
        verify(jdbc).update("INSERT IGNORE INTO guest_token (guest_id, token_hash, last_used_on) VALUES (?, ?, ?)",
                GUEST, hash, LocalDate.of(2026, 11, 24));
    }

    @Test
    @DisplayName("DB에 문제가 있어도 막지 않는다")
    void dbTroubleDoesNotBlock() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST)))
                .thenThrow(new DataAccessResourceFailureException("Table 'guest_token' doesn't exist"));
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.UNAVAILABLE);
    }

    @Test
    @DisplayName("SHA-256 해시는 16진수 64글자")
    void sha256IsHex64() {
        assertThat(GuestTokenStore.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
