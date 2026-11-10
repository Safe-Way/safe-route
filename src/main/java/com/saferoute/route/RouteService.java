package com.saferoute.route;

import java.time.Clock;
import java.util.ArrayList;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.PoiSource;
import com.saferoute.route.data.PreferenceSource;
import com.saferoute.route.data.RoadGraphSource;
import com.saferoute.route.data.UserPreferences;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadEvents;
import com.saferoute.route.engine.explain.ExplanationBuilder;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;
import com.saferoute.situation.NightResult;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

/**
 * 경로 엔진의 입구 (요구사항 8.1). 순서만 정하고, 각 단계는 맡은 클래스가 한다.
 * <ol>
 *   <li>요청 검사 (SAME_POINT, TOO_FAR, OUT_OF_AREA)</li>
 *   <li>모드 기준에 그 사람 선호(계단·우회 한도·쉬는 간격)를 덮기: {@link PreferenceSource}. 요청에 guestId가 있을 때만</li>
 *   <li>밤 판단: C파트 {@link NightJudge}. 어린이처럼 일몰 몇 분 전부터 밤으로 보는 모드는 여기서 앞당긴다</li>
 *   <li>비 판단 (2026-11-09): 요청 rain(on·off), auto면 C파트 {@link WeatherDustService}(기상청, 같은 동네 10분 기억).
 *       비면 지붕 없는 길의 부담을 (1 + rain.walk-more)배로 보고, 넓힌 우회 한도 max(한도, 1 + walk-more)는 지붕 이득이 있을 때만 쓰며,
 *       운행 시간 안이면 지하 길도 도로망에 넣고 출발·도착은 지상 길에만 붙인다.
 *       대중교통 걷는 구간·비서 추천은 길찾기 창구를 거치지 않아, 밤처럼 엔진이 C파트에 직접 묻는다</li>
 *   <li>주변 도로망 읽기, 출발·도착을 길에 붙이기: {@link RoadGraphSource}. 휠체어는 지날 수 있는 길로 이어진 곳에 붙인다</li>
 *   <li>주변 시설을 한 번 읽기: {@link PoiSource}. 점수·설명·지도 아이콘이 같은 목록을 쓴다</li>
 *   <li>지금 진행 중인 행사·돌발상황 읽기: 모드에 따라 둘레를 조건부로 피하거나({@link RouteProperties.Events}) 지날 때 알린다</li>
 *   <li>모드·낮밤 기준으로 길마다 더할 비용: {@link RouteScorer} (시설 커버율을 요청 때 계산)</li>
 *   <li>최단·안전 경로: {@link RouteFinder}. 못 찾으면 범위를 넓혀 한 번 더 찾는다.
 *       휠체어가 비포장을 지나면 피할 때 얼마나 더 걸리는지 설명에 알린다</li>
 *   <li>시설 세기, 설명, 응답 만들기: {@link RouteResultAssembler}</li>
 * </ol>
 * 들를 곳(stops, 최대 3곳, 2026-11-10): 같은 도로망에 모든 점을 붙이고 구간마다 위 찾기를 돌려 이은 뒤 응답은 한 번만 만든다.
 * 그래서 시설 수·꺾는 곳·계단·행사 구간이 전체 경로 기준이다. 한 구간이라도 못 찾으면 그 들를 곳 이름으로 NO_ROUTE
 * 사용: {@code RouteResult r = routeService.route(request);}
 * 경로를 못 주면 {@link RouteException}을 던진다. {@code error().httpStatus()}와 {@code error().name()}을
 * 그대로 응답에 쓰면 된다. night·rain 값이 auto·on·off가 아니거나 mode를 모르면 IllegalArgumentException(400으로 보면 됨).
 *
 * 사용자 위치를 남기지 않도록 출발·도착 좌표는 로그에 쓰지 않는다 (NFR-07).
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    /** 행사·돌발상황을 읽을 때 도로망 범위 밖으로 더 보는 거리. 영향 반경(행사 300m)보다 크게 */
    static final double EVENT_MARGIN_M = 500;

    /** 들를 곳 두 개가 이 거리(m) 안이면 같은 곳으로 본다. 좌표를 두 번 보낸 실수만 거르고, 붙어 있는 다른 가게는 받는다 */
    static final double SAME_STOP_M = 1;

    /** 들를 곳 최대 개수. 앱이 최대 3곳까지 받는다 (진행표 w10, 2026-11-09) */
    static final int MAX_STOPS = 3;

    /** 경로에 쓰는 시설 종류 (점수·설명·지도 아이콘) */
    static final Set<String> POI_KINDS = Set.of("light", "cctv", "bell", "police", "store", "guard_house",
            "shelter", "toilet", "school_zone");

    private final RoadGraphSource roads;
    private final PoiSource pois;
    private final NightJudge nightJudge;
    private final RouteScorer scorer;
    private final RouteResultAssembler assembler;
    private final RouteProperties props;
    private final Clock clock;
    private final PreferenceSource preferences;
    /** C파트 날씨. null이면 묻지 않는다 (rain auto = 비 아님) */
    private final WeatherDustService weatherDust;

    /** 선호·날씨를 읽지 않는 엔진 (테스트·점검 도구용). 비는 요청 rain(on·off)으로만 정한다 */
    public RouteService(RoadGraphSource roads, PoiSource pois, NightJudge nightJudge, RouteScorer scorer,
            RouteResultAssembler assembler, RouteProperties props, Clock clock) {
        this(roads, pois, nightJudge, scorer, assembler, props, clock, PreferenceSource.NONE, null);
    }

    /** 날씨를 묻지 않는 엔진 (테스트용) */
    public RouteService(RoadGraphSource roads, PoiSource pois, NightJudge nightJudge, RouteScorer scorer,
            RouteResultAssembler assembler, RouteProperties props, Clock clock, PreferenceSource preferences) {
        this(roads, pois, nightJudge, scorer, assembler, props, clock, preferences, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RouteService(RoadGraphSource roads, PoiSource pois, NightJudge nightJudge, RouteScorer scorer,
            RouteResultAssembler assembler, RouteProperties props, Clock clock, PreferenceSource preferences,
            WeatherDustService weatherDust) {
        this.weatherDust = weatherDust;
        this.preferences = preferences;
        this.roads = roads;
        this.pois = pois;
        this.nightJudge = nightJudge;
        this.scorer = scorer;
        this.assembler = assembler;
        this.props = props;
        this.clock = clock;
    }

    public RouteResult route(RouteRequest req) {
        long started = System.nanoTime();
        try {
            RouteResult result = compute(req);
            log.info("경로 계산 {}ms, 경로 {}개, 구간 {}개", elapsedMs(started), result.routes().size(),
                    req.stops() == null ? 1 : req.stops().size() + 1);
            return result;
        } catch (RouteException e) {
            log.info("경로 계산 실패 {}ms, {}", elapsedMs(started), e.error());
            throw e;
        } catch (DataAccessException e) {
            log.warn("경로 계산 중 DB 오류: {}", e.getMostSpecificCause().getMessage());
            throw new RouteException(RouteError.DB_UNAVAILABLE, e);
        }
    }

    private RouteResult compute(RouteRequest req) {
        double[] start = point(req.origin());
        double[] end = point(req.destination());
        List<Place> stops = req.stops() == null ? List.of() : req.stops();
        if (stops.size() > MAX_STOPS) {
            throw new IllegalArgumentException("stops는 최대 %d곳입니다".formatted(MAX_STOPS));
        }
        // 출발, 들를 곳(순서대로), 도착
        List<double[]> points = new ArrayList<>();
        points.add(start);
        for (Place s : stops) {
            points.add(point(s, "stops의 lat·lon이 필요합니다"));
        }
        points.add(end);
        NightOption option = NightOption.from(req.night());
        Boolean rainOption = rainOption(req.rain());
        RouteProperties.Profile base = props.profile(req.mode());
        validate(points);
        // 비(auto)는 C파트 날씨에 묻는다. 받는 데 1.5초까지 걸릴 수 있어 먼저 묻기 시작하고 그동안 선호·밤을 본다.
        // 컨트롤러도 같은 서비스를 묻는다(응답 표시용). 같은 동네 10분 기억이 있으면 바로 받고, 없을 때만 한 번 더 묻게 된다
        // (C파트 코드를 건드리지 않기로 한 결정, 2026-11-09)
        CompletableFuture<WeatherDust> weather = rainOption == null && weatherDust != null
                ? weatherDust.start(start[1], start[0]) : null;
        UserPreferences prefs = preferences.find(req.guestId());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KOREA);
        // 날씨를 모르면(기상청 실패·날씨 없는 엔진) 비 아님
        boolean rain = rainOption != null ? rainOption : weather != null && weatherDust.await(weather).rain();
        Conditions cond = conditions(start, now, option, base.nightLeadMin(), rain, rainOption == null ? "auto" : "manual");
        // 비면 지붕 기준을 더한 뒤 그 사람 선호(우회 한도 등)를 덮는다: 직접 정한 값이 비 기준보다 우선
        RouteProperties.Profile profile = (rain ? base.rainy(props.rain()) : base).personalized(prefs);
        // 비 올 때 넓힌 우회 한도. 그 사람이 직접 정한 우회 한도가 있으면 그 값이 우선이라 넓히지 않는다
        double wideDetour = rain && (prefs == null || prefs.maxDetour() == null)
                ? props.rain().widen(profile.maxDetour()) : profile.maxDetour();
        RouteProperties.Phase phase = profile.phase(cond.night());
        // 지하 길(역 대합실·지하상가)은 비 올 때 운행 시간 안에만 도로망에 넣는다 (맑은 날은 지하에서 GPS가 끊겨 걷는 안내가 깨짐, A파트 요청)
        boolean underground = rain && props.underground().openAt(now.toLocalTime());

        // 다시 찾을 때의 넓은 범위까지 한 번에 읽는다. 도로망·행사 범위에는 들를 곳도 넣는다
        List<RoadEvent> events = pois.events(BBox.around(points,
                Math.max(props.searchMarginM(), props.retryMarginM()) + EVENT_MARGIN_M), now.toLocalDateTime());
        // 출발·들를 곳·도착이 반경 안이면 그 행사에 가는 길이라 피하지 않고 알리기만 한다
        List<RoadEvent> avoided = events.stream().filter(e -> props.events().avoids(profile, e)
                && points.stream().allMatch(p -> Geo.distance(p, e.point()) > e.radiusM()))
                .toList();

        Attempt a;
        RoadGraph near = roads.load(BBox.around(points, props.searchMarginM()), underground);
        try {
            a = attempt(near, underground, points, stops, cond, profile, wideDetour, phase, now, avoided);
        } catch (RouteException e) {
            // 넓혀 다시 찾는 것은 길을 못 찾았을 때만 (이은 길이 빈 SAME_POINT 안전망은 넓혀도 같다)
            if (e.error() != RouteError.NO_ROUTE || props.retryMarginM() <= props.searchMarginM()) {
                throw e;
            }
            // 강·철도·큰 도로를 범위 밖 다리로 돌아가야 하는 길. 넓혀 한 번 더 찾는다 (우회 한도 등 규칙은 같다)
            a = attempt(roads.load(BBox.around(points, props.retryMarginM()), underground),
                    underground, points, stops, cond, profile, wideDetour, phase, now, avoided);
        }
        RouteResult result = withStops(assembler.assemble(cond, profile, phase, a.found(), start, end, a.snapM(),
                a.nearby(), a.coverage(), events, a.avoided()::contains), a, start, stops);
        if (profile.avoidUnpaved()) {
            result = withNotes(result, surfaceNotes(a.found(), profile));
        }
        return withNotes(result, a.snapNotes());
    }

    /**
     * 한 도로망에서 찾은 결과. snapNotes: 휠체어가 가장 가까운 길이 아닌 곳에 붙었을 때 주의 (없으면 빈 목록).
     * avoided: 실제로 둘레를 피하려 한 행사·돌발. legs: 들를 곳 사이 구간마다 찾은 것 (들를 곳이 없으면 found 하나)
     */
    private record Attempt(RouteFinder.Found found, List<RouteFinder.Found> legs, double snapM, Nearby nearby,
            List<RouteResult.Note> snapNotes, Coverage coverage, List<RoadEvent> avoided) {
    }

    /**
     * 출발·들를 곳·도착을 붙인 길 점이 하나라도 반경 안인 행사는 피하지 않는다. 그 점에 닿는 길이 모두 막혀 피하는 길을 못 찾고,
     * 틀린 "피해 가는 길은 너무 멉니다"가 붙는다 (요청 좌표는 반경 밖이어도 붙인 점은 안일 수 있음).
     * 구간마다 따로 거르지 않는다: 그러면 들를 곳이 반경 안인 행사에 다른 구간에서 "피해 가는 길은 너무 멉니다"가 붙는다
     */
    private static List<RoadEvent> reachable(RoadGraph graph, List<RoadEvent> avoided, List<RoadGraph.Snap> at) {
        return avoided.stream().filter(e -> at.stream()
                .allMatch(s -> Geo.distance(graph.point(s.node()), e.point()) > e.radiusM())).toList();
    }

    private static java.util.function.Predicate<RoadEdge> aside(RoadGraph graph, List<RoadEvent> events) {
        Set<Long> zone = RoadEvents.zone(graph, events);
        return zone.isEmpty() ? null : e -> zone.contains(e.id());
    }

    /** 길에 붙인 곳. moved: 모든 길 기준으로 가장 가까운 곳이 아닌 곳에 붙였는지 (휠체어) */
    private record Placed(RoadGraph.Snap snap, boolean moved) {
    }

    /**
     * 이 도로망에서 경로를 찾는다. 길에 붙이지 못하거나 이어지지 않으면 NO_ROUTE (들를 곳이 있으면 막힌 구간의 도착점 이름으로).
     * points: 출발, 들를 곳, 도착. 모든 점을 같은 도로망에 먼저 붙인 뒤 구간마다 찾는다: 붙일 때 길을 나눈 임시 노드가
     * 다른 구간과 겹치지 않게. underground: 지하 길을 넣은 도로망인지
     */
    private Attempt attempt(RoadGraph graph, boolean underground, List<double[]> points, List<Place> stops,
            Conditions cond, RouteProperties.Profile profile, double wideDetour, RouteProperties.Phase phase,
            ZonedDateTime now, List<RoadEvent> avoided) {
        // 지하를 넣었으면 출발·도착은 지상 길(출구 포함)에만 붙인다 (2026-11-09). 요청 좌표는 위도·경도뿐이라 지상·지하를 모르는데,
        // 앱 사용자는 대개 지상(GPS)에 있고, 지하 점에 붙이면 내려가는 출구(계단·엘리베이터)가 경로에서 빠진다
        // (휠체어가 계단 출구로 내려가게 될 수 있음). 출구 점은 연결 길로 지상과 이어져 있어 출구에서 출발·도착하면 그대로 출구에 붙는다.
        // 한계: 지하상가 안에 있는 사람도 가까운 지상 점에서 출발하는 것으로 계산된다. 들를 곳도 같다
        Set<Long> surface = underground ? graph.mainPart(e -> !RouteFinder.isUnderground(e)) : null;
        List<RoadGraph.Snap> snaps = new ArrayList<>();
        for (int k = 0; k < points.size(); k++) {
            try {
                snaps.add(snap(graph, points.get(k), profile, surface));
            } catch (RouteException e) {
                throw noRoute(stops, k);
            }
        }
        Nearby nearby = nearby(graph, phase, now);
        // 커버율은 한 번만 계산해 경로 고르기와 어두운 구간 안내(FR-31)에 같이 쓴다
        Coverage cov = scorer.coverage(graph, phase, cond.night(), nearby);
        ToDoubleFunction<RoadEdge> extra = scorer.extraCost(cov, phase);
        List<RoadEvent> av = reachable(graph, avoided, snaps);
        RouteException failed;
        try {
            return joined(legs(graph, snaps, extra, profile, wideDetour, aside(graph, av), cov, stops), snaps,
                    nearby, List.of(), cov, av, profile.avoid());
        } catch (RouteException e) {
            if (profile.exclude().isEmpty()) {
                throw e;
            }
            failed = e;
        }
        // 휠체어: 붙인 곳이 계단으로만 드나드는 구역일 수 있다. 지날 수 있는 길로 이어진 곳에 다시 붙여 한 번 더 찾는다.
        // 대부분의 요청은 위에서 찾으므로 연결 덩어리 계산은 못 찾았을 때만 한다.
        // 들를 곳이 있어도 모든 점을 함께 다시 붙이고 구간 전부를 다시 찾는다: 한 구간만 옮기면 앞 구간 끝 노드와 이어지지 않는다
        Set<Long> part = graph.mainPart(underground ? e -> profile.passable(e) && !RouteFinder.isUnderground(e)
                : profile::passable);
        List<Placed> placed = new ArrayList<>();
        for (int k = 0; k < points.size(); k++) {
            try {
                placed.add(place(graph, points.get(k), snaps.get(k), part, profile));
            } catch (RouteException e) {
                throw noRoute(stops, k);
            }
        }
        if (placed.stream().noneMatch(Placed::moved)) {
            throw failed;
        }
        List<RoadGraph.Snap> moved = placed.stream().map(Placed::snap).toList();
        List<RoadEvent> movedAv = reachable(graph, avoided, moved);
        List<RouteResult.Note> notes = new ArrayList<>();
        for (int k = 0; k < placed.size(); k++) {
            String which = k == 0 ? "출발점" : k == placed.size() - 1 ? "도착점" : stopName(stops, k - 1);
            placeNote(which, placed.get(k), profile).ifPresent(notes::add);
        }
        return joined(legs(graph, moved, extra, profile, wideDetour, aside(graph, movedAv), cov, stops), moved,
                nearby, notes, cov, movedAv, profile.avoid());
    }

    /** 구간 결과를 이어 하나의 시도로. 붙인 거리는 출발·도착만 더한다 (들를 곳 가게에서 길까지는 머무는 일에 가깝다) */
    private static Attempt joined(List<RouteFinder.Found> legs, List<RoadGraph.Snap> at, Nearby nearby,
            List<RouteResult.Note> notes, Coverage cov, List<RoadEvent> avoided,
            java.util.function.Predicate<RoadEdge> avoid) {
        return new Attempt(join(legs, avoid), legs, at.get(0).distanceM() + at.get(at.size() - 1).distanceM(), nearby, notes,
                cov, avoided);
    }

    /** 붙인 점 사이 구간마다 최단·안전 경로를 찾는다. 우회 한도는 구간마다 따로 지킨다 */
    private List<RouteFinder.Found> legs(RoadGraph graph, List<RoadGraph.Snap> at, ToDoubleFunction<RoadEdge> extra,
            RouteProperties.Profile profile, double wideDetour, java.util.function.Predicate<RoadEdge> aside,
            Coverage cov, List<Place> stops) {
        List<RouteFinder.Found> out = new ArrayList<>();
        for (int k = 1; k < at.size(); k++) {
            try {
                out.add(find(graph, at.get(k - 1).node(), at.get(k).node(), extra, profile, wideDetour,
                        props.rain().walkMore(), aside, cov));
            } catch (RouteException e) {
                throw noRoute(stops, k);
            }
        }
        return out;
    }

    /**
     * 구간 결과를 차례로 잇는다. 하나라도 우회 한도에 걸렸으면 걸린 것으로,
     * 조건부 제외 없이 가는 길(avoidFree)은 구간마다 다 있을 때만 있다.
     * avoid: 조건부 제외할 길 (없으면 null)
     */
    static RouteFinder.Found join(List<RouteFinder.Found> legs, java.util.function.Predicate<RoadEdge> avoid) {
        if (legs.size() == 1) {
            return legs.get(0);
        }
        RoadPath shortest = RoadPath.join(legs.stream().map(RouteFinder.Found::shortest).toList());
        if (shortest.edges().isEmpty()) {
            // 인접한 점은 50m 넘게 떨어져 있지만 모두 같은 길 점에 붙은 경우 (안전망)
            throw new RouteException(RouteError.SAME_POINT);
        }
        RoadPath safe = RoadPath.join(legs.stream().map(RouteFinder.Found::safe).toList());
        // 안전 경로에 피할 길이 없는 구간은 그 안전 경로를 잇는다. 그 구간의 avoidFree는 최단이라, 밝은 길로 돈 구간에서
        // 그대로 이으면 "피하면 −70m 더 걸립니다"처럼 피하는 길이 더 짧게 나온다
        java.util.function.Function<RouteFinder.Found, RoadPath> free1 = avoid == null ? RouteFinder.Found::avoidFree
                : l -> l.safe().edges().stream().anyMatch(avoid) ? l.avoidFree() : l.safe();
        RoadPath free = legs.stream().allMatch(l -> free1.apply(l) != null)
                ? RoadPath.join(legs.stream().map(free1).toList()) : null;
        return new RouteFinder.Found(shortest, safe, legs.stream().anyMatch(RouteFinder.Found::detourCapped), free);
    }

    /** 들를 곳이 없으면 지금 문장 그대로, 있으면 막힌 구간의 도착점(k번째 점) 이름으로. k = 0(출발)이면 지금 문장 */
    private static RouteException noRoute(List<Place> stops, int k) {
        if (stops.isEmpty() || k == 0) {
            return new RouteException(RouteError.NO_ROUTE);
        }
        String to = k > stops.size() ? "도착지" : stopName(stops, k - 1);
        return new RouteException(RouteError.NO_ROUTE, "%s까지 가는 길을 찾지 못했어요".formatted(to));
    }

    /** i번째(0부터) 들를 곳 이름. 비었으면 "들를 곳 N" */
    private static String stopName(List<Place> stops, int i) {
        String n = stops.get(i).name();
        return n == null || n.isBlank() ? "들를 곳 " + (i + 1) : n;
    }

    /**
     * 경로마다 들를 곳 자리를 붙인다. atM = 요청 출발에서 경로 첫 점까지 + 그 들를 곳까지 구간 길이 합
     * (계단·꺾는 곳 fromM·atM과 같은 기준). 최단·안전은 구간 길이가 달라 따로 잰다
     */
    private static RouteResult withStops(RouteResult r, Attempt a, double[] start, List<Place> stops) {
        if (stops.isEmpty()) {
            return r;
        }
        List<RouteResult.Route> routes = r.routes().stream().map(rt -> {
            java.util.function.Function<RouteFinder.Found, RoadPath> path = "shortest".equals(rt.kind())
                    ? RouteFinder.Found::shortest : RouteFinder.Found::safe;
            double at = Geo.distance(start, path.apply(a.found()).line().get(0));
            List<RouteResult.StopMark> marks = new ArrayList<>();
            for (int i = 0; i < stops.size(); i++) {
                at += path.apply(a.legs().get(i)).lengthM();
                Place p = stops.get(i);
                marks.add(new RouteResult.StopMark(p.name(), p.lat(), p.lon(), (int) Math.round(at)));
            }
            return new RouteResult.Route(rt.kind(), rt.distanceM(), rt.durationMin(), rt.geometry(), rt.counts(),
                    rt.covered(), rt.sidewalks(), rt.dark(), rt.turns(), rt.crossings(), rt.stairs(), rt.events(),
                    rt.unpavedM(), rt.surfaceUnknownM(), marks);
        }).toList();
        return new RouteResult(r.conditions(), routes, r.explanation(), r.facilities(), r.dataQuality());
    }

    /**
     * 최단·안전 경로. 비 올 때 넓힌 한도(wideDetour)가 그 사람의 한도보다 크면 두 번 찾아, 넓힌 쪽 안전 경로의 지붕 이득이
     * 더 걷는 만큼의 젖는 부담을 갚을 때만 그쪽을 쓴다 (2026-11-09, walkMore = rain.walk-more). 비 때문에 더 걷는 것은 지붕 아래로 가기 위해서만이다. 넓힌 한도로 지붕 이득 없이
     * 다른 기준만 더 챙기면 비를 맞으며 더 걷게 된다 (하네스: 노인 낮 평균 +104m 중 지붕 15m)
     */
    private static RouteFinder.Found find(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            RouteProperties.Profile profile, double wideDetour, double walkMore, java.util.function.Predicate<RoadEdge> aside,
            Coverage cov) {
        RouteFinder.Found narrow = RouteFinder.find(graph, from, to, extra, profile.maxDetour(), profile::passable,
                profile.avoid(), aside);
        // 범위에 지붕 길이 하나도 없으면 지붕 이득도 없어 결과가 같다. 광주는 걷는 지붕 길이 2.8km뿐이라 대부분의 비 요청이 여기서 끝난다
        if (wideDetour <= profile.maxDetour() || graph.graph().edgeSet().stream()
                .noneMatch(e -> e.penalty().get(Penalty.COVERED) >= 1 || RouteFinder.isUnderground(e))) {
            return narrow;
        }
        RouteFinder.Found wide = RouteFinder.find(graph, from, to, extra, wideDetour, profile::passable, profile.avoid(),
                aside);
        // 비 오는 날 지붕 없는 1m = 지붕 아래 (1 + r)m (F1과 같은 교환비). 더 걷는 거리만큼의 젖는 부담을 지붕 이득이 갚을 때만
        // 넓힌 한도를 쓴다: r × 지붕 이득 ≥ (1 + r) × 더 걷는 길이. 예전 1m 조건은 지붕 10m 때문에 비를 맞으며 380m를 더 걷는 길도 골랐다
        double gain = roofM(wide.safe(), cov) - roofM(narrow.safe(), cov);
        double more = wide.safe().lengthM() - narrow.safe().lengthM();
        return gain > 0 && walkMore * gain >= (1 + walkMore) * more ? wide : narrow;
    }

    /** 경로가 지붕 아래(지하 포함)를 걷는 길이(m) */
    private static double roofM(RoadPath p, Coverage cov) {
        return p.edges().stream().mapToDouble(e -> e.lengthM() * cov.covered(e, Criterion.ROOF)).sum();
    }

    /**
     * 안내하는 경로의 바닥을 주의·알림으로 알린다 (휠체어, 2026-11-04 결정).
     * <ul>
     *   <li>비포장을 지나면: 피하면 얼마나 더 걸리는지, 비포장 없이는 이어지지 않으면 피하는 길이 없다고</li>
     *   <li>재질을 모르는 산길을 지나면: "확인 안 됨"</li>
     * </ul>
     */
    static List<RouteResult.Note> surfaceNotes(RouteFinder.Found found, RouteProperties.Profile profile) {
        double unpaved = found.safe().lengthM(RouteFinder::isUnpaved);
        double unknown = found.safe().lengthM(RouteFinder::isSurfaceUnknown);
        List<RouteResult.Note> notes = new ArrayList<>();
        if (unpaved > 0) {
            String avoid;
            if (found.avoidFree() == null) {
                avoid = "비포장을 피하는 길은 없습니다";
            } else {
                long extraM = Math.round(found.avoidFree().lengthM() - found.safe().lengthM());
                int extraMin = profile.minutes(found.avoidFree().lengthM()) - profile.minutes(found.safe().lengthM());
                avoid = extraMin > 0 ? "비포장을 피하면 %dm(%d분) 더 걸립니다".formatted(extraM, extraMin)
                        : "비포장을 피하면 %dm 더 걸립니다".formatted(extraM);
            }
            notes.add(RouteResult.Note.warn("unpaved",
                    "비포장길 약 %dm를 지납니다. %s".formatted(Math.round(unpaved), avoid)));
        }
        if (unknown > 0) {
            notes.add(RouteResult.Note.unknown("surface",
                    "바닥 재질을 모르는 산길 약 %dm를 지납니다".formatted(Math.round(unknown))));
        }
        return notes;
    }

    /** 설명에 주의·알림을 더한다 */
    private static RouteResult withNotes(RouteResult r, List<RouteResult.Note> notes) {
        if (notes.isEmpty()) {
            return r;
        }
        RouteResult.Explanation e = r.explanation();
        List<RouteResult.Note> all = new ArrayList<>(e.notes());
        all.addAll(notes);
        // 주의가 생기면 "가장 짧은 길이 가장 안전한 길입니다"는 맞지 않다
        return new RouteResult(r.conditions(), r.routes(), RouteResult.Explanation.of(
                ExplanationBuilder.withWarnings(e.summary(), all), all, e.extraM(), e.extraMin(), e.diff()),
                r.facilities(), r.dataQuality());
    }

    /** 도로망 범위를 가장 큰 반경만큼 넓혀 그 안 시설을 한 번 읽는다 */
    private Nearby nearby(RoadGraph graph, RouteProperties.Phase phase, ZonedDateTime now) {
        RouteProperties.CountRadius c = props.countRadiusM();
        double margin = Math.max(Math.max(Math.max(phase.helpM(), phase.restM()), Math.max(phase.schoolM(),
                props.coverage().cctvM())), Math.max(Math.max(Math.max(c.light(), c.cctv()), Math.max(c.bell(), c.help())),
                Math.max(c.rest(), c.school())));
        BBox box = BBox.around(new ArrayList<>(graph.nodes().values()), margin);
        return new Nearby(pois.within(box, POI_KINDS), pois.holidays(), now.toLocalDateTime(), pois.dataDate());
    }

    /** 일몰 후 ~ 일출 전이 밤. auto일 때 leadMin만큼 일몰 전부터 밤으로 본다 (어린이 60분) */
    Conditions conditions(double[] start, ZonedDateTime now, NightOption option, int leadMin) {
        return conditions(start, now, option, leadMin, false, "auto");
    }

    /** 밤 판단에 비 기준(지붕 기준을 더하는지)과 그 출처(roofSource: auto·manual)를 붙인다 */
    Conditions conditions(double[] start, ZonedDateTime now, NightOption option, int leadMin, boolean roof,
            String roofSource) {
        NightResult r = nightJudge.judge(start[1], start[0], now, option);
        boolean night = r.night();
        if (option == NightOption.AUTO && !night && leadMin > 0 && r.sunset() != null) {
            LocalTime t = now.toLocalTime();
            night = !t.isBefore(r.sunset().minusMinutes(leadMin));
        }
        return new Conditions(night, r.nightSource(), r.sunset(), roof, roofSource);
    }

    /** 요청 rain: 비어 있거나 auto면 null(날씨를 따름), on이면 true, off면 false */
    static Boolean rainOption(String v) {
        if (v == null || v.isBlank() || "auto".equalsIgnoreCase(v.trim())) {
            return null;
        }
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("rain 은 auto, on, off 중 하나여야 합니다: " + v);
        };
    }

    /** points: 출발, 들를 곳(순서대로), 도착 */
    private void validate(List<double[]> points) {
        // 같은 들를 곳(좌표가 같음)이 두 번이면 요청 실수로 본다 (400 BAD_PARAM, 진행표 11-09).
        // 서로 다른 가게가 붙어 있는 것(충장로 등)은 가까워도 안내한다 (사용자 결정 11-10)
        for (int i = 1; i < points.size() - 1; i++) {
            for (int j = i + 1; j < points.size() - 1; j++) {
                if (Geo.distance(points.get(i), points.get(j)) < SAME_STOP_M) {
                    throw new IllegalArgumentException("같은 들를 곳이 두 번 있습니다");
                }
            }
        }
        // 왕복(출발 = 도착)은 들를 곳이 있어도 받지 않는다 (사용자 결정 11-10).
        // 들를 곳이 출발·도착이나 앞뒤 들를 곳과 가까운 것은 안내한다 (사용자 결정 11-10). 길이 0인 구간은 잇기에서 빠진다
        boolean same = Geo.distance(points.get(0), points.get(points.size() - 1)) < props.samePointM();
        double straight = 0;
        for (int k = 1; k < points.size(); k++) {
            straight += Geo.distance(points.get(k - 1), points.get(k));
        }
        if (same) {
            throw new RouteException(RouteError.SAME_POINT);
        }
        // 구간 직선거리의 합으로 잰다. 구간마다 재면 들를 곳 3곳을 거쳐 20km도 지나간다
        if (straight > props.tooFarM()) {
            throw new RouteException(RouteError.TOO_FAR);
        }
        if (points.stream().anyMatch(p -> !roads.covers(p[0], p[1]))) {
            throw new RouteException(RouteError.OUT_OF_AREA);
        }
    }

    /** 가장 가까운 길 노드. 너무 멀면 길 위가 아니라고 본다. surface가 있으면(지하를 넣은 도로망) 그 안 지상 길에만 붙인다 */
    private RoadGraph.Snap snap(RoadGraph graph, double[] p, RouteProperties.Profile profile, Set<Long> surface) {
        RoadGraph.Snap s = surface == null ? graph.nearest(p[0], p[1], profile::passable)
                : graph.nearest(p[0], p[1], surface, e -> profile.passable(e) && !RouteFinder.isUnderground(e));
        if (s == null || s.distanceM() > props.snapMaxM()) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        return s;
    }

    /**
     * 지날 수 있는 길로 이어진 덩어리(part) 안에 다시 붙인다 (휠체어).
     * 가장 가까운 길이 계단으로만 드나드는 구역이면 옆 길에 붙는데, 모든 길 기준보다
     * passable-snap-extra-m(50m) 넘게 멀어지면 붙이지 않는다 (2026-11-04)
     */
    private Placed place(RoadGraph graph, double[] p, RoadGraph.Snap any, Set<Long> part,
            RouteProperties.Profile profile) {
        if (part.contains(any.node())) {
            return new Placed(any, false);
        }
        RoadGraph.Snap s = graph.nearest(p[0], p[1], part, profile::passable);
        if (s == null || s.distanceM() - any.distanceM() > props.passableSnapExtraM()) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        return new Placed(s, true);
    }

    /** 옆 길에 붙였으면 남은 거리를 알린다. 계단으로만 드나드는 곳이라 휠체어 길이 확인되지 않은 구간이다 */
    private static java.util.Optional<RouteResult.Note> placeNote(String which, Placed p,
            RouteProperties.Profile profile) {
        String way = profile.onlyStairsExcluded() ? "계단 없이" : "휠체어로";
        return p.moved() ? java.util.Optional.of(RouteResult.Note.warn("snap",
                "%s 근처 약 %dm는 %s 이어진 길이 확인되지 않았습니다".formatted(which,
                        Math.round(p.snap().distanceM()), way))) : java.util.Optional.empty();
    }

    /** {경도, 위도} */
    private static double[] point(Place p) {
        return point(p, "origin과 destination의 lat·lon이 필요합니다");
    }

    private static double[] point(Place p, String missing) {
        if (p == null || p.lat() == null || p.lon() == null) {
            throw new IllegalArgumentException(missing);
        }
        return new double[] {p.lon(), p.lat()};
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
