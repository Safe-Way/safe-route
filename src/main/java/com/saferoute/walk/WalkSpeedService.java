package com.saferoute.walk;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.saferoute.route.RouteProperties;

/**
 * 개인 보행 속도 (FR-52, 2026-11-05 팀 결정).
 * 쓰는 속도 = (모드 기본 속도 × 5 + 남아 있는 측정 속도의 합) ÷ (5 + 측정 수).
 * 기본 속도는 route.yml 의 walk-speed-m-per-min 을 그대로 쓴다 (따로 적지 않음).
 * 게스트 번호는 사람을 알아볼 수 없는 무작위 값이지만 그래도 로그에 남기지 않는다.
 */
@Service
public class WalkSpeedService {

    static final int MIN_DISTANCE_M = 300;
    static final double MIN_SPEED = 20;
    static final double MAX_SPEED = 150;
    static final int BASE_WEIGHT = 5;
    static final int KEEP_SAMPLES = 20;
    static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final WalkSpeedRepository repo;
    private final ToDoubleFunction<String> baseSpeed;
    private final Clock clock;

    @Autowired
    public WalkSpeedService(WalkSpeedRepository repo, RouteProperties route, Clock clock) {
        this(repo, mode -> route.profile(mode).walkSpeedMPerMin(), clock);
    }

    WalkSpeedService(WalkSpeedRepository repo, ToDoubleFunction<String> baseSpeed, Clock clock) {
        this.repo = repo;
        this.baseSpeed = baseSpeed;
        this.clock = clock;
    }

    /** 한 번 잰 값을 받아 저장하고, 새로 계산한 지금 속도를 돌려준다 */
    @Transactional
    public WalkSpeed add(WalkSample s) {
        if (s == null) {
            throw WalkSpeedException.bad("요청 내용이 비어 있어요");
        }
        String guest = guest(s.guestId());
        String mode = mode(s.mode());
        if (s.distanceM() == null || s.distanceM() < MIN_DISTANCE_M) {
            throw WalkSpeedException.bad("distanceM 은 " + MIN_DISTANCE_M + "m 이상이어야 해요");
        }
        if (s.movingS() == null || s.movingS() <= 0) {
            throw WalkSpeedException.bad("movingS 는 1초 이상이어야 해요");
        }
        double raw = s.distanceM() * 60.0 / s.movingS();
        if (raw < MIN_SPEED || raw > MAX_SPEED) {
            throw WalkSpeedException.bad("분당 " + (int) MIN_SPEED + "~" + (int) MAX_SPEED
                    + "m 밖이라 받지 않아요 (버스·자전거를 탔거나 GPS가 튄 것으로 봐요)");
        }
        double base = baseSpeed.applyAsDouble(mode);

        repo.lockProfile(guest, mode);
        repo.insertSample(guest, mode, s.distanceM(), s.movingS(), round1(raw), LocalDate.now(clock));
        repo.trimSamples(guest, mode, KEEP_SAMPLES);
        WalkSpeedRepository.SampleSum sum = repo.sampleSum(guest, mode);
        double speed = blend(base, sum.sum(), sum.count());
        repo.updateProfile(guest, mode, speed, s.distanceM(), s.movingS());
        WalkSpeedRepository.Profile p = repo.find(guest, mode);
        int samples = p == null ? sum.count() : p.samples();
        return new WalkSpeed(mode, speed, base, samples, true);
    }

    /** 지금 쓰는 속도. 잰 적이 없으면 모드 기본 속도 (personal=false) */
    public WalkSpeed get(String guestId, String modeName) {
        String guest = guest(guestId);
        String mode = mode(modeName);
        double base = baseSpeed.applyAsDouble(mode);
        WalkSpeedRepository.Profile p = repo.find(guest, mode);
        if (p == null || p.speedMMin() == null) {
            return new WalkSpeed(mode, base, base, p == null ? 0 : p.samples(), false);
        }
        return new WalkSpeed(mode, p.speedMMin(), base, p.samples(), true);
    }

    /** 내 기록 지우기 (모든 모드). 지운 모드 수 */
    @Transactional
    public int delete(String guestId) {
        return repo.deleteGuest(guest(guestId));
    }

    /** (기본 × 5 + 측정 합) ÷ (5 + 측정 수), 소수 첫째 자리 */
    static double blend(double base, double sum, int count) {
        return round1((base * BASE_WEIGHT + sum) / (BASE_WEIGHT + count));
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String guest(String id) {
        if (id == null || !UUID.matcher(id.trim()).matches()) {
            throw WalkSpeedException.bad("guestId 는 앱이 만든 UUID(8-4-4-4-12)여야 해요");
        }
        return id.trim().toLowerCase();
    }

    /** 비면 adult (길찾기와 같음) */
    private static String mode(String m) {
        String key = m == null || m.isBlank() ? "adult" : m.trim().toLowerCase();
        if (!MODES.contains(key)) {
            throw WalkSpeedException.bad("mode 는 adult, child, senior, wheelchair 중 하나여야 해요");
        }
        return key;
    }
}
