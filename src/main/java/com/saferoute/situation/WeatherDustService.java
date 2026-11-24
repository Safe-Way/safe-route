package com.saferoute.situation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.saferoute.air.DustJudge;
import com.saferoute.air.DustResult;
import com.saferoute.weather.WeatherJudge;
import com.saferoute.weather.WeatherResult;

/**
 * 출발점의 날씨(기상청)와 미세먼지(에어코리아)를 함께 판단한다.
 * <ul>
 *   <li>두 곳에 동시에 묻고, 기다리는 시간은 최대 1.5초. 늦거나 실패하면 그쪽만 "모름"(available=false)</li>
 *   <li>같은 동네(위도·경도 소수 둘째 자리, 약 1km)는 10분 동안 기억해 둔 값을 쓴다 (공공 API 호출 아끼기).
 *       둘 다 받았을 때만 기억한다</li>
 *   <li>출발·도착 좌표는 로그에 쓰지 않는다 (NFR-07)</li>
 * </ul>
 * 길 계산에는 쓰지 않는다. B 경로 엔진은 날씨를 보류했다 (docs/route-weights.md 6장).
 */
@Service
public class WeatherDustService {

    static final Duration WAIT = Duration.ofMillis(1500);
    static final Duration KEEP = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(WeatherDustService.class);

    private final WeatherJudge weather;
    private final DustJudge dust;
    private final Clock clock;
    private final ExecutorService pool;
    /** 이만큼 쌓이면 시간이 지난 기억을 지운다 (1km 칸 하나에 한 줄이라 광주 안이면 보통 넘지 않는다) */
    static final int MAX_SAVED = 500;

    private final Map<String, Saved> saved = new ConcurrentHashMap<>();

    @Autowired
    public WeatherDustService(WeatherJudge weather, DustJudge dust, Clock clock) {
        this(weather, dust, clock, Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "weather-dust");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 테스트용 입구: 일꾼 줄(pool)을 바꿔 끼운다 */
    WeatherDustService(WeatherJudge weather, DustJudge dust, Clock clock, ExecutorService pool) {
        this.weather = weather;
        this.dust = dust;
        this.clock = clock;
        this.pool = pool;
    }

    /** 미리 묻기 시작한다. 길 계산과 동시에 돌리고, 끝나면 {@link #await}로 받는다 */
    public CompletableFuture<WeatherDust> start(double lat, double lon) {
        String key = key(lat, lon);
        Saved s = saved.get(key);
        Instant now = clock.instant();
        if (s != null && now.isBefore(s.at().plus(KEEP))) {
            return CompletableFuture.completedFuture(s.value());
        }
        CompletableFuture<WeatherResult> w = CompletableFuture.supplyAsync(() -> weather.judgeNow(lat, lon), pool)
                .exceptionally(e -> null);
        CompletableFuture<DustResult> d = CompletableFuture.supplyAsync(() -> dust.judgeAt(lat, lon), pool)
                .exceptionally(e -> null);
        return w.thenCombine(d, (wr, dr) -> {
            WeatherDust v = combine(wr, dr);
            if (v.weatherAvailable() && v.dustAvailable()) { // 하나라도 못 받았으면 다음에 다시 묻는다
                Instant at = clock.instant();
                forgetOld(at);
                saved.put(key, new Saved(v, at));
            }
            return v;
        }).orTimeout(WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.warn("날씨·미세먼지를 제때 못 받았어요: {}", e.getClass().getSimpleName());
                    return WeatherDust.unknown();
                });
    }

    /** start로 시작한 결과를 받는다. 무슨 일이 있어도 예외를 던지지 않는다 */
    public WeatherDust await(CompletableFuture<WeatherDust> f) {
        if (f == null) {
            return WeatherDust.unknown();
        }
        try {
            return f.join();
        } catch (RuntimeException e) {
            return WeatherDust.unknown();
        }
    }

    /** 바로 묻고 받는다 */
    public WeatherDust judge(double lat, double lon) {
        return await(start(lat, lon));
    }

    static WeatherDust combine(WeatherResult w, DustResult d) {
        boolean wOk = w != null && w.available();
        boolean dOk = d != null && d.available();
        return new WeatherDust(wOk && w.rain(), wOk && w.heat(), wOk && w.cold(), wOk ? w.tempC() : null, wOk,
                dOk && d.dustBad(), dOk);
    }

    /** 기억이 많이 쌓이면 시간이 지난 것을 지운다. 예전에는 지우지 않아 서버가 오래 켜져 있으면 계속 늘었다 */
    private void forgetOld(Instant now) {
        if (saved.size() < MAX_SAVED) {
            return;
        }
        saved.values().removeIf(s -> !now.isBefore(s.at().plus(KEEP)));
    }

    /** 테스트용: 지금 기억하고 있는 칸 수 */
    int savedCount() {
        return saved.size();
    }

    /** 약 1km 칸으로 묶는다 */
    static String key(double lat, double lon) {
        return Math.round(lat * 100) + "," + Math.round(lon * 100);
    }

    private record Saved(WeatherDust value, Instant at) {
    }
}
