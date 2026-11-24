package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.saferoute.bus.BusArrival;
import com.saferoute.report.ReportItem;
import com.saferoute.report.ReportRepository;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.transit.TransitException;
import com.saferoute.transit.TransitResult;
import com.saferoute.transit.TransitService;
import com.saferoute.walk.WalkSpeed;
import com.saferoute.walk.WalkSpeedService;

/**
 * recommend_trip 도구 (팀 비서 설계의 '큰 도구 하나'). 목적지가 정해지면 한 번 불러서
 * 걷는 길(/api/route, 원일님 엔진, 행사·돌발 routes[].events 포함) + 대중교통(/api/transit) + 날씨·미세먼지
 * + 불편 신고 + 내 습관(trip_weather_choice) + 내 걸음 속도를 모으고, 추천 규칙(TripScorer)으로 1등과 이유를 정한다.
 * 숫자는 모두 이 결과에서만 나온다. 길 계산은 원일님 엔진이 하고, 여기서는 고르기만 한다.
 */
@Component
public class TripRecommender {

    private static final Logger log = LoggerFactory.getLogger(TripRecommender.class);
    private static final int MAX_REPORTS = 300;

    private final RouteService routes;
    private final TransitService transit;
    private final WeatherDustService weatherDust;
    private final ReportRepository reports;
    private final WalkSpeedService walkSpeed;
    private final MemoryRepository memory;
    private final TripScorer scorer;
    private final int reportNearM;

    public TripRecommender(RouteService routes, TransitService transit, WeatherDustService weatherDust,
            ReportRepository reports, WalkSpeedService walkSpeed, MemoryRepository memory, TripScorer scorer,
            @Value("${assistant.report-near-m:30}") int reportNearM) {
        this.routes = routes;
        this.transit = transit;
        this.weatherDust = weatherDust;
        this.reports = reports;
        this.walkSpeed = walkSpeed;
        this.memory = memory;
        this.scorer = scorer;
        this.reportNearM = reportNearM;
    }

    /**
     * 무엇을 추천할지 정할 때 쓰는 값.
     *
     * @param night    "auto", "on", "off" (시험 시각이 있으면 그 시각으로 정한 on·off)
     * @param weather  시험 날씨. null 이면 기상청·에어코리아에 물어봄
     * @param profile  내 정보 (없으면 null)
     * @param bodyNote 오늘 몸 상태 ("무릎이 아파"). 없으면 null
     */
    public record Ask(String guestId, double lat, double lon, Conversation.Place dest, String mode, String night,
                      WeatherDust weather, Profile profile, String bodyNote, String fromName) {

        public Ask(String guestId, double lat, double lon, Conversation.Place dest, String mode, String night,
                   WeatherDust weather, Profile profile, String bodyNote) {
            this(guestId, lat, lon, dest, mode, night, weather, profile, bodyNote, null);
        }

        /** 출발 이름 ("광주역에서"라고 말했으면 그 이름, 아니면 지금 위치) */
        String from() {
            return fromName == null || fromName.isBlank() ? "지금 위치" : fromName;
        }
    }

    /**
     * 추천 결과.
     *
     * @param scored   1등·다른 후보·이유. 걷는 길도 대중교통도 없으면 null
     * @param weather  날씨·미세먼지 판단
     * @param night    밤이면 true
     * @param failText 둘 다 못 찾았을 때 사용자에게 할 말. 아니면 null
     */
    /**
     * @param why 엔진이 고른 걷는 길(safe)의 이유 한 줄 (explanation.summary, 예 "인도가 있는 길로 갑니다"). 없으면 null.
     *            비서가 "왜 이 길이야?"에 그대로 말한다 (11-13 원일님 부탁)
     */
    /**
     * @param notes 엔진이 걷는 길(safe)에 단 알림 (explanation.notes, 예 "횡단보도 없이 길을 건너요", "○○은 3번 출구로 들어가요").
     *              비서가 걷는 길 답 끝에 한두 개 붙인다 ({@link Sentences#withNotes}). 없으면 빈 목록
     */
    public record Plan(TripScorer.Scored scored, WeatherDust weather, boolean night, String failText, String why,
            List<RouteResult.Note> notes) {
        public Plan {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public Plan(TripScorer.Scored scored, WeatherDust weather, boolean night, String failText, String why) {
            this(scored, weather, night, failText, why, List.of());
        }

        public Plan(TripScorer.Scored scored, WeatherDust weather, boolean night, String failText) {
            this(scored, weather, night, failText, null);
        }
    }

    public Plan recommend(Ask a) {
        CompletableFuture<WeatherDust> wdFuture = a.weather() == null ? weatherDust.start(a.lat(), a.lon()) : null;
        // 비서가 받은 날씨(시험 날씨 포함)를 엔진에도 넘긴다. 날씨를 아직 모르면 null = auto (엔진·대중교통이 한 번 물음)
        String rain = a.weather() == null ? null : a.weather().rain() ? "on" : "off";
        RouteRequest req = new RouteRequest(new RouteRequest.Place(a.lat(), a.lon(), a.from()),
                new RouteRequest.Place(a.dest().lat(), a.dest().lon(), a.dest().name()), a.night(), a.mode(),
                blankToNull(a.guestId()), rain);

        List<Trip> trips = new ArrayList<>();
        boolean night = "on".equals(a.night());
        WalkSpeed speed = speed(a.guestId(), a.mode());
        boolean personal = speed != null && speed.personal();
        String failText = null;
        String why = null;
        List<RouteResult.Note> notes = List.of();

        // 1. 걷는 길 (원일님 엔진): 최단·맞춤 중 피할 것이 적은 길을 걷기 후보로
        try {
            RouteResult r = routes.route(req);
            night = r.conditions() != null && r.conditions().night();
            Map<String, Integer> near = reportsNear(r.routes());
            RouteResult.Route w = bestWalk(r.routes(), near);
            if (w != null) {
                int minutes = personal ? (int) Math.ceil(w.distanceM() / speed.speedMMin()) : w.durationMin();
                Map<String, Integer> diff = r.explanation() == null || r.explanation().diff() == null
                        ? Map.of() : r.explanation().diff();
                why = engineWhy(r, w);
                notes = engineNotes(r, w);
                trips.add(new Trip("walk", minutes, w.distanceM(), 0, near.getOrDefault(w.kind(), 0),
                        minutes + "분, " + String.format("%,d", w.distanceM()) + "m",
                        request(req), w.kind(), diff, w.events() == null ? List.of() : w.events(), false, null,
                        w.counts() == null ? null : w.counts().get("rest"), List.of(), w));
                // 최단 길이 피할 행사를 지나면 이유에 "○○ 피함"을 쓰려고 후보로만 남긴다 (점수에서 밀림)
                for (RouteResult.Route o : r.routes()) {
                    if (o != w && o.events() != null && o.events().stream().anyMatch(RouteResult.Event::avoid)) {
                        int m = personal ? (int) Math.ceil(o.distanceM() / speed.speedMMin()) : o.durationMin();
                        trips.add(new Trip("walk", m, o.distanceM(), 0, near.getOrDefault(o.kind(), 0),
                                m + "분, " + String.format("%,d", o.distanceM()) + "m",
                                request(req),
                                o.kind(), Map.of(), o.events(), false, null,
                                o.counts() == null ? null : o.counts().get("rest"), List.of(), o));
                    }
                }
            }
        } catch (RouteException e) {
            failText = e.getMessage();
        } catch (RuntimeException e) {
            log.warn("비서 걷는 길 조회 실패: {}", e.getClass().getSimpleName());
            failText = "지금은 길을 찾을 수 없어요.";
        }

        // 2. 대중교통 (ODsay + 우리 걷기 구간). 가까우면(700m 안) ODsay 가 안 찾아 주니 건너뛴다
        try {
            TransitResult t = transit.search(req);
            if (t.paths() != null && !t.paths().isEmpty()) {
                TransitResult.TransitPath p = t.paths().get(0);
                BusArrival bus = firstBus(p);
                List<TransitResult.Leg> legs = mySpeed(p.legs(), speed);
                int total = p.totalMin() + legs.stream().mapToInt(TransitResult.Leg::minutes).sum()
                        - p.legs().stream().mapToInt(TransitResult.Leg::minutes).sum();
                trips.add(new Trip("transit", total, p.walkM(), p.transfers(), 0,
                        lines(p) + ", " + total + "분", request(req), null, Map.of(), List.of(),
                        bus != null && bus.lowFloor(),
                        bus == null || bus.remainMin() == null ? null
                                : bus.lineName().strip() + "번 " + bus.remainMin() + "분 뒤", null, legs));
            }
        } catch (TransitException e) {
            log.info("비서 대중교통 후보 없음: {}", e.code());
        } catch (RuntimeException e) {
            log.warn("비서 대중교통 조회 실패: {}", e.getClass().getSimpleName());
        }

        WeatherDust w = a.weather() != null ? a.weather() : weatherDust.await(wdFuture);
        if (trips.isEmpty()) {
            return new Plan(null, w, night, failText == null ? "갈 수 있는 길을 찾지 못했어요." : failText);
        }
        Profile pf = a.profile();
        TripScorer.Context ctx = new TripScorer.Context(a.mode(), w.rain(), w.heat(), w.cold(), w.dustBad(), night,
                personal, pf == null ? null : pf.preferTransit(), habit(a.guestId(), w), a.bodyNote());
        return new Plan(scorer.score(trips, ctx), w, night, null, why, notes);
    }

    /** 엔진 설명(explanation.summary)은 safe 길을 고른 이유라서, 걷기 후보가 safe 길일 때만 쓴다 */
    static String engineWhy(RouteResult r, RouteResult.Route w) {
        if (r.explanation() == null || w == null || !"safe".equals(w.kind())) {
            return null;
        }
        String s = r.explanation().summary();
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** 엔진 알림(explanation.notes)도 safe 길에 대한 것이라 걷기 후보가 safe 길일 때만 쓴다 */
    static List<RouteResult.Note> engineNotes(RouteResult r, RouteResult.Route w) {
        if (r.explanation() == null || r.explanation().notes() == null || w == null || !"safe".equals(w.kind())) {
            return List.of();
        }
        return r.explanation().notes();
    }

    /** 날씨가 같았던 지난 이동에서 더 많이 고른 것 (transit, walk). 기록이 없거나 같으면 null */
    String habit(String guestId, WeatherDust w) {
        if (guestId == null) {
            return null;
        }
        String weather = w.rain() ? "rain" : w.heat() ? "heat" : w.cold() ? "cold" : w.dustBad() ? "dust" : "clear";
        try {
            MemoryRepository.WeatherChoice c = memory.weatherChoice(guestId, weather);
            if (c == null || c.byTransit() == c.byWalk()) {
                return null;
            }
            return c.byTransit() > c.byWalk() ? "transit" : "walk";
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** 앱이 /api/route·/api/transit 에 그대로 보낼 값 */
    static Map<String, Object> request(RouteRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origin", Map.of("lat", req.origin().lat(), "lon", req.origin().lon(), "name", req.origin().name()));
        m.put("destination", Map.of("lat", req.destination().lat(), "lon", req.destination().lon(),
                "name", req.destination().name()));
        m.put("night", req.night());
        m.put("mode", req.mode());
        if (req.rain() != null) {
            m.put("rain", req.rain()); // 앱이 같은 요청으로 다시 찾아도 같은 비 판단
        }
        return m;
    }

    /** 걷는 구간 분을 걸음 속도로 다시 센다 (잰 적이 있으면 내 속도, 없으면 모드 기본 속도. 속도를 못 읽으면 그대로) */
    static List<TransitResult.Leg> mySpeed(List<TransitResult.Leg> legs, WalkSpeed speed) {
        if (legs == null) {
            return List.of();
        }
        if (speed == null || speed.speedMMin() <= 0) {
            return legs;
        }
        List<TransitResult.Leg> out = new ArrayList<>();
        for (TransitResult.Leg l : legs) {
            if (!"walk".equals(l.type())) {
                out.add(l);
                continue;
            }
            int m = (int) Math.ceil(l.distanceM() / speed.speedMMin());
            out.add(new TransitResult.Leg(l.type(), l.distanceM(), m, l.from(), l.to(), l.lines(), l.way(),
                    l.stationCount(), l.safeWalk(), l.geometry(), l.sidewalks(), l.liveAvailable(), l.buses(),
                    l.trains(), l.stops(), l.walkRoute()));
        }
        return out;
    }

    /** "버스 30번 → 지하철 1호선" */
    static String lines(TransitResult.TransitPath p) {
        List<String> parts = new ArrayList<>();
        for (TransitResult.Leg leg : p.legs()) {
            if ("bus".equals(leg.type())) {
                parts.add("버스 " + String.join("·", leg.lines()) + "번");
            } else if ("subway".equals(leg.type())) {
                parts.add("지하철 " + String.join("·", leg.lines()));
            }
        }
        return parts.isEmpty() ? "대중교통" : String.join(" → ", parts);
    }

    /** 첫 버스 구간에서 가장 먼저 오는 버스 (저상버스가 있으면 그것) */
    static BusArrival firstBus(TransitResult.TransitPath p) {
        for (TransitResult.Leg leg : p.legs()) {
            if ("bus".equals(leg.type())) {
                if (leg.buses() == null || leg.buses().isEmpty()) {
                    return null;
                }
                return leg.buses().stream().filter(BusArrival::lowFloor).findFirst().orElse(leg.buses().get(0));
            }
            if ("subway".equals(leg.type())) {
                return null;
            }
        }
        return null;
    }

    /** 게스트 번호가 비었으면 null (엔진은 null 이면 모드 기준만 씀) */
    static String blankToNull(String guestId) {
        return guestId == null || guestId.isBlank() ? null : guestId;
    }

    private WalkSpeed speed(String guestId, String mode) {
        if (guestId == null || guestId.isBlank()) {
            return null;
        }
        try {
            return walkSpeed.get(guestId, mode);
        } catch (RuntimeException e) {
            return null; // 잘못된 번호·DB 오류면 엔진 기본 시간을 쓴다
        }
    }

    /** 불편 신고가 더 적은 길, 같으면 피할 행사가 적은 길, 그것도 같으면 맞춤 길(safe) */
    static RouteResult.Route bestWalk(List<RouteResult.Route> list, Map<String, Integer> near) {
        RouteResult.Route best = null;
        for (RouteResult.Route r : list) {
            if (best == null || better(r, best, near)) {
                best = r;
            }
        }
        return best;
    }

    private static boolean better(RouteResult.Route a, RouteResult.Route b, Map<String, Integer> near) {
        int ra = near.getOrDefault(a.kind(), 0);
        int rb = near.getOrDefault(b.kind(), 0);
        if (ra != rb) {
            return ra < rb;
        }
        long ea = a.events() == null ? 0 : a.events().stream().filter(RouteResult.Event::avoid).count();
        long eb = b.events() == null ? 0 : b.events().stream().filter(RouteResult.Event::avoid).count();
        if (ea != eb) {
            return ea < eb;
        }
        return "safe".equals(a.kind());
    }

    /** 길마다 reportNearM 안에 있는 불편 신고 수 (숨긴 신고 제외). DB가 안 되면 모두 0 */
    Map<String, Integer> reportsNear(List<RouteResult.Route> list) {
        Map<String, Integer> out = new LinkedHashMap<>();
        double west = 180, south = 90, east = -180, north = -90;
        for (RouteResult.Route r : list) {
            out.put(r.kind(), 0);
            for (double[] p : r.geometry()) {
                west = Math.min(west, p[0]);
                east = Math.max(east, p[0]);
                south = Math.min(south, p[1]);
                north = Math.max(north, p[1]);
            }
        }
        if (west > east) {
            return out;
        }
        double pad = 0.0005; // 약 50m. 길 바로 옆 신고도 범위에 들게
        List<ReportItem> items;
        try {
            items = reports.within(west - pad, south - pad, east + pad, north + pad, MAX_REPORTS);
        } catch (DataAccessException e) {
            log.warn("비서 불편 신고 조회 실패: {}", e.getMostSpecificCause().getMessage());
            return out;
        }
        for (RouteResult.Route r : list) {
            int n = 0;
            for (ReportItem it : items) {
                if (Geo.distanceToLine(new double[] {it.lon(), it.lat()}, r.geometry()) <= reportNearM) {
                    n++;
                }
            }
            out.put(r.kind(), n);
        }
        return out;
    }
}
