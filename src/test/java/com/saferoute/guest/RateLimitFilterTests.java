package com.saferoute.guest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 요청 수 제한 (공개 배포 준비 1-2) */
class RateLimitFilterTests {

    private final GuestTokenStoreTests.MovableClock clock = new GuestTokenStoreTests.MovableClock();
    private final RateLimiter limiter = new RateLimiter(clock);

    private static RateLimitFilter.Rules rules(String name, int perGuest, int perIp, Duration w) {
        return new RateLimitFilter.Rules(List.of(new RateLimiter.Rule(name, perGuest, w)),
                List.of(new RateLimiter.Rule(name + "-ip", perIp, w)));
    }

    private final RateLimitFilter filter = new RateLimitFilter(limiter, true,
            rules("api", 3, 5, Duration.ofMinutes(1)),
            rules("assistant", 1, 10, Duration.ofMinutes(1)),
            rules("reports", 1, 10, Duration.ofHours(1)));

    private MockHttpServletResponse call(String method, String uri, String realIp, String guest) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr("172.18.0.5");   // 우리 프록시 컨테이너
        if (realIp != null) {
            req.addHeader("X-Real-IP", realIp);
        }
        if (guest != null) {
            req.setAttribute(GuestAuthFilter.ATTR_GUEST, guest);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void limiterCountsPerWindowAndResets() {
        RateLimiter.Rule r = new RateLimiter.Rule("t", 2, Duration.ofMinutes(1));
        clock.now = java.time.Instant.parse("2026-11-23T01:00:10Z");
        assertThat(limiter.hit(r, "a")).isZero();
        assertThat(limiter.hit(r, "a")).isZero();
        assertThat(limiter.hit(r, "a")).isEqualTo(50);     // 다음 분까지 50초
        assertThat(limiter.hit(r, "b")).isZero();          // 키마다 따로
        clock.now = clock.now.plusSeconds(50);
        assertThat(limiter.hit(r, "a")).isZero();          // 새 구간
    }

    @Test
    void limiterForgetsOldWindows() {
        RateLimiter.Rule r = new RateLimiter.Rule("t", 5, Duration.ofMinutes(1));
        limiter.hit(r, "a");
        limiter.hit(r, "b");
        limiter.clean(clock.millis() + 120_000);
        assertThat(limiter.size()).isZero();
    }

    @Test
    void blocksAfterLimitWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("GET", "/api/places", "203.0.113.7", null).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse res = call("GET", "/api/places", "203.0.113.7", null);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(res.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(res.getContentAsString()).contains("TOO_MANY_REQUESTS").contains("뒤에 다시 해 주세요");
        assertThat(call("GET", "/api/places", "198.51.100.9", null).getStatus()).isEqualTo(200);   // 다른 사람은 그대로
    }

    @Test
    void countsByGuestButIpCeilingStopsIdRotation() throws Exception {
        // 같은 IP에서 게스트 번호를 바꿔 가며 부르면 게스트 한도(3)에는 안 걸려도 IP 한도(5)에 걸린다
        for (int i = 0; i < 5; i++) {
            assertThat(call("GET", "/api/me", "203.0.113.7", "guest-" + i).getStatus()).isEqualTo(200);
        }
        assertThat(call("GET", "/api/me", "203.0.113.7", "guest-9").getStatus()).isEqualTo(429);
    }

    @Test
    void assistantHasItsOwnSmallerLimit() throws Exception {
        assertThat(call("POST", "/api/assistant", "203.0.113.7", "g1").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/assistant", "203.0.113.7", "g1").getStatus()).isEqualTo(429);
        assertThat(call("GET", "/api/places", "203.0.113.7", "g1").getStatus()).isEqualTo(200);   // 다른 창구는 아직 됨
    }

    @Test
    void reportsLimitIsPerHour() throws Exception {
        clock.now = java.time.Instant.parse("2026-11-23T01:30:00Z");
        assertThat(call("POST", "/api/reports", "203.0.113.7", "g1").getStatus()).isEqualTo(200);
        MockHttpServletResponse res = call("POST", "/api/reports", "203.0.113.7", "g1");
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentAsString()).contains("분 뒤에");
        assertThat(call("GET", "/api/reports", "203.0.113.7", "g1").getStatus()).isEqualTo(200);   // 목록 보기는 신고 한도와 따로
    }

    @Test
    void serverInternalCallsAreNotCounted() throws Exception {
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/places");
            req.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void nonApiPathsAreNotCounted() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("GET", "/s/abc", "203.0.113.7", null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void realIpIsTrustedOnlyFromOurProxy() {
        assertThat(RateLimitFilter.clientIp("172.18.0.5", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RateLimitFilter.clientIp("10.0.0.12", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RateLimitFilter.clientIp("198.51.100.1", "203.0.113.7")).isEqualTo("198.51.100.1");   // 바로 붙은 외부
        assertThat(RateLimitFilter.clientIp("172.18.0.5", null)).isEqualTo("172.18.0.5");
        assertThat(RateLimitFilter.isPrivateOrLoopback("fd12:3456::1")).isTrue();
        assertThat(RateLimitFilter.isPrivateOrLoopback("example.com")).isFalse();   // 이름은 DNS를 찾지 않음
    }

    @Test
    void waitTextIsReadable() {
        assertThat(RateLimitFilter.waitText(42)).isEqualTo("42초");
        assertThat(RateLimitFilter.waitText(61)).isEqualTo("2분");
        assertThat(RateLimitFilter.waitText(3600 * 5 + 1)).isEqualTo("6시간");
    }
}
