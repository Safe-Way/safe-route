package com.saferoute.guest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * /api 요청 수 제한 (2026-11-23, 공개 배포 준비 1-2).
 *
 * <p>비서(제미나이 비용)와 사진 신고(요청 하나 16MB까지)를 누가 끝없이 부르지 못하게 한다.
 * 게스트 번호(본인 확인 필터가 찾은 것)마다 세고, 번호를 바꿔 가며 부르는 것을 막으려고 IP마다 넉넉한 상한도 둔다.
 * 넘으면 429와 Retry-After(초)를 돌려준다.
 *
 * <p>IP는 학교 nginx가 넘긴 X-Real-IP를 쓴다. 단, 바로 앞이 우리 프록시(사설·루프백 주소)일 때만 믿는다.
 * 서버 안(루프백)에서 X-Real-IP 없이 부르는 요청(시험·점검)은 세지 않는다.
 */
@Component
@Order(RateLimitFilter.ORDER)
public class RateLimitFilter extends OncePerRequestFilter {

    public static final int ORDER = GuestAuthFilter.ORDER + 10;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 한 요청에 적용할 규칙: 게스트 번호로 셀 것, IP로 셀 것 */
    record Rules(List<RateLimiter.Rule> perGuest, List<RateLimiter.Rule> perIp) {
    }

    private final RateLimiter limiter;
    private final boolean enabled;
    private final Rules general;
    private final Rules assistant;
    private final Rules reports;

    @Autowired
    public RateLimitFilter(@Value("${rate-limit.enabled:true}") boolean enabled,
                           @Value("${rate-limit.per-minute:120}") int perMinute,
                           @Value("${rate-limit.ip-per-minute:600}") int ipPerMinute,
                           @Value("${rate-limit.assistant-per-minute:20}") int assistantPerMinute,
                           @Value("${rate-limit.assistant-per-day:300}") int assistantPerDay,
                           @Value("${rate-limit.assistant-ip-per-minute:60}") int assistantIpPerMinute,
                           @Value("${rate-limit.reports-per-hour:10}") int reportsPerHour,
                           @Value("${rate-limit.reports-ip-per-hour:30}") int reportsIpPerHour) {
        this(new RateLimiter(Clock.systemUTC()), enabled,
                new Rules(List.of(new RateLimiter.Rule("api", perMinute, Duration.ofMinutes(1))),
                        List.of(new RateLimiter.Rule("api-ip", ipPerMinute, Duration.ofMinutes(1)))),
                new Rules(List.of(new RateLimiter.Rule("assistant", assistantPerMinute, Duration.ofMinutes(1)),
                        new RateLimiter.Rule("assistant-day", assistantPerDay, Duration.ofDays(1))),
                        List.of(new RateLimiter.Rule("assistant-ip", assistantIpPerMinute, Duration.ofMinutes(1)))),
                new Rules(List.of(new RateLimiter.Rule("reports", reportsPerHour, Duration.ofHours(1))),
                        List.of(new RateLimiter.Rule("reports-ip", reportsIpPerHour, Duration.ofHours(1)))));
    }

    RateLimitFilter(RateLimiter limiter, boolean enabled, Rules general, Rules assistant, Rules reports) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.general = general;
        this.assistant = assistant;
        this.reports = reports;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String realIp = req.getHeader("X-Real-IP");
        String remote = req.getRemoteAddr();
        if ((realIp == null || realIp.isBlank()) && isLoopback(remote)) {
            chain.doFilter(req, res);   // 서버 안에서 부름 (시험·점검)
            return;
        }
        String ip = clientIp(remote, realIp);
        Object g = req.getAttribute(GuestAuthFilter.ATTR_GUEST);
        String guest = g instanceof String s ? s : null;

        List<Rules> apply = new ArrayList<>(2);
        apply.add(general);
        String uri = req.getRequestURI();
        if ("POST".equals(req.getMethod())) {
            if (uri.equals("/api/assistant") || uri.startsWith("/api/assistant/")) {
                apply.add(assistant);
            } else if (uri.equals("/api/reports")) {
                apply.add(reports);
            }
        }
        for (Rules rules : apply) {
            for (RateLimiter.Rule r : rules.perGuest()) {
                long wait = limiter.hit(r, guest != null ? "g:" + guest : "ip:" + ip);
                if (wait > 0) {
                    reject(res, r, guest != null ? guest.substring(0, Math.min(8, guest.length())) + "…" : ip, wait);
                    return;
                }
            }
            for (RateLimiter.Rule r : rules.perIp()) {
                long wait = limiter.hit(r, ip);
                if (wait > 0) {
                    reject(res, r, ip, wait);
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    private static void reject(HttpServletResponse res, RateLimiter.Rule r, String who, long wait) throws IOException {
        log.info("요청이 많아 잠시 막음: 규칙 {} ({}번/{}), {}", r.name(), r.limit(), r.window(), who);
        res.setHeader("Retry-After", Long.toString(wait));
        GuestAuthFilter.write(res, 429, "TOO_MANY_REQUESTS", "요청이 너무 많아요. " + waitText(wait) + " 뒤에 다시 해 주세요");
    }

    static String waitText(long seconds) {
        if (seconds < 60) {
            return seconds + "초";
        }
        if (seconds < 3600) {
            return (seconds + 59) / 60 + "분";
        }
        return (seconds + 3599) / 3600 + "시간";
    }

    /** 바로 앞이 우리 프록시(사설·루프백)일 때만 X-Real-IP 를 믿는다 */
    static String clientIp(String remote, String realIp) {
        if (realIp != null && !realIp.isBlank() && isPrivateOrLoopback(remote)) {
            return realIp.strip();
        }
        return remote;
    }

    static boolean isLoopback(String addr) {
        InetAddress a = literal(addr);
        return a != null && a.isLoopbackAddress();
    }

    static boolean isPrivateOrLoopback(String addr) {
        InetAddress a = literal(addr);
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;   // IPv6 사설 fc00::/7
    }

    /** 숫자 주소만 바꾼다 (이름이면 DNS를 찾지 않고 null) */
    private static InetAddress literal(String addr) {
        if (addr == null || addr.isBlank() || !addr.matches("[0-9a-fA-F:.%]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(addr);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
