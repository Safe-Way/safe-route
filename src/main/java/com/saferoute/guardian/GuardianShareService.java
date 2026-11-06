package com.saferoute.guardian;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자 실시간 위치 보기 (029, 교수님 피드백 11/06).
 * 시작 → 무작위 32자 공유 번호와 보호자 링크. 걷는 동안 10초마다 위치(마지막 한 점만 덮어씀).
 * 끝: 도착·그만둠·SOS, 시작 3시간 뒤, 또는 30분 동안 위치가 안 오면. 끝나면 위치를 비우고, 10분 뒤 줄을 지운다.
 */
@Service
public class GuardianShareService {

    static final Duration LIFETIME = Duration.ofHours(3);
    static final Duration SILENT_LIMIT = Duration.ofMinutes(30);
    static final Duration KEEP_AFTER_END = Duration.ofMinutes(10);
    /** 가는 곳에서 이만큼 가까워지면 저절로 도착으로 끝낸다 */
    static final int ARRIVE_M = 25;

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]{32}");
    private static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");
    private static final Set<String> END_REASONS = Set.of("arrived", "stopped", "sos");
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final GuardianShareRepository repo;
    private final Clock clock;
    private final String publicUrl;
    private final SecureRandom random = new SecureRandom();

    public GuardianShareService(GuardianShareRepository repo, Clock clock,
                                @Value("${guardian.public-url:https://safe-route.java21.net}") String publicUrl) {
        this.repo = repo;
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @Transactional
    public GuardianRequests.Started start(GuardianRequests.Start in) {
        if (in == null) {
            throw GuardianException.bad("보낼 내용이 없어요");
        }
        String guest = null;
        if (in.guestId() != null && !in.guestId().isBlank()) {
            if (!UUID.matcher(in.guestId().strip()).matches()) {
                throw GuardianException.bad("게스트 번호 모양이 틀렸어요");
            }
            guest = in.guestId().strip().toLowerCase();
        }
        String mode = in.mode() == null || in.mode().isBlank() ? null : in.mode().strip().toLowerCase();
        if (mode != null && !MODES.contains(mode)) {
            throw GuardianException.bad("모드는 adult·child·senior·wheelchair 중 하나예요");
        }
        String name = text(in.walkerName(), 30, "이름");
        String dest = text(in.destName(), 100, "가는 곳 이름");
        checkPoint(in.destLat(), in.destLon(), true);
        checkPoint(in.lat(), in.lon(), true);

        LocalDateTime now = LocalDateTime.now(clock);
        boolean located = in.lat() != null && in.lon() != null;
        Integer remain = located ? remain(in.lat(), in.lon(), in.destLat(), in.destLon()) : null;
        String token = newToken();
        repo.insert(new GuardianShare(token, guest, name, dest, in.destLat(), in.destLon(), mode, now,
                now.plus(LIFETIME), located ? in.lat() : null, located ? in.lon() : null, located ? now : null,
                remain, null, false, null, null));
        if (guest != null) {
            repo.endOthers(guest, token, now);
        }
        return new GuardianRequests.Started(token, publicUrl + "/s/" + token, ISO.format(now.plus(LIFETIME)));
    }

    /** 10초마다 위치 올리기. 가는 곳 25m 안이면 저절로 도착으로 끝낸다. 돌려주는 값: 지금 상태 */
    @Transactional
    public String move(String token, GuardianRequests.Location in) {
        GuardianShare s = live(token);
        if (in == null || in.lat() == null || in.lon() == null) {
            throw GuardianException.bad("위치(lat, lon)가 필요해요");
        }
        checkPoint(in.lat(), in.lon(), false);
        if (in.remainM() != null && (in.remainM() < 0 || in.remainM() > 200_000)) {
            throw GuardianException.bad("남은 거리가 이상해요");
        }
        if (in.etaMin() != null && (in.etaMin() < 0 || in.etaMin() > 600)) {
            throw GuardianException.bad("남은 시간이 이상해요");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Integer remain = in.remainM() != null ? in.remainM() : remain(in.lat(), in.lon(), s.destLat(), s.destLon());
        if (remain != null && remain <= ARRIVE_M) {
            repo.end(token, "arrived", now);
            return "arrived";
        }
        if (repo.move(token, in.lat(), in.lon(), now, remain, in.etaMin(), Boolean.TRUE.equals(in.offRoute())) == 0) {
            throw GuardianException.ended();
        }
        return "walking";
    }

    @Transactional
    public void end(String token, GuardianRequests.End in) {
        String reason = in == null || in.reason() == null ? "stopped" : in.reason().strip().toLowerCase();
        if (!END_REASONS.contains(reason)) {
            throw GuardianException.bad("끝내는 이유는 arrived·stopped·sos 중 하나예요");
        }
        GuardianShare s = find(token);
        if (!s.ended()) {
            repo.end(token, reason, LocalDateTime.now(clock));
        }
    }

    /** 보호자 화면용. 끝난 공유도 10분 동안은 "도착했어요"를 보여 준다 */
    @Transactional
    public GuardianRequests.View view(String token) {
        GuardianShare s = find(token);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!s.ended() && stale(s, now)) {
            repo.end(token, "expired", now);
            s = find(token);
        }
        String status = s.ended() ? (s.endReason() == null ? "stopped" : s.endReason()) : "walking";
        Long ago = s.ended() || s.lastAt() == null ? null : Duration.between(s.lastAt(), now).toSeconds();
        return new GuardianRequests.View(s.walkerName(), s.destName(), s.destLat(), s.destLon(), s.mode(), status,
                s.ended() ? null : s.lastLat(), s.ended() ? null : s.lastLon(),
                s.lastAt() == null ? null : ISO.format(s.lastAt()), ago, s.remainM(), s.etaMin(), s.offRoute(),
                ISO.format(s.startedAt()), s.endedAt() == null ? null : ISO.format(s.endedAt()));
    }

    /** 정리 (1분마다): 만료된 공유를 끝내고, 끝난 지 10분 지난 줄을 지운다 */
    @Transactional
    public int[] cleanUp() {
        LocalDateTime now = LocalDateTime.now(clock);
        int expired = repo.expire(now, now.minus(SILENT_LIMIT));
        int deleted = repo.deleteEndedBefore(now.minus(KEEP_AFTER_END));
        return new int[] {expired, deleted};
    }

    private GuardianShare live(String token) {
        GuardianShare s = find(token);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!s.ended() && stale(s, now)) {
            repo.end(token, "expired", now);
            throw GuardianException.ended();
        }
        if (s.ended()) {
            throw GuardianException.ended();
        }
        return s;
    }

    private GuardianShare find(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw GuardianException.notFound();
        }
        return repo.find(token).orElseThrow(GuardianException::notFound);
    }

    static boolean stale(GuardianShare s, LocalDateTime now) {
        LocalDateTime last = s.lastAt() != null ? s.lastAt() : s.startedAt();
        return !now.isBefore(s.expiresAt()) || !now.isBefore(last.plus(SILENT_LIMIT));
    }

    String newToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    private static String text(String v, int max, String what) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            throw GuardianException.bad(what + "은 " + max + "자까지예요");
        }
        return s;
    }

    private static void checkPoint(Double lat, Double lon, boolean optional) {
        if (lat == null && lon == null && optional) {
            return;
        }
        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180
                || lat.isNaN() || lon.isNaN()) {
            throw GuardianException.bad("좌표가 이상해요");
        }
    }

    /** 가는 곳까지 직선거리(m). 가는 곳 좌표가 없으면 null */
    static Integer remain(Double lat, Double lon, Double destLat, Double destLon) {
        if (lat == null || lon == null || destLat == null || destLon == null) {
            return null;
        }
        double r = 6_371_000;
        double p1 = Math.toRadians(lat);
        double p2 = Math.toRadians(destLat);
        double dp = p2 - p1;
        double dl = Math.toRadians(destLon - lon);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return (int) Math.round(2 * r * Math.asin(Math.sqrt(a)));
    }
}
