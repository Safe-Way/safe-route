package com.saferoute.assistant;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.saferoute.assistant.Conversation.Pending;
import com.saferoute.assistant.Conversation.Place;
import com.saferoute.facility.Facility;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.PlaceSearchException;
import com.saferoute.report.ReportService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

/**
 * 비서 도구 7개 (팀 설계 "AI 도구 설계"). AI(제미나이)든 규칙이든 같은 도구를 부른다.
 * 도구 안의 일은 이미 있는 서버 코드가 한다: 카카오 장소 검색, 원일님 길찾기·대중교통, 재휘님 시설·내 정보 표.
 * 저장하는 도구(update_my_info, set_map_layers, report_problem)는 바로 저장하지 않고 확인 질문(Pending)을 만든다.
 * "네"를 들으면 save()가 저장한다.
 */
@Component
public class AssistantTools {

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    static final int MAX_CANDIDATES = 3;
    static final int NEARBY_RADIUS_M = 1000;
    /** 광주광역시 둘레 (시연은 광주만, 11/05 팀 결정) */
    static final double[] GWANGJU = {126.64, 35.04, 127.03, 35.27};
    static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");
    static final Set<String> AIDS = Set.of("manual_wheelchair", "power_wheelchair", "walker", "cane", "stroller");
    /** find_nearby 로 찾을 수 있는 종류 → 말할 이름 */
    static final Map<String, String> NEARBY = new LinkedHashMap<>();
    static {
        NEARBY.put("toilet", "화장실");
        NEARBY.put("shelter", "쉼터");
        NEARBY.put("police", "경찰서·파출소");
        NEARBY.put("store", "편의점");
        NEARBY.put("bell", "비상벨");
        NEARBY.put("subway", "지하철역");
        NEARBY.put("guard_house", "아동안전지킴이집");
    }
    static final Set<String> REPORT_TYPES = Set.of("steep", "construction", "curb", "broken", "obstacle",
            "accident", "other");

    private final KakaoLocalClient kakao;
    private final TripRecommender trips;
    private final FacilityRepository facilities;
    private final MemoryRepository memory;
    private final ReportService reports;
    private final WeatherDustService weatherDust;
    private final NightJudge nightJudge;

    public AssistantTools(KakaoLocalClient kakao, TripRecommender trips, FacilityRepository facilities,
            MemoryRepository memory, ReportService reports, WeatherDustService weatherDust, NightJudge nightJudge) {
        this.kakao = kakao;
        this.trips = trips;
        this.facilities = facilities;
        this.memory = memory;
        this.reports = reports;
        this.weatherDust = weatherDust;
        this.nightJudge = nightJudge;
    }

    // ───────────────────────── search_place ─────────────────────────

    /** search_place 결과. status: found(하나로 정함), choose(여럿), none, outside(광주 밖), error */
    record Found(String status, List<Place> places) {
    }

    /**
     * 목적지 찾기. 자주 가는 곳(휴대폰) → 다녀온 곳(trip_log) → 카카오 순서로 본다.
     * 하나면 도착지로 정하고(대화에 기억), 여럿이면 고르게 한다.
     */
    Found searchPlace(Turn t, String query) {
        t.tools.add("search_place");
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return new Found("none", List.of());
        }
        boolean near = wantsNear(t.text);
        // 1. 휴대폰의 자주 가는 곳 ("가까운 카페"처럼 가까운 곳을 찾을 때는 건너뜀)
        if (!near && t.req.favorites() != null) {
            for (AssistantRequest.Favorite f : t.req.favorites()) {
                if (f != null && f.name() != null && f.lat() != null && f.lon() != null
                        && (f.name().contains(q) || q.contains(f.name()))) {
                    return decide(t, List.of(new Place(f.name(), null, f.lat(), f.lon())), q);
                }
            }
        }
        // 2. 다녀온 곳 (동의한 사람만 기록이 있음, 도착지는 약 100m 단위)
        if (!near && t.guestId != null) {
            try {
                List<Place> v = memory.visited(t.guestId, q);
                if (!v.isEmpty()) {
                    return decide(t, List.of(v.get(0)), q);
                }
            } catch (DataAccessException e) {
                log.warn("비서 다녀온 곳 조회 실패: {}", e.getMostSpecificCause().getMessage());
            }
        }
        // 3. 카카오 장소 검색
        List<com.saferoute.place.Place> found;
        try {
            found = kakao.search(q, t.lat, t.lon);
        } catch (PlaceSearchException e) {
            log.warn("비서 장소 검색 실패: {}", e.getMessage());
            return new Found("error", List.of());
        }
        List<com.saferoute.place.Place> ordered = new ArrayList<>(found);
        if (near) {
            ordered.sort(AssistantTools::byDistance); // "가까운 카페", "근처 국밥집" → 가까운 순
        }
        List<Place> cand = ordered.stream().limit(MAX_CANDIDATES)
                .map(p -> new Place(p.name(), p.address(), p.lat(), p.lon(), p.distanceM())).toList();
        return decide(t, cand, q);
    }

    /**
     * 가게 종류("국밥", "카페")로 지금 위치에서 가까운 곳 3곳을 찾는다. 하나뿐이어도 고르게 한다 (버튼으로 보여 주려고).
     * 고르면 그곳이 도착지가 된다 (asked = place).
     */
    Found searchKind(Turn t, String kind) {
        t.tools.add("search_place");
        List<com.saferoute.place.Place> found;
        try {
            found = kakao.search(kind, t.lat, t.lon);
        } catch (PlaceSearchException e) {
            log.warn("비서 장소 검색 실패: {}", e.getMessage());
            return new Found("error", List.of());
        }
        List<com.saferoute.place.Place> near = new ArrayList<>(found);
        near.sort(AssistantTools::byDistance);
        List<Place> cand = near.stream().map(p -> new Place(p.name(), p.address(), p.lat(), p.lon(), p.distanceM()))
                .filter(AssistantTools::inGwangju).limit(MAX_CANDIDATES).toList();
        if (cand.isEmpty()) {
            return new Found(near.isEmpty() ? "none" : "outside", List.of());
        }
        t.convo = t.convo.withCandidates(cand).asked("place");
        return new Found("choose", cand);
    }

    /** "가까운", "근처", "주변" 이 들어 있으면 가까운 순으로 보여 준다 */
    static boolean wantsNear(String text) {
        String f = text == null ? "" : text.replace(" ", "");
        return f.contains("가까운") || f.contains("근처") || f.contains("주변") || f.contains("가까이");
    }

    private static int byDistance(com.saferoute.place.Place a, com.saferoute.place.Place b) {
        return Integer.compare(a.distanceM() == null ? Integer.MAX_VALUE : a.distanceM(),
                b.distanceM() == null ? Integer.MAX_VALUE : b.distanceM());
    }

    /**
     * 버튼 글: 위치를 알면 "월계수식당 · 250m", 모르면 "월계수식당 (주소)".
     * 거리는 장소 검색이 준 거리, 없으면 지금 위치에서 곧게 잰 거리 (걷는 거리는 길찾기에서 따로 나옴).
     */
    static String label(Turn t, Place d) {
        if (d.distanceM() != null) {
            return d.name() + " · " + distanceWord(d.distanceM());
        }
        if (t.located()) {
            return d.name() + " · " + distanceWord(straightM(t.lat, t.lon, d.lat(), d.lon()));
        }
        return d.name() + (d.address() == null ? "" : " (" + d.address() + ")");
    }

    /** 250m, 1.2km (m 는 10m 단위) */
    static String distanceWord(int m) {
        if (m < 1000) {
            return Math.max(10, Math.round(m / 10.0) * 10) + "m";
        }
        return String.format(java.util.Locale.ROOT, "%.1fkm", m / 1000.0);
    }

    /** 두 점 사이 곧은 거리(m) */
    static int straightM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(2 * r * Math.asin(Math.sqrt(a)));
    }

    /** 고를 후보를 버튼으로 (value = 0부터 번호) */
    static List<AssistantReply.Choice> placeChoices(Turn t, List<Place> cand) {
        List<AssistantReply.Choice> out = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) {
            out.add(new AssistantReply.Choice(String.valueOf(i), label(t, cand.get(i))));
        }
        return out;
    }

    private Found decide(Turn t, List<Place> cand, String q) {
        if (cand.isEmpty()) {
            return new Found("none", List.of());
        }
        List<Place> inside = cand.stream().filter(AssistantTools::inGwangju).toList();
        if (inside.isEmpty()) {
            return new Found("outside", cand);
        }
        if (inside.size() == 1 || inside.get(0).name().equals(q)) {
            t.convo = t.convo.withDestination(inside.get(0));
            return new Found("found", List.of(inside.get(0)));
        }
        t.convo = t.convo.withCandidates(inside).asked("place");
        return new Found("choose", inside);
    }

    static boolean inGwangju(Place p) {
        return p.lon() >= GWANGJU[0] && p.lat() >= GWANGJU[1] && p.lon() <= GWANGJU[2] && p.lat() <= GWANGJU[3];
    }

    // ───────────────────────── recommend_trip ─────────────────────────

    /** recommend_trip: 걷기·대중교통 후보를 모아 규칙으로 1등을 정한다. 결과는 t.plan */
    TripRecommender.Plan recommendTrip(Turn t, Place dest) {
        t.tools.add("recommend_trip");
        String mode = t.mode() == null ? "adult" : t.mode();
        TripRecommender.Plan plan = trips.recommend(new TripRecommender.Ask(t.guestId, t.lat, t.lon, dest, mode,
                night(t), t.testWeather, t.profile, t.convo.bodyNote()));
        t.plan = plan;
        t.planDest = dest;
        t.weather = plan.weather();
        return plan;
    }

    /** 시험 시각이 있으면 그 시각으로 밤을 정하고, 없으면 엔진이 지금 시각으로 정한다 */
    String night(Turn t) {
        if (!t.testTime || !t.located()) {
            return "auto";
        }
        return nightJudge.judge(t.lat, t.lon, t.now.atZone(KOREA), NightOption.AUTO).night() ? "on" : "off";
    }

    /** 날씨만 물을 때 (시험 날씨가 있으면 그 값) */
    WeatherDust weather(Turn t) {
        if (t.testWeather != null) {
            t.weather = t.testWeather;
        } else if (t.located()) {
            t.weather = weatherDust.judge(t.lat, t.lon);
        } else {
            t.weather = WeatherDust.unknown();
        }
        return t.weather;
    }

    // ───────────────────────── find_nearby ─────────────────────────

    /**
     * find_nearby: 가까운 시설. 모드는 묻지 않고 지금 모드(잠깐 바뀐 모드 포함)를 쓴다.
     * 휠체어면 화장실은 장애인 화장실만, 지하철역은 엘리베이터 출구가 있는 역만 (재휘님 /api/places/nearby 와 같은 규칙).
     */
    AssistantReply.Spot findNearby(Turn t, String kind) {
        t.tools.add("find_nearby");
        String k = NEARBY.containsKey(kind) ? kind : "toilet";
        boolean wheel = "wheelchair".equals(t.mode()) && ("toilet".equals(k) || "subway".equals(k));
        t.spotKindName = wheel && "toilet".equals(k) ? "장애인 화장실"
                : wheel ? "엘리베이터로 들어갈 수 있는 지하철역" : NEARBY.get(k);
        List<Facility> list;
        try {
            list = facilities.near(t.lat, t.lon, k, wheel, NEARBY_RADIUS_M);
        } catch (DataAccessException e) {
            log.warn("비서 가까운 시설 조회 실패: {}", e.getMostSpecificCause().getMessage());
            t.spotMessage = "지금은 시설 정보를 볼 수 없어요. 잠시 뒤에 다시 물어봐 주세요.";
            return null;
        }
        Facility open = null;
        Facility unknown = null;
        for (Facility f : list) {
            String s = f.nowStatus();
            if (open == null && (s == null || "이용 가능".equals(s))) {
                open = f;
            } else if (unknown == null && "확인 안 됨".equals(s)) {
                unknown = f;
            }
        }
        Facility pick = open != null ? open : unknown;
        if (pick == null) {
            t.spotMessage = list.isEmpty() ? "1km 안에 " + Josa.iGa(t.spotKindName) + " 없어요."
                    : "가까운 " + Josa.iGa(t.spotKindName) + " 지금은 모두 닫혀 있어요.";
            return null;
        }
        t.spot = new AssistantReply.Spot(pick.kind(), pick.name(), pick.lat(), pick.lon(), pick.distanceM(),
                pick.nowStatus());
        if (pick == unknown) {
            t.spotMessage = "지금 열려 있는지는 확인이 안 됐어요.";
        }
        return t.spot;
    }

    // ───────────────────────── get_my_info ─────────────────────────

    /** get_my_info: 내 정보 (없으면 null = 처음 쓰는 사람) */
    Profile getMyInfo(Turn t) {
        if (!t.tools.contains("get_my_info")) {
            t.tools.add("get_my_info");
        }
        return loadProfile(t);
    }

    /** 도구 이름을 남기지 않고 내 정보만 읽는다 (모드를 알아야 할 때) */
    Profile loadProfile(Turn t) {
        if (!t.profileLoaded) {
            t.profileLoaded = true;
            if (t.guestId != null) {
                try {
                    t.profile = memory.profile(t.guestId);
                } catch (DataAccessException e) {
                    log.warn("비서 내 정보 조회 실패: {}", e.getMostSpecificCause().getMessage());
                }
            }
        }
        return t.profile;
    }

    // ───────────────────────── 저장하는 도구 (확인 먼저) ─────────────────────────

    /**
     * update_my_info 제안. fields 칸: mode, mobility_aid, stairs_ok, temp_mode, temp_aid, temp_reason, temp_until(날짜),
     * clear_temp(true 면 잠깐 바뀐 모드 끝), setup_done.
     */
    Pending proposeMyInfo(Map<String, Object> fields, String question, Pending more) {
        return new Pending("update_my_info", Map.copyOf(fields), question, more);
    }

    /** set_map_layers 제안. add·remove 는 map_layer 의 layer 값 */
    Pending proposeLayers(List<String> add, List<String> remove, LocalDate until, String reason, String question) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("add", List.copyOf(add));
        a.put("remove", List.copyOf(remove));
        if (until != null) {
            a.put("until", until.toString());
        }
        if (reason != null) {
            a.put("reason", reason);
        }
        return new Pending("set_map_layers", a, question, null);
    }

    /** report_problem 제안 (지금 위치, 사진 없이) */
    Pending proposeReport(Turn t, String type, String memo) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", REPORT_TYPES.contains(type) ? type : "other");
        if (memo != null) {
            a.put("memo", memo);
        }
        String name = ReportService.typeName((String) a.get("type"));
        return new Pending("report_problem", a, "지금 계신 곳을 '" + name + "'(으)로 신고할까요? 사진도 찍으시려면 신고 화면을 써 주세요.",
                null);
    }

    /** 확인을 받은 저장 일을 한다. 성공하면 사용자에게 알릴 말을 돌려준다 */
    String save(Turn t, Pending p) {
        t.tools.add(p.tool());
        try {
            return switch (p.tool()) {
                case "update_my_info" -> saveMyInfo(t, p.args());
                case "set_map_layers" -> saveLayers(t, p.args());
                case "report_problem" -> saveReport(t, p.args());
                default -> null;
            };
        } catch (DataAccessException e) {
            log.warn("비서 저장 실패 ({}): {}", p.tool(), e.getMostSpecificCause().getMessage());
            return "지금은 저장이 안 돼요. 잠시 뒤에 다시 말씀해 주세요.";
        }
    }

    private String saveMyInfo(Turn t, Map<String, Object> a) {
        if (t.guestId == null) {
            return "앱 번호가 없어서 기억하지 못했어요. 이번 대화에서만 그렇게 안내할게요.";
        }
        Map<String, Object> f = new LinkedHashMap<>();
        for (String k : MemoryRepository.PROFILE_COLUMNS) {
            if (a.containsKey(k)) {
                Object v = a.get(k);
                f.put(k, ("temp_until".equals(k) || "next_ask_on".equals(k)) && v instanceof String s
                        ? LocalDate.parse(s) : v);
            }
        }
        if (Boolean.TRUE.equals(a.get("clear_temp"))) {
            f.put("temp_mode", null);
            f.put("temp_aid", null);
            f.put("temp_reason", null);
            f.put("temp_until", null);
            f.put("next_ask_on", null);
        }
        memory.saveProfile(t.guestId, f);
        t.profileLoaded = false;
        loadProfile(t);
        if (a.get("mode") instanceof String m && MODES.contains(m)) {
            t.convo = t.convo.withMode(null);
        }
        return "기억해 둘게요.";
    }

    private String saveLayers(Turn t, Map<String, Object> a) {
        if (t.guestId == null) {
            return "앱 번호가 없어서 지도 설정을 저장하지 못했어요.";
        }
        LocalDate until = a.get("until") instanceof String s ? LocalDate.parse(s) : null;
        String reason = a.get("reason") instanceof String s ? s : null;
        for (Object l : (List<?>) a.getOrDefault("add", List.of())) {
            memory.saveLayer(t.guestId, l.toString(), true, until, reason);
        }
        for (Object l : (List<?>) a.getOrDefault("remove", List.of())) {
            memory.saveLayer(t.guestId, l.toString(), false, until, reason);
        }
        return "지도를 바꿨어요.";
    }

    private String saveReport(Turn t, Map<String, Object> a) {
        if (!t.located()) {
            return "지금 위치를 몰라서 신고하지 못했어요. 위치 켜기를 눌러 주세요.";
        }
        reports.createVoice((String) a.get("type"), t.mode(), (String) a.get("memo"), t.lat, t.lon);
        return "신고했어요. 다른 분들 지도에도 보여요.";
    }

    // ───────────────────────── 지도 시설 사전 ─────────────────────────

    private volatile List<MemoryRepository.Layer> layerCache;

    /** map_layer 사전 (한 번 읽고 기억) */
    List<MemoryRepository.Layer> layers() {
        List<MemoryRepository.Layer> l = layerCache;
        if (l == null) {
            try {
                l = memory.layers();
                layerCache = l;
            } catch (DataAccessException e) {
                log.warn("지도 시설 사전 조회 실패: {}", e.getMostSpecificCause().getMessage());
                return List.of();
            }
        }
        return l;
    }

    /** 사전 이름 ("엘리베이터 육교") → layer 값. 이름·같은 말 중 하나와 같으면. 없으면 null */
    String layerOf(String word) {
        String w = norm(word);
        for (MemoryRepository.Layer l : layers()) {
            if (norm(l.name()).equals(w) || l.layer().equals(word)
                    || l.aliases().stream().anyMatch(a -> norm(a).equals(w))) {
                return l.layer();
            }
        }
        return null;
    }

    String layerName(String layer) {
        return layers().stream().filter(l -> l.layer().equals(layer)).map(MemoryRepository.Layer::name).findFirst()
                .orElse(layer);
    }

    static String norm(String s) {
        return s == null ? "" : s.replace(" ", "").replace("있는", "").replace("달린", "").replace("엘베", "엘리베이터")
                .replace("승강기", "엘리베이터");
    }

    /** 말 속에서 찾은 사전 이름. pos 는 말에서의 위치 (공백 뺀 글자 기준) */
    record LayerHit(String layer, int pos, int end) {
    }

    /** 말 안에 든 지도 시설 이름들 (긴 이름 먼저, 겹치지 않게) */
    List<LayerHit> layerHits(String text) {
        String t = norm(text);
        List<String[]> names = new ArrayList<>();
        for (MemoryRepository.Layer l : layers()) {
            names.add(new String[] {norm(l.name()), l.layer()});
            for (String a : l.aliases()) {
                names.add(new String[] {norm(a), l.layer()});
            }
        }
        names.sort((x, y) -> y[0].length() - x[0].length());
        boolean[] used = new boolean[t.length()];
        List<LayerHit> hits = new ArrayList<>();
        for (String[] n : names) {
            if (n[0].isEmpty()) {
                continue;
            }
            int from = 0;
            int i;
            while ((i = t.indexOf(n[0], from)) >= 0) {
                boolean free = true;
                for (int j = i; j < i + n[0].length(); j++) {
                    free &= !used[j];
                }
                if (free) {
                    for (int j = i; j < i + n[0].length(); j++) {
                        used[j] = true;
                    }
                    final String layer = n[1];
                    if (hits.stream().noneMatch(h -> h.layer().equals(layer))) {
                        hits.add(new LayerHit(layer, i, i + n[0].length()));
                    }
                }
                from = i + 1;
            }
        }
        hits.sort((x, y) -> x.pos() - y.pos());
        return hits;
    }
}
