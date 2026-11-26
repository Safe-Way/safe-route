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

/**
 * 게스트 토큰: 처음 온 토큰을 등록하고 다음부터 같은지 본다 (037).
 * 무엇을: GuestTokenStore.check 가 토큰 모양 검사 → DB 등록·비교 → 메모리 기억 → 하루 한 번 날짜 고치기를 맞게 하는지.
 * 어떻게: JdbcTemplate 을 Mockito 가짜로 바꿔 SELECT 결과를 정해 주고, 날짜는 손으로 옮기는 MovableClock 으로 바꾼다.
 * 왜: 깨지면 남의 토큰으로 남의 기록에 들어가거나(WRONG 이어야 할 때 OK), 요청마다 DB를 읽어 느려지거나,
 *     정리로 지워진 게스트가 돌아왔을 때 영영 확인을 못 하게 된다.
 */
@SuppressWarnings("unchecked")
@DisplayName("게스트 토큰: 처음 등록하고 다음부터 같은지 보기")
class GuestTokenStoreTests {

    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    // 41글자: 토큰 길이 범위(MIN_TOKEN 32 ~ MAX_TOKEN 256) 안
    static final String TOKEN = "k3J9-random-token-from-the-app-0123456789";

    /** 시험에서 날짜를 옮길 수 있는 시계 (now 를 바꾸면 "다음 날"이 된다. RateLimitFilterTests 도 같이 쓴다) */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-23T01:00:00Z");   // UTC 시계라 오늘 = 2026-11-23

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
        // 예전 앱은 토큰을 안 보낸다. 그런 요청마다 DB를 읽으면 쓸데없이 느려지므로 바로 MISSING 을 돌려야 한다
        assertThat(store.check(GUEST, null)).isEqualTo(GuestTokenStore.Check.MISSING);
        assertThat(store.check(GUEST, "  ")).isEqualTo(GuestTokenStore.Check.MISSING);
        verify(jdbc, never()).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));   // 어떤 SELECT 도 안 함
    }

    @Test
    @DisplayName("너무 짧거나 긴 토큰은 틀린 토큰")
    void tooShortOrLongTokenIsWrong() {
        // 짧은 토큰은 맞히기 쉽고, 아주 긴 토큰은 해시 계산·저장을 괴롭힌다. 둘 다 DB에 등록하기 전에 거른다
        assertThat(store.check(GUEST, "short")).isEqualTo(GuestTokenStore.Check.WRONG);
        assertThat(store.check(GUEST, "x".repeat(GuestTokenStore.MAX_TOKEN + 1))).isEqualTo(GuestTokenStore.Check.WRONG);
    }

    @Test
    @DisplayName("처음 온 토큰을 등록하고 기억한다")
    void firstTokenIsRegisteredThenRemembered() {
        // 처음 쓰는 사람이 주인이 된다. 등록 뒤에는 메모리에 기억해서 같은 날 요청마다 DB를 읽지 않아야 한다
        String hash = GuestTokenStore.sha256(TOKEN);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST))).thenReturn(null, hash);
        // 첫 SELECT 는 null(아직 등록 없음) → INSERT → 다시 SELECT 하면 방금 넣은 hash 가 나온다 (그래서 SELECT 는 2번)

        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);
        verify(jdbc).update(eq("INSERT IGNORE INTO guest_token (guest_id, token_hash, last_used_on) VALUES (?, ?, ?)"),
                eq(GUEST), eq(hash), eq(LocalDate.of(2026, 11, 23)));

        // 같은 날 다시 오면 DB를 읽지 않고 메모리로 확인
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);
        // 길이는 맞는(39글자) 다른 토큰이라 모양 검사가 아니라 해시 비교에서 WRONG 이 된다
        assertThat(store.check(GUEST, "another-token-somebody-else-made-xxxxxx")).isEqualTo(GuestTokenStore.Check.WRONG);
        verify(jdbc, times(2)).query(anyString(), any(ResultSetExtractor.class), eq(GUEST));   // 처음 등록할 때 2번뿐, 뒤 두 번은 메모리
    }

    @Test
    @DisplayName("다른 사람이 등록한 번호에 다른 토큰이면 틀린 토큰")
    void someoneElsesRegisteredTokenIsWrong() {
        // 게스트 번호를 알아낸 다른 사람이 자기 토큰을 보내도 이미 등록된 주인의 토큰과 다르면 막고, 덮어쓰지도 않는다
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST)))
                .thenReturn(GuestTokenStore.sha256("the-real-owners-token-0123456789-abcdef"));
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.WRONG);
        verify(jdbc, never()).update(anyString(), any(Object[].class));   // INSERT·UPDATE 아무것도 안 함 (주인 토큰을 바꾸지 않음)
    }

    @Test
    @DisplayName("마지막 사용 날짜는 하루에 한 번만 고친다")
    void lastUsedIsTouchedOncePerDay() {
        // last_used_on 은 1년 안 쓴 게스트 정리(RecordCleanup)가 본다. 요청마다 고치면 DB 쓰기가 너무 많아 하루 한 번만 고친다
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST))).thenReturn(GuestTokenStore.sha256(TOKEN));
        store.check(GUEST, TOKEN);
        store.check(GUEST, TOKEN);
        verify(jdbc, never()).update(eq("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?"), any(Object[].class));

        clock.now = clock.now.plusSeconds(86_400);   // 하루(24시간) 뒤 = 2026-11-24
        // 주의: 가짜 jdbc.update 는 정해 주지 않으면 0을 돌려주므로, 여기서는 "줄이 사라짐 → 다시 읽어 등록" 길로 간다.
        // 그래도 UPDATE 횟수(하루 1번)는 똑같이 확인된다
        store.check(GUEST, TOKEN);
        store.check(GUEST, TOKEN);
        verify(jdbc, times(1)).update("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?",
                LocalDate.of(2026, 11, 24), GUEST);
    }

    @Test
    @DisplayName("정리로 지워진 게스트가 돌아오면 다시 등록한다")
    void cleanedUpRowIsRegisteredAgain() {
        // 1년 안 쓴 게스트 정리(RecordCleanup)가 토큰 줄을 지웠는데 메모리에는 남아 있는 경우
        // 그대로 두면 표에는 줄이 없는데 메모리만 믿고 계속 통과시켜, 다음 정리 대상에서도 빠진다
        String hash = GuestTokenStore.sha256(TOKEN);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST))).thenReturn(hash, null, hash);
        // SELECT 차례: ① 첫날 이미 등록돼 있음(hash) ② 다음 날 다시 읽으니 지워져 없음(null) ③ 다시 넣은 뒤 읽음(hash)
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);

        clock.now = clock.now.plusSeconds(86_400);
        when(jdbc.update(eq("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?"), any(Object[].class)))
                .thenReturn(0);   // 바뀐 줄 0 = 토큰 줄이 사라졌다는 신호
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.OK);
        verify(jdbc).update("INSERT IGNORE INTO guest_token (guest_id, token_hash, last_used_on) VALUES (?, ?, ?)",
                GUEST, hash, LocalDate.of(2026, 11, 24));
    }

    @Test
    @DisplayName("DB에 문제가 있어도 막지 않는다")
    void dbTroubleDoesNotBlock() {
        // V37(guest_token 표)을 적용하기 전이거나 DB가 잠깐 안 될 때 모든 요청을 막으면 서비스 전체가 멈춘다.
        // UNAVAILABLE 이면 필터는 확인을 건너뛰고 통과시킨다
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(GUEST)))
                .thenThrow(new DataAccessResourceFailureException("Table 'guest_token' doesn't exist"));
        assertThat(store.check(GUEST, TOKEN)).isEqualTo(GuestTokenStore.Check.UNAVAILABLE);
    }

    @Test
    @DisplayName("SHA-256 해시는 16진수 64글자")
    void sha256IsHex64() {
        // "abc" 의 SHA-256 은 표준 문서에 나온 값이다. 이 값이 바뀌면 DB에 저장된 해시와 모두 어긋나 모든 사람이 WRONG 이 된다
        assertThat(GuestTokenStore.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
