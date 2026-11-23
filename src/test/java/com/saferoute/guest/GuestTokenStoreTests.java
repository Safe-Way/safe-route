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
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** 게스트 토큰: 처음 온 토큰을 등록하고 다음부터 같은지 본다 (037) */
@SuppressWarnings("unchecked")
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
    void missingTokenNeedsNoDb() {
        assertThat(store.check(GUEST, null)).isEqualTo(GuestTokenStore.Check.MISSING);
        assertThat(store.check(GUEST, "  ")).isEqualTo(GuestTokenStore.Check.MISSING);
        verify(jdbc, never()).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void tooShortOrLongTokenIsWrong() {
        assertThat(store.check(GUEST, "short")).isEqualTo(GuestTokenStore.Check.WRONG);
        assertThat(store.check(GUEST, "x".repeat(GuestTokenStore.MAX_TOKEN + 1))).isEqualTo(GuestTokenStore.Check.WRONG);
    }

    @Test
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
    void someoneElsesRegisteredTokenIsWrong() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST)))
                .thenReturn(GuestTokenStore.sha256("the-real-owners-token-0123456789-abcdef"));
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.WRONG);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
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
    void dbTroubleDoesNotBlock() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST)))
                .thenThrow(new DataAccessResourceFailureException("Table 'guest_token' doesn't exist"));
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.UNAVAILABLE);
    }

    @Test
    void sha256IsHex64() {
        assertThat(GuestTokenStore.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
