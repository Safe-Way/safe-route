package com.saferoute.guest;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 고정 구간 요청 수 세기 (2026-11-23, 공개 배포 준비 1-2).
 * 규칙마다 구간(1분·1시간·하루)을 나눠 키(게스트 번호 또는 IP)별로 센다. 서버 한 대 안에서만 센다
 * (프록시가 같은 IP를 늘 같은 서버로 보내므로 거의 맞고, 두 대를 합치면 최대 두 배까지 갈 수 있다).
 */
public final class RateLimiter {

    /** @param name 규칙 이름 (로그·키에 씀), @param limit 구간 안 최대 수, @param window 구간 길이 */
    public record Rule(String name, int limit, Duration window) {
        public Rule {
            if (limit < 1 || window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("규칙 " + name + ": limit 은 1 이상, window 는 0보다 커야 해요");
            }
        }
    }

    private record Window(long start, int count) {
    }

    /** 이만큼 셀 때마다 끝난 구간을 지운다 */
    static final int CLEAN_EVERY = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> counts = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private volatile long longestWindowMs = 60_000;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 한 번 센다.
     *
     * @return 0 이면 통과. 넘었으면 다음 구간까지 남은 초 (1 이상)
     */
    public long hit(Rule rule, String key) {
        long now = clock.millis();
        long w = rule.window().toMillis();
        if (w > longestWindowMs) {
            longestWindowMs = w;
        }
        long start = Math.floorDiv(now, w) * w;
        Window win = counts.compute(rule.name() + '|' + key,
                (k, old) -> old == null || old.start() != start ? new Window(start, 1) : new Window(start, old.count() + 1));
        if (hits.incrementAndGet() % CLEAN_EVERY == 0) {
            clean(now);
        }
        if (win.count() <= rule.limit()) {
            return 0;
        }
        return Math.max(1, (start + w - now + 999) / 1000);
    }

    /** 가장 긴 구간보다 오래된 칸을 지운다 */
    void clean(long now) {
        long keep = longestWindowMs;
        counts.entrySet().removeIf(e -> now - e.getValue().start() > keep);
    }

    int size() {
        return counts.size();
    }
}
