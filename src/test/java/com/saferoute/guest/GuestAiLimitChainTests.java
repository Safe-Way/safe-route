package com.saferoute.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 앱 #191 모양 그대로: AI 요청(/api/assistant, /api/ai/*)에 X-Guest-Id·X-Guest-Token 헤더만 붙여 보낸다 (2026-11-27).
 * 무엇을: 본인 확인 필터(GuestAuthFilter) → 요청 수 제한 필터(RateLimitFilter)를 서버와 같은 순서로 이어 붙여,
 *        헤더로 온 번호로 사람마다 따로 세는지 본다. 토큰 확인을 끈 때(enforce=false)·켠 때(true) 둘 다.
 * 어떻게: 토큰 저장소는 Mockito 가짜, 한도는 시험용으로 작게(게스트 2번·IP 10번). 모든 폰은 같은 와이파이 IP.
 * 왜: 두 필터는 따로따로 시험하고 있었다(앞 필터가 번호를 요청 속성에 넣었다고 가정).
 *     이어 붙였을 때 번호가 안 넘어가면 같은 와이파이의 폰들이 IP 한도 하나를 나눠 쓰거나, 한 사람이 IP 한도까지 부를 수 있다.
 */
@DisplayName("AI 요청 헤더(번호+토큰)로 사람마다 따로 세기")
class GuestAiLimitChainTests {

    static final String PHONE_A = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    static final String PHONE_B = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d";
    /** 앱이 처음 한 번 만드는 64자리 무작위 토큰 (32바이트 → 16진수) */
    static final String TOKEN_A = "a".repeat(32) + "0123456789abcdef0123456789abcdef";
    static final String TOKEN_B = "b".repeat(32) + "fedcba9876543210fedcba9876543210";
    static final String WIFI = "203.0.113.7";

    private final GuestTokenStore tokens = mock(GuestTokenStore.class);
    private final RateLimitFilter limit = new RateLimitFilter(
            new RateLimiter(new GuestTokenStoreTests.MovableClock()), true,
            new RateLimitFilter.Rules(List.of(new RateLimiter.Rule("api", 100, Duration.ofMinutes(1))),
                    List.of(new RateLimiter.Rule("api-ip", 100, Duration.ofMinutes(1)))),
            new RateLimitFilter.Rules(List.of(new RateLimiter.Rule("assistant", 2, Duration.ofMinutes(1))),
                    List.of(new RateLimiter.Rule("assistant-ip", 10, Duration.ofMinutes(1)))),
            new RateLimitFilter.Rules(List.of(new RateLimiter.Rule("reports", 1, Duration.ofHours(1))),
                    List.of(new RateLimiter.Rule("reports-ip", 10, Duration.ofHours(1)))));

    /** 앱처럼 JSON 본문엔 번호 없이 헤더로만 번호·토큰을 보낸다. 두 필터를 서버와 같은 순서로 지난다 */
    private int call(boolean enforce, String uri, String guestId, String token) throws Exception {
        GuestAuthFilter auth = new GuestAuthFilter(tokens, enforce);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr("172.18.0.5");   // 우리 프록시 컨테이너
        req.addHeader("X-Real-IP", WIFI);
        req.setContentType("application/json");
        req.setContent("{\"text\":\"근처 화장실\"}".getBytes(StandardCharsets.UTF_8));
        if (guestId != null) {
            req.addHeader(GuestAuthFilter.HEADER_ID, guestId);
        }
        if (token != null) {
            req.addHeader(GuestAuthFilter.HEADER_TOKEN, token);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        auth.doFilter(req, res, (rq, rs) -> limit.doFilter(rq, rs, new MockFilterChain()));
        return res.getStatus();
    }

    @Test
    @DisplayName("토큰 확인을 끈 지금: 헤더 번호마다 따로 센다")
    void countsPerGuestWhileEnforceIsOff() throws Exception {
        when(tokens.check(PHONE_A, TOKEN_A)).thenReturn(GuestTokenStore.Check.OK);
        when(tokens.check(PHONE_B, TOKEN_B)).thenReturn(GuestTokenStore.Check.OK);
        // 폰 A: 비서·앱 화면 AI 합쳐 2번까지, 3번째는 429
        assertThat(call(false, "/api/assistant", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(false, "/api/ai/map-chat", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(false, "/api/ai/setup-chat", PHONE_A, TOKEN_A)).isEqualTo(429);
        // 같은 와이파이의 폰 B 는 A 와 상관없이 자기 몫 2번
        assertThat(call(false, "/api/ai/pick-answer", PHONE_B, TOKEN_B)).isEqualTo(200);
        assertThat(call(false, "/api/assistant", PHONE_B, TOKEN_B)).isEqualTo(200);
        assertThat(call(false, "/api/assistant", PHONE_B, TOKEN_B)).isEqualTo(429);
    }

    @Test
    @DisplayName("토큰 확인을 켠 뒤에도 사람마다 따로 세고, 토큰이 없거나 틀리면 세기 전에 막는다")
    void countsPerGuestWhenEnforced() throws Exception {
        when(tokens.check(PHONE_A, TOKEN_A)).thenReturn(GuestTokenStore.Check.OK);
        when(tokens.check(PHONE_B, TOKEN_B)).thenReturn(GuestTokenStore.Check.OK);
        when(tokens.check(PHONE_B, null)).thenReturn(GuestTokenStore.Check.MISSING);
        when(tokens.check(PHONE_B, TOKEN_A)).thenReturn(GuestTokenStore.Check.WRONG);
        assertThat(call(true, "/api/ai/map-chat", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(true, "/api/ai/map-chat", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(true, "/api/ai/map-chat", PHONE_A, TOKEN_A)).isEqualTo(429);
        // 토큰이 없거나 남의 토큰이면 401. 이때는 B 의 몫을 쓰지 않는다
        assertThat(call(true, "/api/ai/map-chat", PHONE_B, null)).isEqualTo(401);
        assertThat(call(true, "/api/ai/map-chat", PHONE_B, TOKEN_A)).isEqualTo(401);
        assertThat(call(true, "/api/ai/map-chat", PHONE_B, TOKEN_B)).isEqualTo(200);
        assertThat(call(true, "/api/ai/map-chat", PHONE_B, TOKEN_B)).isEqualTo(200);
    }

    @Test
    @DisplayName("토큰 표를 못 읽어도(확인 못 함) 번호로는 센다")
    void countsPerGuestWhenTokenTableUnavailable() throws Exception {
        when(tokens.check(PHONE_A, TOKEN_A)).thenReturn(GuestTokenStore.Check.UNAVAILABLE);
        assertThat(call(true, "/api/assistant", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(true, "/api/assistant", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(true, "/api/assistant", PHONE_A, TOKEN_A)).isEqualTo(429);
    }

    @Test
    @DisplayName("번호를 대문자로 보내도 같은 사람으로 센다")
    void upperCaseIdIsSamePerson() throws Exception {
        when(tokens.check(PHONE_A, TOKEN_A)).thenReturn(GuestTokenStore.Check.OK);
        assertThat(call(false, "/api/ai/map-chat", PHONE_A, TOKEN_A)).isEqualTo(200);
        assertThat(call(false, "/api/ai/map-chat", PHONE_A.toUpperCase(), TOKEN_A)).isEqualTo(200);
        assertThat(call(false, "/api/ai/map-chat", PHONE_A, TOKEN_A)).isEqualTo(429);
    }

    @Test
    @DisplayName("64자리 토큰은 길이 검사를 지나 등록되고, 다른 토큰은 틀린 토큰")
    @SuppressWarnings("unchecked")
    void sixtyFourCharTokenIsAccepted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 처음엔 없다가(null) 등록한 뒤 다시 읽으면 그 해시
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(PHONE_A)))
                .thenReturn(null, GuestTokenStore.sha256(TOKEN_A));
        GuestTokenStore store = new GuestTokenStore(jdbc, new GuestTokenStoreTests.MovableClock());
        assertThat(TOKEN_A).hasSize(64);
        assertThat(store.check(PHONE_A, TOKEN_A)).isEqualTo(GuestTokenStore.Check.OK);
        assertThat(store.check(PHONE_A, TOKEN_B)).isEqualTo(GuestTokenStore.Check.WRONG);
    }
}
