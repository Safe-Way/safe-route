package com.saferoute.me;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보·다닌 기록·내 기록 지우기 (팀 할 일 5번, 동의 문구 docs/assistant/consent.md 1·5번).
 * 좌표는 도착지만 약 100m 로 반올림해 받고, 출발 좌표·정확한 시각은 받지 않는다.
 */
@Service
public class MeService {

    /** 잠깐 모드의 끝나는 날을 모르면 이만큼 뒤로 두고 그날 다시 묻는다 (028 표 설명) */
    static final int TEMP_DEFAULT_DAYS = 7;

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LAYER = Pattern.compile("[a-z_]{2,20}(:[a-z_]{2,18})?");
    static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");
    static final Set<String> AIDS = Set.of("manual_wheelchair", "power_wheelchair", "walker", "cane", "stroller");
    static final Set<String> TRAVELS = Set.of("walk", "transit");
    static final Set<String> ROUTE_KINDS = Set.of("safe", "shortest", "bus", "subway", "bus_subway");
    static final List<String> WEATHERS = List.of("rain", "heat", "cold", "dust");
    static final Set<String> RESULTS = Set.of("arrived", "stopped");
    static final Set<String> FEEDBACKS = Set.of("good", "bad");

    private final MeRepository repo;
    private final Clock clock;

    public MeService(MeRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 내 정보 보기. 서버가 모르는 사람이면 known=false 와 빈 값 */
    public MeRequests.View view(String guestId) {
        String g = guest(guestId);
        return repo.find(g).map(r -> new MeRequests.View(true, r.modeNow(), r.aidNow(), r.tempActive(), r.mode(),
                        r.mobilityAid(), r.stairsOk(), r.maxDetour(), r.restEveryM(), r.preferTransit(), r.tempMode(),
                        r.tempAid(), r.tempReason(), r.tempUntil() == null ? null : r.tempUntil().toString(),
                        r.setupDone(), r.askToday(), r.consentTrips(), repo.tripCount(g), repo.layers(g)))
                .orElseGet(() -> new MeRequests.View(false, null, null, false, null, null, null, null, null, null,
                        null, null, null, null, false, true, false, 0, List.of()));
    }

    /** 내 정보 고치기 (처음 설정 질문 ①~⑤의 답도 여기로). 보낸 칸만 바꾼다 */
    @Transactional
    public MeRequests.View update(MeRequests.Update in) {
        if (in == null) {
            throw MeException.bad("보낼 내용이 없어요");
        }
        String g = guest(in.guestId());
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> f = new HashMap<>();
        if (in.mode() != null) {
            f.put("mode", oneOf(in.mode(), MODES, "모드는 adult·child·senior·wheelchair 중 하나예요"));
        }
        if (in.mobilityAid() != null) {
            f.put("mobility_aid", aid(in.mobilityAid()));
        }
        if (in.stairsOk() != null) {
            f.put("stairs_ok", range(in.stairsOk(), 0, 2, "계단(stairsOk)은 0·1·2 중 하나예요"));
        }
        if (in.maxDetour() != null) {
            if (in.maxDetour() < 1.0 || in.maxDetour() > 2.0) {
                throw MeException.bad("돌아가도 되는 정도(maxDetour)는 1.0 ~ 2.0 이에요");
            }
            f.put("max_detour", Math.round(in.maxDetour() * 100) / 100.0);
        }
        if (in.restEveryM() != null) {
            f.put("rest_every_m", range(in.restEveryM(), 50, 2000, "쉬는 간격(restEveryM)은 50 ~ 2000m 예요"));
        }
        if (in.preferTransit() != null) {
            f.put("prefer_transit", range(in.preferTransit(), 0, 2, "대중교통 선호(preferTransit)는 0·1·2 중 하나예요"));
        }
        if (in.setupDone() != null) {
            f.put("setup_done", in.setupDone() ? 1 : 0);
        }
        if (in.consentTrips() != null) {
            f.put("consent_trips", in.consentTrips() ? 1 : 0);
            f.put("consent_at", in.consentTrips() ? LocalDateTime.now(clock) : null);
        }
        if (Boolean.TRUE.equals(in.clearTemp())) {
            f.put("temp_mode", null);
            f.put("temp_aid", null);
            f.put("temp_reason", null);
            f.put("temp_until", null);
            f.put("next_ask_on", null);
        } else if (in.tempMode() != null || in.tempAid() != null) {
            if (in.tempMode() != null) {
                f.put("temp_mode", oneOf(in.tempMode(), MODES, "잠깐 모드는 adult·child·senior·wheelchair 중 하나예요"));
            }
            if (in.tempAid() != null) {
                f.put("temp_aid", aid(in.tempAid()));
            }
            f.put("temp_reason", text(in.tempReason(), 50, "이유"));
            LocalDate until = in.tempUntil() == null || in.tempUntil().isBlank()
                    ? today.plusDays(TEMP_DEFAULT_DAYS) : date(in.tempUntil(), today);
            f.put("temp_until", until);
            f.put("next_ask_on", until);
        } else if (in.tempUntil() != null || in.tempReason() != null) {
            throw MeException.bad("잠깐 모드(tempMode)나 이동 보조(tempAid)와 같이 보내 주세요");
        }
        repo.save(g, f);
        if (Boolean.FALSE.equals(in.consentTrips())) {
            repo.deleteTrips(g);
        }
        return view(g);
    }

    /** 내 지도 시설 하나 보이기·숨기기 */
    @Transactional
    public List<MeRequests.Layer> setLayer(String layer, MeRequests.LayerSet in) {
        if (in == null || in.shown() == null) {
            throw MeException.bad("보일지(shown) 값이 필요해요");
        }
        String g = guest(in.guestId());
        String l = layer(layer);
        LocalDate until = in.until() == null || in.until().isBlank() ? null : date(in.until(), LocalDate.now(clock));
        repo.ensure(g);
        repo.saveLayer(g, l, in.shown(), until, text(in.reason(), 50, "이유"));
        return repo.layers(g);
    }

    /** 지도 시설 설정을 모드 기본으로 되돌리기 */
    @Transactional
    public List<MeRequests.Layer> resetLayer(String layer, String guestId) {
        String g = guest(guestId);
        repo.deleteLayer(g, layer(layer));
        return repo.layers(g);
    }

    /** 길안내 시작 기록 (동의한 사람만). 출발 날짜·요일·30분 시간대는 서버 시계로 정한다 */
    @Transactional
    public MeRequests.TripStarted startTrip(MeRequests.TripStart in) {
        if (in == null) {
            throw MeException.bad("보낼 내용이 없어요");
        }
        String g = guest(in.guestId());
        String name = text(in.toName(), 100, "도착지 이름");
        if (name == null) {
            throw MeException.bad("도착지 이름(toName)이 필요해요");
        }
        if (in.toLat() == null || in.toLon() == null || in.toLat() < 33 || in.toLat() > 39
                || in.toLon() < 124 || in.toLon() > 132) {
            throw MeException.bad("도착지 좌표(toLat, toLon)가 우리나라 안이 아니에요");
        }
        String mode = oneOf(in.mode(), MODES, "모드는 adult·child·senior·wheelchair 중 하나예요");
        String travel = oneOf(in.travel(), TRAVELS, "travel 은 walk·transit 중 하나예요");
        String kind = in.routeKind() == null || in.routeKind().isBlank() ? null
                : oneOf(in.routeKind(), ROUTE_KINDS, "routeKind 는 safe·shortest·bus·subway·bus_subway 중 하나예요");
        Integer planned = in.plannedMin() == null ? null : range(in.plannedMin(), 0, 600, "예상 시간(plannedMin)은 0 ~ 600분이에요");
        String weather = weather(in.weather());
        String body = text(in.bodyNote(), 30, "몸 상태 한마디");

        if (repo.find(g).map(r -> !r.consentTrips()).orElse(true)) {
            throw MeException.noConsent();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime slot = LocalTime.of(now.getHour(), now.getMinute() < 30 ? 0 : 30);
        long id = repo.insertTrip(g, now.toLocalDate(), now.getDayOfWeek().getValue(), slot,
                new MeRepository.Trip(name, round3(in.toLat()), round3(in.toLon()), mode, travel, kind,
                        Boolean.TRUE.equals(in.byAssistant()), weather, Boolean.TRUE.equals(in.night()), body, planned));
        repo.trimTrips(g);
        return new MeRequests.TripStarted(id, now.toLocalDate().toString(), slot.toString());
    }

    /** 도착·그만둠과 한마디 적기. 그 기록까지 보고 대중교통·계단 선호를 고친다 (PreferenceLearner) */
    @Transactional
    public MeRequests.TripEnded endTrip(long id, MeRequests.TripEnd in) {
        if (in == null) {
            throw MeException.bad("보낼 내용이 없어요");
        }
        String g = guest(in.guestId());
        String result = oneOf(in.result(), RESULTS, "result 는 arrived·stopped 중 하나예요");
        Integer actual = in.actualMin() == null ? null : range(in.actualMin(), 0, 600, "걸린 시간(actualMin)은 0 ~ 600분이에요");
        String feedback = in.feedback() == null || in.feedback().isBlank() ? null
                : oneOf(in.feedback(), FEEDBACKS, "feedback 은 good·bad 중 하나예요");
        String reason = text(in.feedbackReason(), 30, "불편한 이유");
        if (repo.endTrip(g, id, result, "stopped".equals(result) ? null : actual, feedback, reason) == 0) {
            throw MeException.tripNotFound();
        }
        MeRepository.Row me = repo.find(g).orElse(null);
        List<PreferenceLearner.Done> recent = repo.recentDone(g, PreferenceLearner.RECENT);
        List<MeRequests.Learned> learned = PreferenceLearner.learn(recent,
                new PreferenceLearner.Done(null, result, feedback, reason),
                me == null ? null : me.preferTransit(), me == null ? null : me.stairsOk());
        if (!learned.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            for (MeRequests.Learned l : learned) {
                f.put("preferTransit".equals(l.what()) ? "prefer_transit" : "stairs_ok", l.to());
            }
            repo.save(g, f);
        }
        return new MeRequests.TripEnded(learned);
    }

    /** 내 기록 모두 지우기: 내 정보, 다닌 기록, 지도 설정, 걸음 속도, 보호자 공유 */
    @Transactional
    public MeRequests.Deleted deleteAll(String guestId) {
        return repo.deleteAll(guest(guestId));
    }

    // ---- 값 고르기 ----

    static String guest(String s) {
        if (s == null || s.isBlank()) {
            throw MeException.bad("게스트 번호(guestId)가 필요해요");
        }
        if (!UUID.matcher(s.strip()).matches()) {
            throw MeException.bad("게스트 번호 모양이 틀렸어요");
        }
        return s.strip().toLowerCase();
    }

    private static String oneOf(String s, Set<String> allowed, String message) {
        String v = s == null ? "" : s.strip().toLowerCase();
        if (!allowed.contains(v)) {
            throw MeException.bad(message);
        }
        return v;
    }

    /** 이동 보조. none 이면 비운다 */
    private static String aid(String s) {
        String v = s.strip().toLowerCase();
        if (v.equals("none") || v.isEmpty()) {
            return null;
        }
        return oneOf(v, AIDS, "이동 보조는 manual_wheelchair·power_wheelchair·walker·cane·stroller·none 중 하나예요");
    }

    private static int range(int v, int min, int max, String message) {
        if (v < min || v > max) {
            throw MeException.bad(message);
        }
        return v;
    }

    private static String text(String s, int max, String what) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.strip();
        if (v.length() > max) {
            throw MeException.bad(what + "은(는) " + max + "자까지예요");
        }
        return v;
    }

    /** "2026-11-20". 오늘부터 1년 안만 */
    private static LocalDate date(String s, LocalDate today) {
        LocalDate d;
        try {
            d = LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            throw MeException.bad("날짜는 2026-11-20 처럼 보내 주세요");
        }
        if (d.isBefore(today) || d.isAfter(today.plusYears(1))) {
            throw MeException.bad("날짜는 오늘부터 1년 안이어야 해요");
        }
        return d;
    }

    private static String layer(String s) {
        String v = s == null ? "" : s.strip().toLowerCase();
        if (!LAYER.matcher(v).matches()) {
            throw MeException.bad("지도 시설 이름 모양이 틀렸어요");
        }
        return v;
    }

    /** "rain,dust" 처럼 쉼표로. 정해진 말만 남기고, 맑으면 null */
    static String weather(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.toLowerCase().split(",")) {
            String w = p.strip();
            if (w.isEmpty() || w.equals("clear")) {
                continue;
            }
            if (!WEATHERS.contains(w)) {
                throw MeException.bad("날씨(weather)는 rain·heat·cold·dust 를 쉼표로 이어 보내 주세요");
            }
            out.add(w);
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /** 약 100m 단위로 반올림 (소수 셋째 자리) */
    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
