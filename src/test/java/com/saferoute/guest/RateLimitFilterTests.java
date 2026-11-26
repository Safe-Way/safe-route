package com.saferoute.guest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 요청 수 제한 (공개 배포 준비 1-2).
 * 무엇을: RateLimiter(구간마다 세기)와 RateLimitFilter(어느 창구에 어떤 한도를, 게스트·IP 중 무엇으로 셀지)를 본다.
 * 어떻게: 시계는 GuestTokenStoreTests.MovableClock 으로 바꾸고, 한도는 시험용으로 아주 작게(3·5·1) 잡아
 *        Mock 서블릿 요청을 몇 번만 보내도 429가 나게 한다. DB·스프링은 띄우지 않는다.
 * 왜: 깨지면 누가 비서(제미나이 비용)나 사진 신고(요청 하나 16MB)를 끝없이 불러 돈·서버가 버티지 못하거나,
 *     반대로 프록시 IP 하나로 모두를 묶어 세서 멀쩡한 사용자들이 함께 막힌다.
 */
@DisplayName("요청 수 제한")
class RateLimitFilterTests {

    private final GuestTokenStoreTests.MovableClock clock = new GuestTokenStoreTests.MovableClock();
    private final RateLimiter limiter = new RateLimiter(clock);

    /** 한 창구의 규칙 묶음: 게스트별 perGuest 번, IP별 perIp 번 (구간 w). IP 규칙 이름은 뒤에 "-ip" */
    private static RateLimitFilter.Rules rules(String name, int perGuest, int perIp, Duration w) {
        return new RateLimitFilter.Rules(List.of(new RateLimiter.Rule(name, perGuest, w)),
                List.of(new RateLimiter.Rule(name + "-ip", perIp, w)));
    }

    // 시험용 작은 한도: 일반 /api 게스트 3·IP 5 (1분), 비서 게스트 1 (1분), 신고 게스트 1 (1시간)
    private final RateLimitFilter filter = new RateLimitFilter(limiter, true,
            rules("api", 3, 5, Duration.ofMinutes(1)),
            rules("assistant", 1, 10, Duration.ofMinutes(1)),
            rules("reports", 1, 10, Duration.ofHours(1)));

    /** 학교 nginx → 우리 프록시를 거쳐 온 요청처럼 보낸다. guest 는 앞 필터(GuestAuthFilter)가 찾아 둔 번호 자리 */
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
    @DisplayName("시간 칸마다 세고 칸이 바뀌면 다시 센다")
    void limiterCountsPerWindowAndResets() {
        // 고정 구간 방식: 1분 칸(01:00:00~01:01:00) 안에서 세고, 다음 칸이 되면 0부터 다시 센다.
        // 10초에 시작해서 "다음 칸까지 남은 초"가 50으로 딱 떨어지게 했다
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
    @DisplayName("지난 시간 칸은 잊어서 메모리가 늘지 않는다")
    void limiterForgetsOldWindows() {
        // 지난 칸을 안 지우면 IP·게스트 수만큼 메모리가 계속 늘어 서버가 결국 멈춘다
        RateLimiter.Rule r = new RateLimiter.Rule("t", 5, Duration.ofMinutes(1));
        limiter.hit(r, "a");
        limiter.hit(r, "b");
        limiter.clean(clock.millis() + 120_000);   // 2분 뒤: 가장 긴 구간(1분)보다 오래된 칸이 된다
        assertThat(limiter.size()).isZero();
    }

    @Test
    @DisplayName("한도를 넘으면 429와 다시 시도할 시각을 준다")
    void blocksAfterLimitWith429AndRetryAfter() throws Exception {
        // 게스트 번호가 없는 요청은 IP로 센다. 일반 게스트 한도 3번까지는 통과, 4번째에 429
        // 앱은 Retry-After(초)를 보고 기다렸다 다시 부르므로 1~60초 안의 값이어야 한다
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
    @DisplayName("게스트별로 세되, 번호를 바꿔 가며 피하면 IP 한도에서 막는다")
    void countsByGuestButIpCeilingStopsIdRotation() throws Exception {
        // 같은 IP에서 게스트 번호를 바꿔 가며 부르면 게스트 한도(3)에는 안 걸려도 IP 한도(5)에 걸린다
        for (int i = 0; i < 5; i++) {
            assertThat(call("GET", "/api/me", "203.0.113.7", "guest-" + i).getStatus()).isEqualTo(200);
        }
        assertThat(call("GET", "/api/me", "203.0.113.7", "guest-9").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("비서는 따로 더 작은 한도")
    void assistantHasItsOwnSmallerLimit() throws Exception {
        // 비서 POST 는 일반 한도에 더해 비서 한도(게스트 1번)도 센다. 비서 한도에 걸려도 일반 창구는 막지 않아야 한다
        assertThat(call("POST", "/api/assistant", "203.0.113.7", "g1").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/assistant", "203.0.113.7", "g1").getStatus()).isEqualTo(429);
        assertThat(call("GET", "/api/places", "203.0.113.7", "g1").getStatus()).isEqualTo(200);   // 다른 창구는 아직 됨
    }

    @Test
    @DisplayName("신고 한도는 시간당")
    void reportsLimitIsPerHour() throws Exception {
        // 1시간 칸의 한가운데(01:30)라 남은 시간이 30분 → 안내 문구가 "1시간"이 아니라 "30분 뒤에"가 된다
        clock.now = java.time.Instant.parse("2026-11-23T01:30:00Z");
        assertThat(call("POST", "/api/reports", "203.0.113.7", "g1").getStatus()).isEqualTo(200);
        MockHttpServletResponse res = call("POST", "/api/reports", "203.0.113.7", "g1");
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentAsString()).contains("분 뒤에");
        assertThat(call("GET", "/api/reports", "203.0.113.7", "g1").getStatus()).isEqualTo(200);   // 목록 보기는 신고 한도와 따로
    }

    @Test
    @DisplayName("서버 안에서 부른 요청은 세지 않는다")
    void serverInternalCallsAreNotCounted() throws Exception {
        // 서버 안 점검·시험은 X-Real-IP 없이 루프백(127.0.0.1)으로 온다. 한도(3·5)보다 훨씬 많은 20번을 불러도 막지 않는다
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/places");
            req.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("/api가 아닌 주소는 세지 않는다")
    void nonApiPathsAreNotCounted() throws Exception {
        // /s/... 는 보호자가 여는 위치 공유 웹 화면이다. 한도는 /api 에만 걸고 화면·정적 파일은 세지 않는다
        for (int i = 0; i < 20; i++) {
            assertThat(call("GET", "/s/abc", "203.0.113.7", null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("실제 IP 헤더는 우리 프록시에서 온 것만 믿는다")
    void realIpIsTrustedOnlyFromOurProxy() {
        // X-Real-IP 는 누구나 적어 보낼 수 있다. 바로 앞이 사설·루프백(우리 프록시)일 때만 믿어야
        // 밖에서 헤더를 바꿔 가며 IP 한도를 피하지 못한다
        assertThat(RateLimitFilter.clientIp("172.18.0.5", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RateLimitFilter.clientIp("10.0.0.12", "203.0.113.7")).isEqualTo("203.0.113.7");   // 다른 사설 대역도 프록시로 봄
        assertThat(RateLimitFilter.clientIp("198.51.100.1", "203.0.113.7")).isEqualTo("198.51.100.1");   // 바로 붙은 외부
        assertThat(RateLimitFilter.clientIp("172.18.0.5", null)).isEqualTo("172.18.0.5");   // 헤더가 없으면 바로 앞 주소
        assertThat(RateLimitFilter.isPrivateOrLoopback("fd12:3456::1")).isTrue();   // IPv6 사설(fc00::/7)
        assertThat(RateLimitFilter.isPrivateOrLoopback("example.com")).isFalse();   // 이름은 DNS를 찾지 않음
    }

    @Test
    @DisplayName("기다릴 시간 안내 문구가 읽기 쉽다")
    void waitTextIsReadable() {
        // 1분·1시간을 넘으면 올림해서 보여 준다 (61초 → 2분). 내림하면 안내보다 일찍 다시 불러 또 막힌다
        assertThat(RateLimitFilter.waitText(42)).isEqualTo("42초");
        assertThat(RateLimitFilter.waitText(61)).isEqualTo("2분");
        assertThat(RateLimitFilter.waitText(3600 * 5 + 1)).isEqualTo("6시간");   // 5시간 1초 → 올림 6시간
    }
}
