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
        NEARBY.put("elevator", "엘리베이터"); // 엘리베이터 육교 + 엘리베이터 있는 지하철 출구 (11-13)
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
    private final StopPlanner stops;

    /** 들를 곳 묶기 없이 (예전 테스트) */
    public AssistantTools(KakaoLocalClient kakao, TripRecommender trips, FacilityRepository facilities,
            MemoryRepository memory, ReportService reports, WeatherDustService weatherDust, NightJudge nightJudge) {
        this(kakao, trips, facilities, memory, reports, weatherDust, nightJudge, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantTools(KakaoLocalClient kakao, TripRecommender trips, FacilityRepository facilities,
            MemoryRepository memory, ReportService reports, WeatherDustService weatherDust, NightJudge nightJudge,
            StopPlanner stops) {
        this.stops = stops;
        this.kakao = kakao;
        this.taxiStands = new TaxiStands(kakao);
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
        Found f = findPlace(t, query);
        if ("none".equals(f.status()) || "outside".equals(f.status())) {
            t.placeMissed = true;
        }
        return f;
    }

    private Found findPlace(Turn t, String query) {
        t.tools.add("search_place");
        String q = query == null ? "" : com.saferoute.place.PlaceAlias.of(query.strip());
        if (q.isEmpty()) {
            return new Found("none", List.of());
        }
        boolean near = wantsNear(t.text);
        // 1. 휴대폰의 자주 가는 곳 ("가까운 카페"처럼 가까운 곳을 찾을 때는 건너뜀)
        if (!near && t.req.favorites() != null) {
            for (AssistantRequest.Favorite f : t.req.favorites()) {
                if (f != null && f.name() != null && f.lat() != null && f.lon() != null
                        && (f.name().contains(q) || q.contains(f.name())
                        || f.name().replace(" ", "").equals(q.replace(" ", "")))) {
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
        // 집을 저장하지 않았으면 카카오로 찾지 않는다 ("하남돼지집", "해뜨는집" 같은 가게가 나옴, 진행표 c22)
        if (!near && HOME.contains(q.replace(" ", ""))) {
            return new Found("no_home", List.of());
        }
        // 3. "서석동 3", "광주 동구 서석동 3"처럼 주소로 말했으면 카카오 주소 검색 먼저 (11-12)
        if (!near && (looksLikeAddress(q) || looksLikeDong(q))) {
            Found byAddress = byAddress(t, q);
            if (byAddress != null) {
                return byAddress;
            }
        }
        // 4. 카카오 장소 검색
        List<com.saferoute.place.Place> found;
        try {
            found = kakao.search(q, t.lat, t.lon);
        } catch (PlaceSearchException e) {
            log.warn("비서 장소 검색 실패: {}", e.getMessage());
            return new Found("error", List.of());
        }
        // 카카오는 위치를 주면 가까운 순 10곳만 줘서, 이름에 '금남지하상가'가 든 가게 10곳이 진짜 금남지하상가(296m)를
        // 밀어낼 수 있다 (11-13). 이름이 똑같은 곳이 없으면 정확도 순으로 한 번 더 보고, 광주 안에 똑같은 곳이 있으면 맨 앞에
        if (!near && t.located() && found.stream().noneMatch(p -> com.saferoute.place.PlaceAlias.same(p.name(), q))) {
            found = withExact(found, q);
        }
        // "조선대", "전남대"처럼 대학 줄임말이면 "○○대학교"가 광주에 있을 때 그곳 하나로 (11-16 시험 44번:
        // 카카오가 "조선대학교 총동창회", "도야짬뽕 광주조선대점"만 줘서 되물었음)
        String univ = univ(q);
        if (!near && univ != null && found.stream().noneMatch(p -> com.saferoute.place.PlaceAlias.same(p.name(), univ))) {
            List<com.saferoute.place.Place> withUniv = withExact(found, univ);
            if (withUniv.size() > found.size()) {
                com.saferoute.place.Place u = withUniv.get(0);
                return decide(t, List.of(new Place(u.name(), u.address(), u.lat(), u.lon(), u.distanceM())), univ);
            }
        }
        // "남광주 시장"처럼 띄어 써서 광주에 맞는 곳이 없으면 붙여서 다시 (11-12 음성 인식 ④)
        String joined = q.replace(" ", "");
        if (!near && !joined.equals(q) && joined.length() >= 2
                && found.stream().map(p -> new Place(p.name(), p.address(), p.lat(), p.lon())).noneMatch(AssistantTools::inGwangju)) {
            try {
                boolean hit = kakao.search(joined, t.lat, t.lon).stream()
                        .map(p -> new Place(p.name(), p.address(), p.lat(), p.lon())).anyMatch(AssistantTools::inGwangju);
                if (hit) {
                    t.tools.remove("search_place"); // 다시 부르면서 한 번 더 넣으므로
                    return searchPlace(t, joined);
                }
            } catch (PlaceSearchException e) {
                log.info("띄어쓰기 뺀 이름 찾기 실패: {}", e.getMessage());
            }
        }
        // "조용한 공원"처럼 꾸미는 말이 붙어 광주에 맞는 곳이 없으면 마지막 낱말("공원")로 가까운 곳을 다시 (사용성 점검)
        String head = headWord(q);
        if (head != null && t.located() && found.stream().map(p -> new Place(p.name(), p.address(), p.lat(), p.lon()))
                .noneMatch(AssistantTools::inGwangju)) {
            return nearKind(t, head);
        }
        // "충장로", "양림동"처럼 거리·동네 이름이면 가게를 고르게 하지 않고 그 거리로 (점검 5번)
        Place area = area(q, found);
        if (area != null) {
            return decide(t, List.of(area), q);
        }
        List<com.saferoute.place.Place> ordered = new ArrayList<>(found);
        if (near) {
            ordered.sort(AssistantTools::byDistance); // "가까운 카페", "근처 국밥집" → 가까운 순
        } else {
            // 카카오는 위치를 주면 가까운 순이라 "세븐일레븐 남광주시장점"이 시장보다 먼저 온다.
            // 이름이 같은 곳 → 이름에 든 곳 → 주차장·○○점 → 이름에 없는 곳 (같은 칸 안은 가까운 순 그대로, 진행표 c22)
            ordered.sort(java.util.Comparator.comparingInt(p -> rank(p.name(), q)));
        }
        List<Place> cand = ordered.stream().limit(MAX_CANDIDATES)
                .map(p -> new Place(p.name(), p.address(), p.lat(), p.lon(), p.distanceM())).toList();
        Found r = decide(t, cand, q);
        if (!near && ("none".equals(r.status()) || "outside".equals(r.status()))) {
            Found loose = loose(t, q);
            if (loose != null) {
                return loose;
            }
        }
        return r;
    }

    /**
     * 못 찾은 이름을 한 번 더 (11-12 음성 인식 ④): 자주 가는 곳 이름과 한두 글자만 다르면 그곳 ("문화전딩" → 문화전당).
     * 띄어쓰기를 뺀 다시 찾기는 searchPlace 안에서 먼저 한다. 그래도 없으면 null
     */
    private Found loose(Turn t, String q) {
        String joined = q.replace(" ", "");
        if (t.req.favorites() != null && joined.length() >= 3) {
            AssistantRequest.Favorite best = null;
            int bestD = Integer.MAX_VALUE;
            for (AssistantRequest.Favorite f : t.req.favorites()) {
                if (f == null || f.name() == null || f.lat() == null || f.lon() == null) {
                    continue;
                }
                int d = editDistance(f.name().replace(" ", ""), joined);
                if (d < bestD) {
                    bestD = d;
                    best = f;
                }
            }
            int allowed = joined.length() >= 6 ? 2 : 1;
            if (best != null && bestD <= allowed) {
                return decide(t, List.of(new Place(best.name(), null, best.lat(), best.lon())), best.name());
            }
        }
        return null;
    }

    /** 두 글자 사이 몇 글자를 바꾸면 같아지나 (레벤슈타인 거리) */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** "집에 가자"의 집. 휴대폰 자주 가는 곳·다녀온 곳에 없으면 no_home */
    static final List<String> HOME = List.of("집", "우리집", "내집", "본가");

    /** 집을 모를 때 (no_home) */
    static final String HOME_UNKNOWN = "집을 아직 몰라요. 앱의 자주 가는 곳에 집을 저장해 주시면 바로 안내할게요."
            + " 지금은 집 근처 건물이나 동네 이름을 말씀해 주셔도 돼요.";

    /** 딸린 곳: 그 장소 자체가 아니라 옆에 붙은 시설 */
    private static final List<String> ANNEX = List.of("주차장", "주차타워", "정류장", "화장실", "ATM", "매표소");

    /**
     * 장소 후보 순서 (작을수록 앞). 0 이름이 같음, 1 이름에 찾는 말이 들어 있음, 2 그중 주차장 같은 딸린 곳이나
     * 체인 가게 지점("세븐일레븐 남광주시장점"), 3 이름에 찾는 말이 없음. 띄어쓰기는 보지 않는다.
     */
    static int rank(String name, String q) {
        String n = name == null ? "" : name.replace(" ", "");
        String w = q == null ? "" : q.replace(" ", "");
        if (w.isEmpty()) {
            return 3;
        }
        if (n.equals(w)) {
            return 0;
        }
        if (!n.contains(w)) {
            return 3;
        }
        for (String a : ANNEX) {
            if (n.contains(a) && !w.contains(a)) {
                return 2;
            }
        }
        if (n.endsWith(w + "점") || (n.endsWith("점") && !w.endsWith("점") && name.strip().contains(" "))) {
            return 2;
        }
        return 1;
    }

    /**
     * 가게 종류("국밥", "카페")로 지금 위치에서 가까운 곳 3곳을 찾는다. 하나뿐이어도 고르게 한다 (버튼으로 보여 주려고).
     * 고르면 그곳이 도착지가 된다 (asked = place).
     */
    Found searchKind(Turn t, String kind) {
        t.tools.add("search_place");
        return nearKind(t, kind);
    }

    /** 여러 낱말이면 마지막 낱말 ("조용한 공원" → 공원). 한 낱말이거나 너무 짧으면 null */
    static String headWord(String q) {
        String[] w = q.strip().split("\\s+");
        if (w.length < 2) {
            return null;
        }
        String last = RuleReader.stripParticle(w[w.length - 1]);
        return last.length() >= 2 ? last : null;
    }

    private Found nearKind(Turn t, String kind) {
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

    /** 정확도 순 검색에서 이름이 똑같은 광주 안 장소를 찾아 맨 앞에 넣는다. 없거나 실패하면 그대로 */
    private List<com.saferoute.place.Place> withExact(List<com.saferoute.place.Place> found, String q) {
        try {
            for (com.saferoute.place.Place p : kakao.search(q, null, null)) {
                if (com.saferoute.place.PlaceAlias.same(p.name(), q)
                        && inGwangju(new Place(p.name(), p.address(), p.lat(), p.lon()))) {
                    List<com.saferoute.place.Place> out = new ArrayList<>();
                    out.add(p);
                    out.addAll(found);
                    return out;
                }
            }
        } catch (PlaceSearchException e) {
            log.info("이름 똑같은 곳 다시 찾기 실패: {}", e.getMessage());
        }
        return found;
    }

    /** 이름으로 광주 안 첫 장소 하나 (근처를 정할 기준점). 대화는 바꾸지 않는다. 못 찾으면 null */
    Place firstPlace(Turn t, String name) {
        try {
            String n = com.saferoute.place.PlaceAlias.of(name);
            List<com.saferoute.place.Place> found = kakao.search(n, t.lat, t.lon);
            if (found.stream().noneMatch(p -> com.saferoute.place.PlaceAlias.same(p.name(), n))) {
                found = withExact(found, n);
            }
            for (com.saferoute.place.Place p : found) {
                Place c = new Place(p.name(), p.address(), p.lat(), p.lon());
                if (inGwangju(c)) {
                    return c;
                }
            }
            if (!n.startsWith("광주")) {
                // 광주 안 결과가 하나도 없으면 "광주 "를 붙여 한 번 더 (경기 고양·성남 같은 곳이 앞을 채울 때, 11-13)
                for (com.saferoute.place.Place p : kakao.search("광주 " + n, t.lat, t.lon)) {
                    Place c = new Place(p.name(), p.address(), p.lat(), p.lon());
                    if (inGwangju(c)) {
                        return c;
                    }
                }
            }
        } catch (PlaceSearchException e) {
            log.warn("비서 기준점 찾기 실패: {}", e.getMessage());
        }
        return null;
    }

    /** anchor 근처 kind 를 가까운 순 3곳 고르게 한다 ("시장 갔다 근처 병원"). 거리는 anchor 에서 잰 것 */
    Found around(Turn t, String kind, Place anchor) {
        t.tools.add("search_place");
        List<com.saferoute.place.Place> found;
        try {
            found = new ArrayList<>(kakao.search(kind, anchor.lat(), anchor.lon()));
        } catch (PlaceSearchException e) {
            log.warn("비서 장소 검색 실패: {}", e.getMessage());
            return new Found("error", List.of());
        }
        found.sort(AssistantTools::byDistance);
        List<Place> cand = found.stream().map(p -> new Place(p.name(), p.address(), p.lat(), p.lon(), p.distanceM()))
                .filter(AssistantTools::inGwangju).limit(MAX_CANDIDATES).toList();
        if (cand.isEmpty()) {
            return new Found(found.isEmpty() ? "none" : "outside", List.of());
        }
        return decide(t, cand, kind);
    }

    private static final java.util.regex.Pattern AREA = java.util.regex.Pattern.compile(
            "^[가-힣]{1,8}(대로|로|길|동)(\\d{1,2}가)?$");

    /**
     * 거리·동네 이름("충장로", "금남로", "양림동", "충장로3가")이면 그 이름이 주소에 들어 있는 검색 결과들의 가운데를
     * 도착지로 삼는다. 이름이 장소 이름과 똑같은 곳이 있거나(예: "○○길"이라는 가게), 주소에 든 곳이 없으면 null.
     */
    static Place area(String q, List<com.saferoute.place.Place> found) {
        String name = q.replace(" ", "");
        if (!AREA.matcher(name).matches()) {
            return null;
        }
        String road = name.replaceAll("\\d{1,2}가$", "");
        List<com.saferoute.place.Place> on = new ArrayList<>();
        for (com.saferoute.place.Place p : found) {
            if (p.name().replace(" ", "").equals(name)) {
                return null;
            }
            if (p.address() != null && p.address().replace(" ", "").contains(road)
                    && inGwangju(new Place(p.name(), null, p.lat(), p.lon()))) {
                on.add(p);
            }
        }
        if (on.isEmpty()) {
            return null;
        }
        double lat = on.stream().mapToDouble(com.saferoute.place.Place::lat).average().orElseThrow();
        double lon = on.stream().mapToDouble(com.saferoute.place.Place::lon).average().orElseThrow();
        String addr = on.get(0).address();
        int at = addr.indexOf(road.substring(0, Math.min(road.length(), 2)));
        String where = (at > 0 ? addr.substring(0, at) : "") + name + " 일대";
        return new Place(name, where.strip(), lat, lon);
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

    /**
     * 출발지 찾기 결과.
     *
     * @param place 찾은 출발지. 못 찾았으면 null
     * @param say   못 찾았을 때 할 말
     */
    record OriginFound(Place place, String say) {
    }

    /** "광주역에서"의 광주역을 찾는다. 장소 찾기와 같은 순서(자주 가는 곳 → 다녀온 곳 → 카카오)로, 여러 곳이면 맨 앞 */
    OriginFound findOrigin(Turn t, String name) {
        Conversation keep = t.convo;
        List<String> before = List.copyOf(t.tools);
        Found f = searchPlace(t, name);
        t.convo = keep; // 출발지 찾기는 도착·후보·되물음을 바꾸지 않는다
        t.tools.clear();
        t.tools.addAll(before);
        return switch (f.status()) {
            case "found", "choose" -> new OriginFound(exactOrigin(name, f.places().get(0)), null);
            case "no_home" -> new OriginFound(null, AssistantTools.HOME_UNKNOWN);
            case "outside" -> new OriginFound(null, Josa.eun("'" + f.places().get(0).name() + "'")
                    + " 광주 밖이에요. 지금은 광주 안에서만 길을 안내해요.");
            case "error" -> new OriginFound(null, "지금은 장소 검색이 잘 안 돼요. 잠시 뒤에 다시 말씀해 주세요.");
            default -> new OriginFound(null, "출발하실 곳 '" + name + "'을(를) 찾지 못했어요. 다른 이름으로 말씀해 주시겠어요?");
        };
    }

    /**
     * 카카오는 지금 위치에서 가까운 순이라 "광주역"을 찾으면 가까운 "남광주역"이 먼저 온다 (11-12 시험).
     * 찾은 곳 이름이 말한 이름과 다르면 가까운 순 없이 다시 찾아, 이름이 똑같은 광주 안 장소가 있으면 그곳으로.
     */
    Place exactOrigin(String spoken, Place first) {
        String name = com.saferoute.place.PlaceAlias.of(spoken);
        String want = name.replace(" ", "");
        if (first.name().replace(" ", "").equals(want)) {
            return first;
        }
        try {
            List<Place> all = kakao.search(name, null, null).stream()
                    .map(p -> new Place(p.name(), p.address(), p.lat(), p.lon()))
                    .filter(AssistantTools::inGwangju).toList();
            // 이름이 똑같은 곳 → "광주역 광주선"처럼 그 이름으로 시작하는 곳 (카카오 정확도 순)
            for (Place c : all) {
                if (c.name().replace(" ", "").equals(want)) {
                    return c;
                }
            }
            for (Place c : all) {
                if (c.name().startsWith(name.strip() + " ")) {
                    return c;
                }
            }
        } catch (PlaceSearchException e) {
            log.info("출발지 다시 찾기 실패: {}", e.getMessage());
        }
        return first;
    }

    /** 동·로·길·가 뒤에 번지 숫자 ("서석동 3", "충장로 45-1", "제봉로 100번길 7") */
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
            ".*\\S(동|로|길|가|리)\\s*\\d+(-\\d+)?(번지)?$");

    static boolean looksLikeAddress(String q) {
        return q != null && ADDRESS.matcher(q.strip()).matches();
    }

    /** "서석동", "동구 서석동", "충장로1가"처럼 동 이름만 (카카오 주소 검색이 동 한가운데를 줌, 11-12) */
    private static final java.util.regex.Pattern DONG = java.util.regex.Pattern.compile(
            "^(?:광주\\s*)?(?:[가-힣]{1,3}구\\s*)?[가-힣]{1,6}(?:\\d?동|\\d가)$");

    static boolean looksLikeDong(String q) {
        return q != null && DONG.matcher(q.strip()).matches();
    }

    /** "조선대" → "조선대학교" (두~네 글자 + 대). 아니면 null */
    private static final java.util.regex.Pattern UNIV = java.util.regex.Pattern.compile("^[가-힣]{2,4}대$");

    static String univ(String q) {
        String s = q == null ? "" : q.replace(" ", "");
        return UNIV.matcher(s).matches() ? s + "학교" : null;
    }

    /** 주소로 찾는다. 광주 안 결과가 없으면 앞에 "광주"를 붙여 한 번 더. 그래도 없으면 null (장소 검색으로 넘어감) */
    private Found byAddress(Turn t, String q) {
        try {
            List<Place> in = addressInGwangju(q);
            if (in.isEmpty() && !q.startsWith("광주")) {
                in = addressInGwangju("광주 " + q);
            }
            if (in.isEmpty()) {
                return null;
            }
            return decide(t, in.stream().limit(MAX_CANDIDATES).toList(), in.get(0).name());
        } catch (PlaceSearchException e) {
            log.warn("비서 주소 검색 실패: {}", e.getMessage());
            return null;
        }
    }

    private List<Place> addressInGwangju(String q) {
        List<com.saferoute.place.Place> found = kakao.searchAddress(q);
        if (found == null) {
            return List.of();
        }
        return found.stream().map(p -> new Place(shortAddress(p.name()), p.address(), p.lat(), p.lon()))
                .filter(AssistantTools::inGwangju).toList();
    }

    /** "전남광주통합특별시 동구 서석동 3" → "동구 서석동 3" (말로 읽기 쉽게, 전체 주소는 address 칸에) */
    static String shortAddress(String a) {
        String[] w = a.strip().split("\\s+", 2);
        return w.length == 2 && w[0].contains("광주") ? w[1] : a.strip();
    }

    static boolean inGwangju(Place p) {
        return p.lon() >= GWANGJU[0] && p.lat() >= GWANGJU[1] && p.lon() <= GWANGJU[2] && p.lat() <= GWANGJU[3];
    }

    // ───────────────────────── recommend_trip ─────────────────────────

    /** recommend_trip: 걷기·대중교통 후보를 모아 규칙으로 1등을 정한다. 결과는 t.plan */
    TripRecommender.Plan recommendTrip(Turn t, Place dest) {
        t.tools.add("recommend_trip");
        String mode = t.mode() == null ? "adult" : t.mode();
        Place o = t.convo.origin();
        TripRecommender.Plan plan = trips.recommend(new TripRecommender.Ask(t.guestId, o == null ? t.lat : o.lat(),
                o == null ? t.lon : o.lon(), dest, mode, night(t), t.testWeather, t.profile, t.convo.bodyNote(),
                o == null ? null : o.name()));
        t.plan = plan;
        t.planDest = dest;
        t.weather = plan.weather();
        return plan;
    }

    // ───────────────────────── plan_stops (들를 곳 묶기) ─────────────────────────

    /** plan_stops: 들를 곳을 고르고 출발 → 들를 곳 → 도착 걷는 길을 찾는다. 결과는 t.stopPlan */
    StopPlanner.Plan planStops(Turn t, Place dest, Conversation.Errand errand) {
        t.tools.add("plan_stops");
        String mode = t.mode() == null ? "adult" : t.mode();
        StopPlanner.Plan p = stops == null ? StopPlanner.Plan.fail("지금은 들를 곳을 같이 찾을 수 없어요.")
                : stops.plan(new StopPlanner.Ask(t.guestId, t.convo.origin() == null ? t.lat : t.convo.origin().lat(),
                        t.convo.origin() == null ? t.lon : t.convo.origin().lon(), dest, errand.stops(), errand.ordered(),
                        mode, night(t), t.convo.origin() == null ? null : t.convo.origin().name()));
        t.stopPlan = p;
        t.planDest = dest;
        if (p.failText() != null && p.failText().startsWith(StopPlanner.NOT_FOUND)) {
            t.placeMissed = true;
        }
        return p;
    }

    /** 시험 시각이 있으면 그 시각으로 밤을 정하고, 없으면 엔진이 지금 시각으로 정한다 */
    String night(Turn t) {
        if (!t.testTime || !t.located()) {
            return "auto";
        }
        return nightJudge.judge(t.lat, t.lon, t.now.atZone(KOREA), NightOption.AUTO).night() ? "on" : "off";
    }

    /** 오늘 해 지는 시각 (위치를 모르면 null) */
    java.time.LocalTime sunset(Turn t) {
        if (!t.located()) {
            return null;
        }
        return nightJudge.judge(t.lat, t.lon, t.now.atZone(KOREA), NightOption.AUTO).sunset();
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
        return findNearby(t, kind, t.lat, t.lon);
    }

    /** lat·lon(지금 위치 또는 "문화전당 앞"처럼 말한 곳) 근처 kind 중 가장 가까운 곳. 거리는 그 점에서 잰 것 */
    AssistantReply.Spot findNearby(Turn t, String kind, double lat, double lon) {
        t.tools.add("find_nearby");
        String k = NEARBY.containsKey(kind) ? kind : "toilet";
        boolean wheel = "wheelchair".equals(t.mode()) && ("toilet".equals(k) || "subway".equals(k));
        t.spotKindName = wheel && "toilet".equals(k) ? "장애인 화장실"
                : wheel ? "엘리베이터로 들어갈 수 있는 지하철역" : NEARBY.get(k);
        List<Facility> list;
        try {
            list = facilities.near(lat, lon, k, wheel, NEARBY_RADIUS_M);
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

    // ───────────────────────── 택시 타는 곳 (카카오 장소 검색) ─────────────────────────

    /** 택시 승강장을 찾는 거리. 승강장이 드물어 다른 시설(1km)보다 넓게 */
    static final int TAXI_RADIUS_M = 2000;

    private TaxiStands taxiStands;

    /**
     * 가장 가까운 택시 승강장 (11-13). 우리 DB에는 택시 자료가 없어 카카오 장소 검색("교통,수송 > 택시")을 쓴다.
     * 카카오에 등록된 곳만 나온다 (광주송정역 택시승강장·호출택시승차장 등). 못 찾으면 null, 까닭은 t.spotMessage
     */
    AssistantReply.Spot findTaxi(Turn t) {
        t.tools.add("find_nearby");
        t.spotKindName = "택시 승강장";
        List<com.saferoute.place.Place> found = new ArrayList<>();
        try {
            for (String q : List.of("택시승강장", "택시")) {
                for (com.saferoute.place.Place p : kakao.search(q, t.lat, t.lon)) {
                    if (taxiStand(p.name()) && p.distanceM() != null && p.distanceM() <= TAXI_RADIUS_M
                            && found.stream().noneMatch(x -> x.name().equals(p.name()))) {
                        found.add(p);
                    }
                }
                if (!found.isEmpty()) {
                    break;
                }
            }
        } catch (PlaceSearchException e) {
            log.warn("비서 택시 승강장 검색 실패: {}", e.getMessage());
        }
        // 카카오에 없는 광주 시내 승강장 손 목록 (11-13)
        for (com.saferoute.place.Place p : taxiStands.around(t.lat, t.lon)) {
            if (p.distanceM() <= TAXI_RADIUS_M) {
                found.add(p);
            }
        }
        if (found.isEmpty()) {
            t.spotMessage = "2km 안에 등록된 택시 승강장을 찾지 못했어요. 큰길에서 택시를 잡으시거나 택시 앱으로 불러 주세요.";
            return null;
        }
        found.sort(java.util.Comparator.comparingInt(com.saferoute.place.Place::distanceM));
        com.saferoute.place.Place p = found.get(0);
        t.spot = new AssistantReply.Spot("taxi", p.name(), p.lat(), p.lon(), p.distanceM(), null);
        return t.spot;
    }

    /** "광주송정역 택시승강장", "광주송정역 호출택시승차장"처럼 택시 타는 곳 이름이면 true (택시 회사·정비소는 아님) */
    static boolean taxiStand(String name) {
        String f = name == null ? "" : name.replace(" ", "");
        return f.contains("택시") && (f.contains("승강장") || f.contains("승차장") || f.contains("타는곳") || f.contains("정류장"));
    }

    /** 버스 정류장 찾는 반경 (앱 /api/bus/nearby 500m보다 넓게: 말로 묻는 사람은 조금 멀어도 알려 줌) */
    static final int BUS_STOP_RADIUS_M = 1000;

    private com.saferoute.bus.BusStopRepository busStops;

    private com.saferoute.bus.BusLineRepository busLines;

    /** 버스 노선 표 (DB 없는 테스트에서는 안 넣음) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBusLines(com.saferoute.bus.BusLineRepository busLines) {
        this.busLines = busLines;
    }

    /** "03번 버스 어디로 가?" → 노선 한 줄씩 (최대 3개). 못 찾으면 null */
    String busLineInfo(Turn t, String number) {
        t.tools.add("find_nearby");
        if (busLines == null) {
            return null;
        }
        try {
            List<com.saferoute.bus.BusLine> found = busLines.find(number);
            if (found.isEmpty()) {
                return number + "번 버스는 광주 버스 목록에서 찾지 못했어요. 번호를 다시 말씀해 주시겠어요?";
            }
            StringBuilder sb = new StringBuilder();
            for (com.saferoute.bus.BusLine l : found.stream().limit(3).toList()) {
                sb.append(sb.isEmpty() ? "" : " ").append(l.name()).append("번은 ").append(l.summary()).append("예요.");
            }
            if (found.size() > 3) {
                sb.append(" 이름이 비슷한 버스가 ").append(found.size() - 3).append("개 더 있어요.");
            }
            return sb.toString();
        } catch (DataAccessException e) {
            log.warn("비서 노선 조회 실패: {}", e.getMostSpecificCause().getMessage());
            return "지금은 버스 노선 정보를 불러오지 못했어요. 잠시 뒤에 다시 물어봐 주세요.";
        }
    }

    private com.saferoute.subway.SubwayRepository subway;

    /** 지하철 표 (DB 없는 테스트에서는 안 넣음) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSubway(com.saferoute.subway.SubwayRepository subway) {
        this.subway = subway;
    }

    /** 장소에서 가장 가까운 지하철 출구를 찾는 반경 (출구 표에 장소가 없을 때) */
    static final int EXIT_NEAR_M = 600;

    /** 출구 주변 장소 표에서 이보다 멀면 "꽤 멀어요"를 붙인다 */
    static final int EXIT_FAR_M = 1000;

    /**
     * "문화전당역에서 ○○ 가려면 몇 번 출구로 나가요?" (11-23).
     * ① 광주교통공사 출구 주변 장소 표(2021)에 있으면 그 출구 ② 없으면 카카오로 장소를 찾아 가장 가까운 출구(OSM).
     * "5번 출구에 뭐 있어?"는 그 출구 주변 장소, "28번 버스는 몇 번 출구?"는 출구 연계 버스 표(2021).
     * 휠체어·어르신이면 그 역의 엘리베이터 출구도 말한다. 지하철 표가 없으면 null
     */
    String subwayExit(Turn t, com.saferoute.subway.SubwayExitQuery.Ask a) {
        t.tools.add("find_nearby");
        if (subway == null) {
            return null;
        }
        try {
            if (a.place() != null) {
                return exitForPlace(t, a);
            }
            if (a.bus() != null) {
                List<com.saferoute.subway.SubwayRepository.ExitBus> buses = subway.exitBuses(a.station(), a.bus());
                String st = stationName(buses.isEmpty() ? a.station() : buses.get(0).station());
                if (buses.isEmpty()) {
                    return st + "역 출구 자료에 " + a.bus() + "번 버스는 없어요.";
                }
                String exits = String.join("·", buses.stream().map(com.saferoute.subway.SubwayRepository.ExitBus::exitNo)
                        .distinct().toList());
                return st + "역에서 " + a.bus() + "번 버스는 " + exits + "번 출구 쪽에서 타요. " + old(null)
                        + elevatorTip(t, st, null);
            }
            List<com.saferoute.subway.SubwayRepository.ExitPlace> at = subway.placesAtExit(a.station(), a.exitNo(), 3);
            String st = stationName(at.isEmpty() ? a.station() : at.get(0).station());
            if (at.isEmpty()) {
                return st + "역 " + a.exitNo() + "번 출구 주변 장소 정보는 없어요.";
            }
            String names = String.join(", ", at.stream().map(com.saferoute.subway.SubwayRepository.ExitPlace::place).toList());
            return st + "역 " + a.exitNo() + "번 출구 근처에는 " + Josa.iGa(names) + " 있어요. " + old(at.get(0).baseDate())
                    + elevatorTip(t, st, a.exitNo());
        } catch (DataAccessException e) {
            log.warn("비서 지하철 출구 조회 실패: {}", e.getMostSpecificCause().getMessage());
            return "지금은 지하철 출구 정보를 불러오지 못했어요. 잠시 뒤에 다시 물어봐 주세요.";
        }
    }

    private String exitForPlace(Turn t, com.saferoute.subway.SubwayExitQuery.Ask a) {
        List<com.saferoute.subway.SubwayRepository.ExitPlace> rows = subway.exitPlaces(a.place(), a.station(), 3);
        String alias = com.saferoute.place.PlaceAlias.of(a.place());
        if (rows.isEmpty() && !alias.equals(a.place())) {
            rows = subway.exitPlaces(alias, a.station(), 3);
        }
        rows = rows.stream().filter(r -> r.exitNo() != null && !r.exitNo().isBlank()).toList();
        if (!rows.isEmpty()) {
            com.saferoute.subway.SubwayRepository.ExitPlace r = rows.get(0);
            String st = stationName(r.station());
            String how = r.walkMin() != null && r.walkMin() > 0 ? "(걸어서 약 " + r.walkMin() + "분)"
                    : r.distanceM() != null && r.distanceM() > 0 ? "(약 " + r.distanceM() + "m)" : "";
            StringBuilder sb = new StringBuilder(Josa.eun(r.place()) + " " + st + "역 " + r.exitNo() + "번 출구로 나가시면 돼요"
                    + how + ".");
            if (r.distanceM() != null && r.distanceM() > EXIT_FAR_M) {
                // 표에 2km(걸어서 31분)인 곳도 있다 (학교 DB 11-23)
                sb.append(" 출구에서 꽤 멀어서 버스나 택시가 나을 수 있어요.");
            }
            if (a.station() == null) {
                rows.stream().skip(1).filter(o -> !o.station().equals(r.station())).findFirst().ifPresent(o -> sb
                        .append(" ").append(stationName(o.station())).append("역 ").append(o.exitNo())
                        .append("번 출구로도 갈 수 있어요."));
            }
            return sb.append(" ").append(old(r.baseDate())).append(elevatorTip(t, st, r.exitNo())).toString();
        }
        Place p = firstPlace(t, a.place());
        if (p == null) {
            return "'" + a.place() + "'을(를) 찾지 못했어요. 다른 이름으로 말씀해 주시겠어요?";
        }
        com.saferoute.subway.SubwayRepository.Entrance e = subway.nearestEntrance(p.lat(), p.lon(), EXIT_NEAR_M);
        if (e == null) {
            return p.name() + " 근처 " + EXIT_NEAR_M + "m 안에는 지하철 출구가 없어요. '" + p.name() + " 가자'라고 말씀하시면 가는 길을 찾아 드려요.";
        }
        String st = stationName(e.station());
        String ev = Boolean.TRUE.equals(e.elevator()) && elevatorMode(t) ? " 그 출구에 엘리베이터가 있어요." : "";
        return p.name() + "에서 가장 가까운 지하철 출구는 " + st + "역 " + e.exitNo() + "번 출구예요(약 " + e.distanceM() + "m)."
                + (ev.isEmpty() ? elevatorTip(t, st, e.exitNo()) : ev);
    }

    /** 공공데이터 출구 표는 2021년 자료라 바뀌었을 수 있다고 한마디 */
    private static String old(java.time.LocalDate base) {
        int year = base == null ? 2021 : base.getYear();
        return year + "년 자료라 지금과 다를 수 있어요.";
    }

    /** "문화전당(구도청)" → "문화전당" */
    static String stationName(String name) {
        return name == null ? "" : name.replaceAll("\\s*\\(.*\\)", "").strip();
    }

    private static boolean elevatorMode(Turn t) {
        return "wheelchair".equals(t.mode()) || "senior".equals(t.mode());
    }

    /** 휠체어·어르신이면 그 역의 엘리베이터 출구. exitNo가 그중 하나면 "그 출구에 엘리베이터가 있어요" */
    private String elevatorTip(Turn t, String station, String exitNo) {
        if (!elevatorMode(t) || station.isEmpty()) {
            return "";
        }
        List<String> ev = subway.elevatorExits(station);
        if (ev.isEmpty()) {
            return "";
        }
        if (exitNo != null && ev.contains(exitNo)) {
            return " 그 출구에 엘리베이터가 있어요.";
        }
        return " 엘리베이터는 " + String.join("·", ev) + "번 출구에 있어요.";
    }

    private com.saferoute.bus.BusLineStops lineStops;

    static final int ROUTE_SAY = 6;

    /**
     * "03번 노선도 보여줘" (11-13): 기점 → 주요 정류장 → 종점, 정류장 몇 개. 말로는 6곳만 (처음·끝 + 사이 고르게).
     * 전체 목록·좌표는 GET /api/bus/lines/stops?number=03
     */
    String busRouteMap(Turn t, String number) {
        t.tools.add("find_nearby");
        if (busLines == null) {
            return null;
        }
        try {
            List<com.saferoute.bus.BusLine> found = busLines.find(number);
            if (found.isEmpty()) {
                return number + "번 버스는 광주 버스 목록에서 찾지 못했어요. 번호를 다시 말씀해 주시겠어요?";
            }
            com.saferoute.bus.BusLine l = found.get(0);
            List<com.saferoute.bus.LineStop> route = lineStops == null ? List.of() : lineStops.route(l.id(), l.to());
            if (route.size() < 2) {
                return l.name() + "번은 " + l.summary() + "예요. 중간에 서는 정류장 정보는 아직 준비 중이에요.";
            }
            int n = route.size();
            int say = Math.min(ROUTE_SAY, n);
            List<String> middle = new ArrayList<>();
            for (int k = 1; k < say - 1; k++) {
                middle.add(route.get((int) Math.round((double) k * (n - 1) / (say - 1))).name());
            }
            String from = route.get(0).name();
            String to = route.get(n - 1).name();
            return l.name() + "번은 " + from + "에서 출발해 "
                    + (middle.isEmpty() ? "" : Josa.eul(String.join(", ", middle)) + " 지나 ")
                    + to + "까지 가요. 정류장은 모두 " + n + "개예요.";
        } catch (DataAccessException e) {
            log.warn("비서 노선도 조회 실패: {}", e.getMostSpecificCause().getMessage());
            return "지금은 버스 노선 정보를 불러오지 못했어요. 잠시 뒤에 다시 물어봐 주세요.";
        }
    }

    /** 노선마다 지나는 정류장 (DB 없는 테스트에서는 안 넣음 → 기점·종점으로만) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLineStops(com.saferoute.bus.BusLineStops lineStops) {
        this.lineStops = lineStops;
    }

    /** 노선별 정류장 파일을 읽었는지 (없으면 "지나가는 버스"는 모르고 기점·종점만 앎) */
    boolean passingKnown() {
        return lineStops != null && lineStops.loaded() > 0;
    }

    /**
     * "수완동으로 가는 버스" (11-13): 그곳을 지나는 노선(광주버스정보 노선별 정류장) + 그쪽이 기점·종점인 노선.
     * 없으면 null. 노선 표를 못 읽으면 빈 문자열
     */
    String busLinesTo(Turn t, String place) {
        t.tools.add("find_nearby");
        if (busLines == null) {
            return "";
        }
        try {
            List<String> parts = new ArrayList<>();
            int total;
            if (lineStops != null) {
                Place at = firstPlace(t, com.saferoute.place.BusLineQuery.anchor(place));
                List<com.saferoute.bus.BusLineStops.Hit> hits = lineStops.to(place, at == null ? null : at.lat(),
                        at == null ? null : at.lon());
                total = hits.size();
                if (!hits.isEmpty() && !lineStops.stopsOf(hits.get(0).line().id()).isEmpty()) {
                    t.busLine = hits.get(0).line();
                }
                for (com.saferoute.bus.BusLineStops.Hit h : hits.stream().limit(3).toList()) {
                    parts.add(h.line().name() + "번(" + (h.via() == null ? h.line().summary()
                            : Josa.eul(h.via().name()) + " 지나요, " + h.line().from() + " ↔ " + h.line().to()) + ")");
                }
            } else {
                List<com.saferoute.bus.BusLine> found = busLines.endingAt(com.saferoute.place.BusLineQuery.stem(place));
                total = found.size();
                for (com.saferoute.bus.BusLine l : found.stream().limit(3).toList()) {
                    parts.add(l.name() + "번(" + l.summary() + ")");
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            return Josa.ro(place) + " 가는 버스는 " + String.join(", ", parts) + (total > 3 ? " 등이 있어요." : "이 있어요.");
        } catch (DataAccessException e) {
            log.warn("비서 노선 조회 실패: {}", e.getMostSpecificCause().getMessage());
            return "";
        }
    }

    /**
     * "03번이 서는 가장 가까운 정류장으로 안내할까요?"에 네 (11-13): 정류장 파일에서 그 노선이 서는 곳 중
     * 지금 위치에서 가장 가까운 곳을 [여기로 가기] 자리로
     */
    AssistantReply.Spot findLineStop(Turn t, int lineId, String lineName) {
        t.tools.add("find_nearby");
        t.spotKindName = lineName + "번이 서는 정류장";
        com.saferoute.bus.LineStop s = lineStops == null || !t.located() ? null
                : lineStops.nearestStop(lineId, t.lat, t.lon);
        if (s == null) {
            t.spotMessage = lineName + "번이 서는 정류장 정보를 아직 못 불러왔어요.";
            return null;
        }
        int m = (int) Math.round(com.saferoute.bus.BusLineStops.meters(t.lat, t.lon, s.lat(), s.lon()));
        t.spot = new AssistantReply.Spot("bus_stop", com.saferoute.place.BusStopQuery.stopName(s.name()), s.lat(), s.lon(),
                m, null);
        return t.spot;
    }

    /** 버스 정류장 표 (DB 없는 테스트에서는 안 넣음 → "지금은 못 찾아요") */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBusStops(com.saferoute.bus.BusStopRepository busStops) {
        this.busStops = busStops;
    }

    /**
     * "근처 버스", "가까운 버스 정류장" (11-13): 카카오가 아니라 DB 정류장 표에서 가장 가까운 곳.
     * 카카오 키워드 "버스"는 이름에 버스가 든 가게·먼 정류장을 섞어 줘서 쓰지 않는다
     */
    AssistantReply.Spot findBusStop(Turn t) {
        return findBusStop(t, t.lat, t.lon);
    }

    /** lat·lon(지금 위치 또는 "광주역 근처"처럼 말한 곳)에서 가장 가까운 정류장 */
    AssistantReply.Spot findBusStop(Turn t, double lat, double lon) {
        t.tools.add("find_nearby");
        t.spotKindName = "버스 정류장";
        List<com.saferoute.bus.BusStop> found;
        try {
            found = busStops == null ? null : busStops.near(lat, lon, BUS_STOP_RADIUS_M, 1);
        } catch (DataAccessException e) {
            log.warn("비서 정류장 검색 실패: {}", e.getMostSpecificCause().getMessage());
            found = null;
        }
        if (found == null) {
            t.spotMessage = "지금은 정류장 정보를 불러오지 못했어요. 잠시 뒤에 다시 물어봐 주세요.";
            return null;
        }
        if (found.isEmpty()) {
            t.spotMessage = "1km 안에 버스 정류장이 없어요.";
            return null;
        }
        com.saferoute.bus.BusStop s = found.get(0);
        t.spot = new AssistantReply.Spot("bus_stop", com.saferoute.place.BusStopQuery.stopName(s.name()), s.lat(), s.lon(),
                s.distanceM(), null);
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
        reports.createVoice((String) a.get("type"), t.mode(), (String) a.get("memo"), t.lat, t.lon, t.guestId);
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
