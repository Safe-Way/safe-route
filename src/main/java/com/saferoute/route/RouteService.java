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
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.explain.ExplanationBuilder;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.graph.UndergroundLinks;
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
 *   <li>지금 진행 중인 행사·돌발상황 읽기: 모드에 따라 둘레를 조건부로 피하거나({@link RouteProperties.Events}) 지날 때 알린다.
 *       지금 유효한 신고 지점(진행표 w11)도 kind "신고"인 행사처럼 넣는다: 뷰의 avoid_modes면 그 길을 조건부로 피하고,
 *       cost_modes면 그 길에 그 모드 걸음 1분만큼 비용만 더한다</li>
 *   <li>모드·낮밤 기준으로 길마다 더할 비용: {@link RouteScorer} (시설 커버율을 요청 때 계산)</li>
 *   <li>최단·안전 경로: {@link RouteFinder}. 못 찾으면 범위를 넓혀 한 번 더 찾는다.
 *       등산로는 요청 allowTrail이 아니면 빼고 찾고, 넓혀도 없을 때만 등산로를 넣어 다시 찾고 알린다 (2026-11-12, 휠체어는 늘 뺌).
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
    private final Snapper snapper;

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
        this.snapper = new Snapper(props);
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
        // 도착지 이름이 지하 시설(지하상가·역 대합실)이면 그 시설에 들어가는 곳까지 간다 (2026-11-16). 출구를 지상 길에 이으려고
        // 맑은 날에도 지하 길을 읽되 걷지는 않는다 (inside false). 도착지만 본다: 앱이 들를 곳 있는 길을 구간마다 따로 묻는다
        Set<Long> places = undergroundPlace(req.destination().name(), roads.undergroundPlaces());
        Entrance entrance = places.isEmpty() ? null : new Entrance(places, underground);
        boolean loaded = underground || entrance != null;

        // 다시 찾을 때의 넓은 범위까지 한 번에 읽는다. 도로망·행사 범위에는 들를 곳도 넣는다
        BBox eventBox = BBox.around(points, Math.max(props.searchMarginM(), props.retryMarginM()) + EVENT_MARGIN_M);
        List<RoadEvent> events = new ArrayList<>(pois.events(eventBox, now.toLocalDateTime()));
        // 출발·들를 곳·도착이 반경 안이면 그 행사에 가는 길이라 피하지 않고 알리기만 한다
        java.util.function.Predicate<RoadEvent> away = e -> points.stream()
                .allMatch(p -> Geo.distance(p, e.point()) > e.radiusM());
        List<RoadEvent> avoided = new ArrayList<>(events.stream()
                .filter(e -> props.events().avoids(profile, e) && away.test(e)).toList());
        // 신고 (진행표 w11, 2026-11-10): 모드는 뷰가 종류로 정한 목록과 비교한다. 사람 선호는 모드 이름을 바꾸지 않는다
        String mode = RouteProperties.modeName(req.mode());
        List<RoadEvent> reports = new ArrayList<>();
        List<RoadEvent> costed = new ArrayList<>();
        for (RoadReport r : pois.reports(eventBox)) {
            RoadEvent e = r.event();
            events.add(e);
            reports.add(e);
            if (away.test(e)) {
                if (r.avoidModes().contains(mode)) {
                    avoided.add(e);
                } else if (r.costModes().contains(mode)) {
                    costed.add(e);
                }
            }
        }

        // 등산로 (2026-11-12): 등산로 없는 길이 넓힌 범위까지 어디에도 없을 때만 등산로를 지나고 알린다. 우회 한도 없음.
        // 출발·도착은 등산로 없는 길에 다시 붙여 보고(휠체어처럼 50m 더 멀어지는 데까지), 아니면 등산로에 붙인다 (attempt). 휠체어는 늘 뺀다(exclude)
        boolean avoidTrail = !Boolean.TRUE.equals(req.allowTrail()) && !profile.exclude().contains(Penalty.TRAIL);
        // 길안내 중 다시 찾기면 휠체어 출발점도 50m까지만 다시 붙인다 (2026-11-13)
        boolean reroute = Boolean.TRUE.equals(req.reroute());
        RoadGraph[] used = new RoadGraph[1]; // 경로를 찾은 도로망. 건너는 곳(SideChanges)을 이 도로망에서 찾는다
        // 휠체어 인도 없는 골목 (2026-11-13): 늘 빼되, 빼면 넓힌 범위·다시 붙이기까지 해도 길이 없을 때만 넣고 알린다
        boolean alleyFallback = profile.exclude().contains(Penalty.NO_SIDEWALK_ALLEY);
        java.util.function.BiFunction<Boolean, Boolean, Attempt> search = (noTrail, alleysOk) -> {
            used[0] = roads.load(BBox.around(points, props.searchMarginM()), loaded);
            try {
                return attempt(used[0], loaded, points, stops, cond, profile, wideDetour, phase, now, avoided, costed,
                        reports, noTrail, alleysOk, reroute, entrance);
            } catch (RouteException e) {
                // 넓혀 다시 찾는 것은 길을 못 찾았을 때만
                if (e.error() != RouteError.NO_ROUTE || props.retryMarginM() <= props.searchMarginM()) {
                    throw e;
                }
                // 강·철도·큰 도로를 범위 밖 다리로 돌아가야 하는 길. 넓혀 한 번 더 찾는다 (우회 한도 등 규칙은 같다)
                used[0] = roads.load(BBox.around(points, props.retryMarginM()), loaded);
                return attempt(used[0], loaded, points, stops, cond, profile, wideDetour, phase, now, avoided, costed,
                        reports, noTrail, alleysOk, reroute, entrance);
            }
        };
        Attempt a;
        boolean usedAlleys = false;
        try {
            a = search.apply(avoidTrail, false);
        } catch (RouteException e) {
            if (!avoidTrail && !alleyFallback || e.error() != RouteError.NO_ROUTE) {
                throw e;
            }
            // 다시 찾을 때 넣을 길(등산로·인도 없는 골목)이 읽은 도로망(넓힌 범위)에 없으면 결과가 같아 다시 찾지 않는다
            if (used[0].graph().edgeSet().stream().noneMatch(x -> avoidTrail && RouteFinder.isTrail(x)
                    || alleyFallback && RouteFinder.isNoSidewalkAlley(x))) {
                throw e;
            }
            // 붙일 때 길을 나눠 도로망이 바뀌므로 새로 읽는다 (메모리에서 잘라 오는 것이라 DB를 다시 읽지 않음).
            // 등산로를 피하던 모드는 등산로를, 휠체어는 인도 없는 골목을 넣는다 (휠체어는 등산로를 늘 빼서 둘이 겹치지 않음)
            // shortcut: 다시 찾을 때는 모든 등산로를 비용 없이 쓴다, 필요한 만큼만 쓰게 하려면 등산로에 비용을 더하기
            a = search.apply(false, alleyFallback);
            usedAlleys = alleyFallback;
        }
        java.util.Map<RoadEvent, Long> reportEdges = a.reportEdges();
        // 지하 시설에 들어가는 곳으로 바꿨으면 경로 선은 그 출구(지하를 걸으면 시설 점)에서 끝난다
        double[] to = a.entered() == null ? end : used[0].point(a.entered());
        RouteResult result = withStops(assembler.assemble(cond, profile, phase, a.found(), start, to, a.snapM(),
                a.nearby(), a.coverage(), events, a.avoided()::contains, (e, path) -> passes(e, path, reportEdges),
                used[0], roads.crosswalkPoints(eventBox)),
                a, start, stops);
        if (profile.avoidUnpaved()) {
            result = withNotes(result, surfaceNotes(a.found(), profile));
        }
        // 좁은 인도를 피하지 못하면 알린다 (휠체어, 2026-11-13)
        double narrowM = profile.avoidNarrowSidewalk() ? a.found().safe().lengthM(RouteFinder::isNarrowSidewalk) : 0;
        if (narrowM > 0) {
            result = withNotes(result, List.of(RouteResult.Note.warn("narrow", String.format(java.util.Locale.ROOT,
                    "폭 %.1fm 안 되는 좁은 인도 약 %dm를 지납니다", props.narrowSidewalkM(), Math.round(narrowM)))));
        }
        double noSidewalkM = usedAlleys ? a.found().safe().lengthM(RouteFinder::isNoSidewalkAlley) : 0;
        if (noSidewalkM > 0) {
            result = withNotes(result, List.of(RouteResult.Note.warn("no_sidewalk_alley",
                    "인도 없는 골목 약 %dm를 지납니다".formatted(Math.round(noSidewalkM)))));
        }
        double trailM = avoidTrail ? a.found().safe().lengthM(RouteFinder::isTrail) : 0;
        if (trailM > 0) {
            result = withNotes(result, List.of(RouteResult.Note.warn("trail",
                    "등산로 약 %dm를 지납니다. 등산로를 피하는 길은 없습니다".formatted(Math.round(trailM)))));
        }
        if (a.entered() != null) {
            result = withNotes(result, entranceNote(req.destination().name(), used[0], a.found().safe(), a.entered(),
                    profile).stream().toList());
        }
        return withNotes(result, a.snapNotes());
    }

    /**
     * 한 도로망에서 찾은 결과. snapNotes: 휠체어가 가장 가까운 길이 아닌 곳에 붙었을 때 주의 (없으면 빈 목록).
     * avoided: 실제로 둘레를 피하려 한 행사·돌발. legs: 들를 곳 사이 구간마다 찾은 것 (들를 곳이 없으면 found 하나).
     * reportEdges: 신고마다 이 도로망에서 신고된 길 번호 (20m 안에 길이 없는 신고는 없음).
     * entered: 도착을 지하 시설에 들어가는 곳으로 바꿨으면 그 점(출구 또는 시설 점), 아니면 null
     */
    private record Attempt(RouteFinder.Found found, List<RouteFinder.Found> legs, double snapM, Nearby nearby,
            List<RouteResult.Note> snapNotes, Coverage coverage, List<RoadEvent> avoided,
            java.util.Map<RoadEvent, Long> reportEdges, Long entered) {
    }

    /** 도착지인 지하 시설 (2026-11-16). places: 그 시설 점, inside: 지하 길을 걷는지 (비, 운행 시간 안) */
    private record Entrance(Set<Long> places, boolean inside) {
    }

    /**
     * 도착지 이름에 맞는 지하 시설 점 (2026-11-16). 띄어쓰기를 빼고 시설 이름과 같으면("금남지하상가" 통로 점 6개),
     * 또는 이름이 "X역"이나 카카오 "X역 광주1호선" 그대로면 "X역 대합실". "X역 4번 출구"·"X역 공영주차장"처럼 뒤에 다른 말이
     * 붙으면 사용자가 고른 그 좌표로 가야 하므로 맞추지 않는다 (2026-11-16). 이름이 없거나 맞는 것이 없으면 빈 집합
     */
    static Set<Long> undergroundPlace(String name, java.util.Map<Long, String> places) {
        if (name == null) {
            return Set.of();
        }
        String n = name.replaceAll("\\s", "");
        String station = n.replaceFirst("광주\\d+호선$", "") + "대합실";
        Set<Long> out = new java.util.HashSet<>();
        places.forEach((id, place) -> {
            String p = place.replaceAll("\\s", "");
            if (p.equals(n) || p.endsWith("역대합실") && p.equals(station)) {
                out.add(id);
            }
        });
        return out;
    }

    /**
     * 지하 시설에 들어가는 곳 (2026-11-16). 지하를 걸으면 시설 점 어디든 닿으면 도착이고, 아니면 이 모드가 지날 수 있는 지하 길
     * (휠체어는 엘리베이터)로 시설에 이어진 출구 점(지상)이 도착이다. 앞 점(from)에서 길 비용 + 모드 기준 비용(λ = 1)이 가장 적은
     * 곳 하나를 다익스트라 한 번으로 고른다. 시설·출구에서 끝나므로 한 출구로 내려가 지하로 걷다 다른 출구로 올라오지 않는다.
     * 갈 곳이 없으면 null (지금처럼 도착 좌표로)
     */
    private static Long entrance(RoadGraph graph, long from, Entrance x, RouteProperties.Profile profile,
            java.util.function.Predicate<RoadEdge> passable, ToDoubleFunction<RoadEdge> cost, ToDoubleFunction<RoadEdge> score) {
        if (x == null) {
            return null;
        }
        Set<Long> targets = x.inside() ? x.places() : UndergroundLinks.exits(graph, x.places(), profile::passable);
        return targets.isEmpty() ? null : RouteFinder.closest(graph, from, targets, passable,
                score == null ? cost : e -> cost.applyAsDouble(e) + score.applyAsDouble(e));
    }

    /** 도착(마지막 점)을 길 위 점 node로 바꾼 목록. node가 null이면 그대로. 출구·시설 점이라 붙인 거리는 0 */
    private static List<RoadGraph.Snap> arrive(List<RoadGraph.Snap> snaps, Long node) {
        if (node == null) {
            return snaps;
        }
        List<RoadGraph.Snap> out = new ArrayList<>(snaps);
        out.set(out.size() - 1, new RoadGraph.Snap(node, 0));
        return out;
    }

    /**
     * 지하 시설에 들어가는 출구 알림 (2026-11-16): "금남지하상가는 문화전당역 4번 출구(엘리베이터)로 들어가요".
     * 지하를 걸으면 안전 경로가 내려간 출구 길(마지막 지하 계단·엘리베이터), 아니면 도착한 출구에서 이 모드가 내려갈 길(엘리베이터 먼저)
     */
    private static java.util.Optional<RouteResult.Note> entranceNote(String name, RoadGraph graph, RoadPath path,
            long entered, RouteProperties.Profile profile) {
        RoadEdge down = null;
        for (RoadEdge e : path.edges()) {
            if (RouteFinder.isUnderground(e) && ("steps".equals(e.highway()) || "elevator".equals(e.highway()))) {
                down = e;
            }
        }
        if (down == null) {
            down = graph.graph().edgesOf(entered).stream().filter(e -> RouteFinder.isUnderground(e) && profile.passable(e))
                    .min(java.util.Comparator.comparing(e -> !"elevator".equals(e.highway()))).orElse(null);
        }
        if (down == null || down.name() == null) {
            return java.util.Optional.empty();
        }
        String n = name.trim();
        char c = n.charAt(n.length() - 1);
        // 끝 글자에 받침이 있으면 "은"
        String topic = c >= '가' && c <= '힣' && (c - '가') % 28 != 0 ? "은" : "는";
        return java.util.Optional.of(RouteResult.Note.info("entrance",
                "%s%s %s로 들어가요".formatted(n, topic, ExplanationBuilder.undergroundName(down))));
    }

    /**
     * 신고마다 신고된 길 하나 ({@link RoadEvents#zoneNearest}). 길을 피할 때·비용을 더할 때·경로가 신고를 지나는지 볼 때 같은 길을 쓴다
     */
    private static java.util.Map<RoadEvent, Long> reportEdges(RoadGraph graph, List<RoadEvent> reports) {
        java.util.Map<RoadEvent, Long> out = new java.util.HashMap<>();
        for (RoadEvent r : reports) {
            Long id = RoadReport.KIND.equals(r.kind()) ? RoadEvents.zoneNearest(graph, r) : null;
            if (id != null) {
                out.put(r, id);
            }
        }
        return out;
    }

    /**
     * 경로가 이 행사·신고를 지나는지 (응답 events에 넣을지). 행사는 반경 안을 지나면, 신고는 신고된 길을 지날 때만.
     * 신고는 그 길 하나만 피하므로, 길 건너 보도로 피한 경로가 20m 안이라고 "앞에 공사 신고가 있는 곳을 지나요"가 붙지 않게
     */
    private static boolean passes(RoadEvent e, RoadPath path, java.util.Map<RoadEvent, Long> reportEdges) {
        if (!RoadReport.KIND.equals(e.kind())) {
            return true;
        }
        Long id = reportEdges.get(e);
        return id != null && path.edges().stream().anyMatch(x -> x.id() == id);
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

    /**
     * 차례로 조건부 제외할 길 묶음: 행사·돌발 둘레 하나, 피할 신고마다 그 길 하나 (2026-11-10).
     * 신고를 행사 둘레와 한 묶음으로 두면 하나라도 못 피할 때 전부 안 피한다
     */
    private static List<java.util.function.Predicate<RoadEdge>> asides(RoadGraph graph, List<RoadEvent> events,
            java.util.Map<RoadEvent, Long> reportEdges, RouteProperties.Profile profile) {
        List<java.util.function.Predicate<RoadEdge>> out = new ArrayList<>();
        // 좁은 인도 (휠체어, 2026-11-13): 비포장과 따로 피한다. 비포장을 못 피해도 좁은 인도만이라도 한도 안이면 피한다
        if (profile.avoidNarrowSidewalk()) {
            out.add(RouteFinder::isNarrowSidewalk);
        }
        Set<Long> zone = RoadEvents.zone(graph, events.stream().filter(e -> !RoadReport.KIND.equals(e.kind())).toList());
        if (!zone.isEmpty()) {
            out.add(e -> zone.contains(e.id()));
        }
        for (RoadEvent r : events) {
            Long id = reportEdges.get(r);
            if (id != null) {
                out.add(e -> e.id() == id);
            }
        }
        return out;
    }

    /**
     * 비용만 더할 신고(costed)의 길에 그 모드 걸음 1분만큼(m) 더한다 (2026-11-10 사용자 확정). λ가 곱해져 우회 한도 안에서만 작용한다.
     * "1분 더 걸어서 신고된 곳을 지나지 않을 수 있으면 그렇게"라는 작은 선호. 기준이 없는 낮(extra null)에도 이 비용만으로 만든다
     */
    private static ToDoubleFunction<RoadEdge> withReports(ToDoubleFunction<RoadEdge> extra, List<RoadEvent> costed,
            java.util.Map<RoadEvent, Long> reportEdges, RouteProperties.Profile profile) {
        Set<Long> ids = new java.util.HashSet<>();
        for (RoadEvent r : costed) {
            Long id = reportEdges.get(r);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return extra;
        }
        double minute = profile.walkSpeedMPerMin();
        return e -> (extra == null ? 0 : extra.applyAsDouble(e)) + (ids.contains(e.id()) ? minute : 0);
    }

    /**
     * 이 도로망에서 경로를 찾는다. 길에 붙이지 못하거나 이어지지 않으면 NO_ROUTE (들를 곳이 있으면 막힌 구간의 도착점 이름으로).
     * points: 출발, 들를 곳, 도착. 모든 점을 같은 도로망에 먼저 붙인 뒤 구간마다 찾는다: 붙일 때 길을 나눈 임시 노드가
     * 다른 구간과 겹치지 않게. underground: 지하 길을 넣은 도로망인지. noTrail: 등산로를 빼고 찾는지. alleysOk: 휠체어도 인도 없는 골목을 지나는지 (빼고는 길이 없을 때).
     * 먼저 모드 기준(profile.passable)대로 붙여 찾고, 못 찾으면 등산로를 뺀 길로 이어진 곳에 다시 붙인다 (아래 휠체어와 같은 방법).
     * reroute: 길안내 중 다시 찾기. 휠체어 출발·도착점도 넓힌 한도(100m) 대신 50m로 다시 붙인다 (2026-11-13).
     * entrance: 도착지가 지하 시설이면 그 시설, 아니면 null. 지하를 걷지 않으면(inside false) 읽은 지하 길은 지나지 않는다
     */
    private Attempt attempt(RoadGraph graph, boolean underground, List<double[]> points, List<Place> stops,
            Conditions cond, RouteProperties.Profile profile, double wideDetour, RouteProperties.Phase phase,
            ZonedDateTime now, List<RoadEvent> avoided, List<RoadEvent> costed, List<RoadEvent> reports,
            boolean noTrail, boolean alleysOk, boolean reroute, Entrance entrance) {
        // 지하를 넣었으면 출발·도착은 지상 길(출구 포함)에만 붙인다 (2026-11-09). 요청 좌표는 위도·경도뿐이라 지상·지하를 모르는데,
        // 앱 사용자는 대개 지상(GPS)에 있고, 지하 점에 붙이면 내려가는 출구(계단·엘리베이터)가 경로에서 빠진다
        // (휠체어가 계단 출구로 내려가게 될 수 있음). 출구 점은 연결 길로 지상과 이어져 있어 출구에서 출발·도착하면 그대로 출구에 붙는다.
        // 한계: 지하상가 안에 있는 사람도 가까운 지상 점에서 출발하는 것으로 계산된다. 들를 곳도 같다
        // 광장 길을 안 쓰는 모드(휠체어)는 광장 길로만 이어진 곳(문화전당 지하 마당 계단 등)에 붙지 않게, 광장 길을 뺀 덩어리에서 찾는다.
        // 안 그러면 거기 붙었다가 옆 길로 다시 붙으며 "이어진 길이 확인되지 않았습니다"가 붙는다(바로 옆 OSM 보도로 갈 수 있는데도) (2026-11-10)
        boolean noPlaza = profile.exclude().contains(Penalty.PLAZA);
        Set<Long> surface = underground || noPlaza
                ? graph.mainPart(e -> !(underground && RouteFinder.isUnderground(e))
                        && !(noPlaza && e.penalty().get(Penalty.PLAZA) >= 1))
                : null;
        List<RoadGraph.Snap> snaps = new ArrayList<>();
        for (int k = 0; k < points.size(); k++) {
            try {
                snaps.add(snapper.snap(graph, points.get(k), profile, surface));
            } catch (RouteException e) {
                throw noRoute(stops, k);
            }
        }
        // 휠체어 출발·도착·들를 곳이 인도 없는 골목 안이면 거기서 나가는 그 골목만 지난다 (2026-11-13). 다른 그런 골목은 계속 뺀다
        Set<Long> endAlleys = Snapper.endAlleys(graph, snaps, profile, props.endAlleyM());
        boolean closed = entrance != null && !entrance.inside();
        java.util.function.Predicate<RoadEdge> base = e -> profile.passable(e, alleysOk) && !(noTrail && RouteFinder.isTrail(e))
                && !(closed && RouteFinder.isUnderground(e));
        java.util.function.Predicate<RoadEdge> passable = e -> base.test(e) || endAlleys.contains(e.id());
        // 인도 없는 골목을 넣어 다시 찾을 때는 꼭 필요한 만큼만 지나게 그 골목 1m를 다른 길 factor m로 본다 (2026-11-13)
        double factor = props.noSidewalkAlleyFallbackFactor();
        ToDoubleFunction<RoadEdge> cost = alleysOk
                ? e -> RouteFinder.isNoSidewalkAlley(e) ? e.lengthM() * factor : e.lengthM() : RoadEdge::lengthM;
        Nearby nearby = nearby(graph, phase, now);
        // 커버율은 한 번만 계산해 경로 고르기와 어두운 구간 안내(FR-31)에 같이 쓴다
        Coverage cov = scorer.coverage(graph, phase, cond.night(), nearby);
        ToDoubleFunction<RoadEdge> score = scorer.extraCost(cov, phase);
        Long in = entrance(graph, snaps.get(snaps.size() - 2).node(), entrance, profile, passable, cost, score);
        List<RoadGraph.Snap> at = arrive(snaps, in);
        List<RoadEvent> av = reachable(graph, avoided, at);
        // 붙인 뒤에 찾는다: 출발·도착을 붙이며 나눈 길이면 신고 자리 쪽 조각
        java.util.Map<RoadEvent, Long> edges = reportEdges(graph, reports);
        ToDoubleFunction<RoadEdge> extra = withReports(score, reachable(graph, costed, at), edges, profile);
        RouteException failed;
        try {
            return joined(legs(graph, at, extra, score, profile, passable, cost, wideDetour, asides(graph, av, edges, profile), cov,
                    stops), at,
                    nearby, List.of(), cov, av, edges, profile.avoid(), in);
        } catch (RouteException e) {
            if (profile.exclude().isEmpty() && !noTrail) {
                throw e;
            }
            failed = e;
        }
        // 휠체어: 붙인 곳이 계단으로만 드나드는 구역일 수 있다. 등산로를 피할 때는 붙인 곳이 등산로 위일 수 있다 (2026-11-12). 지날 수 있는 길로 이어진 곳에 다시 붙여 한 번 더 찾는다.
        // 대부분의 요청은 위에서 찾으므로 연결 덩어리 계산은 못 찾았을 때만 한다.
        // 들를 곳이 있어도 모든 점을 함께 다시 붙이고 구간 전부를 다시 찾는다: 한 구간만 옮기면 앞 구간 끝 노드와 이어지지 않는다
        Set<Long> part = graph.mainPart(underground ? e -> passable.test(e) && !RouteFinder.isUnderground(e) : passable);
        // 휠체어 출발·도착점만 100m까지 (건물 안 출발·도착, 2026-11-13): 이미 그 안에 있으면 휠체어로 드나드는 길이 있다고 본다
        boolean stairsExcluded = profile.exclude().contains(Penalty.STAIRS);
        List<Snapper.Placed> placed = new ArrayList<>();
        for (int k = 0; k < points.size(); k++) {
            boolean end = k == 0 || k == points.size() - 1;
            double extraM = end && stairsExcluded && !reroute ? props.passableSnapOriginExtraM()
                    : props.passableSnapExtraM();
            try {
                // 연 골목에는 다시 붙이지 않는다: 붙이며 길을 나누면 새 번호라 endAlleys에서 빠진다
                placed.add(snapper.place(graph, points.get(k), snaps.get(k), part, base, extraM));
            } catch (RouteException e) {
                throw noRoute(stops, k);
            }
        }
        if (placed.stream().noneMatch(Snapper.Placed::moved)) {
            throw failed;
        }
        List<RoadGraph.Snap> placedSnaps = placed.stream().map(Snapper.Placed::snap).toList();
        // 다시 붙이며 나눈 길은 새 번호라 커버율이 없다. 다시 계산하지 않으면 어두운 구간 안내(FR-31)가 빈다
        cov = scorer.coverage(graph, phase, cond.night(), nearby);
        score = scorer.extraCost(cov, phase);
        Long movedIn = entrance(graph, placedSnaps.get(placedSnaps.size() - 2).node(), entrance, profile, passable, cost, score);
        List<RoadGraph.Snap> moved = arrive(placedSnaps, movedIn);
        List<RoadEvent> movedAv = reachable(graph, avoided, moved);
        // 다시 붙이며 길을 나눴으면 그 길은 지워지고 임시 조각 둘이 된다. 신고된 길이었으면 예전 번호로는 피하기·비용·지났는지에서
        // 그 신고가 조용히 빠지므로 다시 찾는다
        java.util.Map<RoadEvent, Long> movedEdges = reportEdges(graph, reports);
        List<RouteResult.Note> notes = new ArrayList<>();
        String way = profile.exclude().isEmpty() ? "등산로 없이"
                : profile.onlyStairsExcluded() ? (noTrail ? "계단·등산로 없이" : "계단 없이") : "휠체어로";
        // 도착을 지하 시설에 들어가는 곳으로 바꿨으면 도착점 근처 알림은 붙이지 않는다
        for (int k = 0; k < (movedIn == null ? placed.size() : placed.size() - 1); k++) {
            String which = k == 0 ? "출발점" : k == placed.size() - 1 ? "도착점" : stopName(stops, k - 1);
            Snapper.placeNote(which, placed.get(k), way).ifPresent(notes::add);
        }
        return joined(legs(graph, moved, withReports(score, reachable(graph, costed, moved), movedEdges, profile), score,
                profile, passable, cost, wideDetour, asides(graph, movedAv, movedEdges, profile), cov, stops), moved,
                nearby, notes, cov, movedAv, movedEdges, profile.avoid(), movedIn);
    }

    /** 구간 결과를 이어 하나의 시도로. 붙인 거리는 출발·도착만 더한다 (들를 곳 가게에서 길까지는 머무는 일에 가깝다) */
    private static Attempt joined(List<RouteFinder.Found> legs, List<RoadGraph.Snap> at, Nearby nearby,
            List<RouteResult.Note> notes, Coverage cov, List<RoadEvent> avoided,
            java.util.Map<RoadEvent, Long> reportEdges, java.util.function.Predicate<RoadEdge> avoid, Long entered) {
        return new Attempt(join(legs, avoid), legs, at.get(0).distanceM() + at.get(at.size() - 1).distanceM(), nearby, notes,
                cov, avoided, reportEdges, entered);
    }

    /** 붙인 점 사이 구간마다 최단·안전 경로를 찾는다. 우회 한도는 구간마다 따로 지킨다 */
    private List<RouteFinder.Found> legs(RoadGraph graph, List<RoadGraph.Snap> at, ToDoubleFunction<RoadEdge> extra,
            ToDoubleFunction<RoadEdge> score, RouteProperties.Profile profile, java.util.function.Predicate<RoadEdge> passable,
            ToDoubleFunction<RoadEdge> cost, double wideDetour, List<java.util.function.Predicate<RoadEdge>> asides, Coverage cov,
            List<Place> stops) {
        List<RouteFinder.Found> out = new ArrayList<>();
        for (int k = 1; k < at.size(); k++) {
            try {
                out.add(find(graph, at.get(k - 1).node(), at.get(k).node(), extra, score, profile, passable, cost, wideDetour,
                        props.rain().walkMore(), asides, cov));
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
        // 모든 점이 같은 길 점에 붙어 길이 0이어도 들를 곳 없는 요청처럼 그대로 안내한다. SAME_POINT는 직선거리(validate)로만 정한다
        RoadPath shortest = RoadPath.join(legs.stream().map(RouteFinder.Found::shortest).toList());
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
                    rt.unpavedM(), rt.surfaceUnknownM(), marks, rt.crossAt(), rt.alleys(), rt.alleyM(), rt.narrows(),
                    rt.narrowM());
        }).toList();
        return new RouteResult(r.conditions(), routes, r.explanation(), r.facilities(), r.dataQuality());
    }

    /**
     * 최단·안전 경로. 비 올 때 넓힌 한도(wideDetour)가 그 사람의 한도보다 크면 두 번 찾아, 넓힌 쪽 안전 경로의 지붕 이득이
     * 더 걷는 만큼의 젖는 부담을 갚을 때만 그쪽을 쓴다 (2026-11-09, walkMore = rain.walk-more). 비 때문에 더 걷는 것은 지붕 아래로 가기 위해서만이다. 넓힌 한도로 지붕 이득 없이
     * 다른 기준만 더 챙기면 비를 맞으며 더 걷게 된다 (하네스: 노인 낮 평균 +104m 중 지붕 15m)
     */
    private static RouteFinder.Found find(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            ToDoubleFunction<RoadEdge> score, RouteProperties.Profile profile, java.util.function.Predicate<RoadEdge> passable,
            ToDoubleFunction<RoadEdge> cost, double wideDetour, double walkMore,
            List<java.util.function.Predicate<RoadEdge>> asides, Coverage cov) {
        RouteFinder.Found narrow = findOnce(graph, from, to, extra, score, profile.maxDetour(), profile, passable, cost, asides);
        // 범위에 지붕 길이 하나도 없으면 지붕 이득도 없어 결과가 같다. 광주는 걷는 지붕 길이 2.8km뿐이라 대부분의 비 요청이 여기서 끝난다
        if (wideDetour <= profile.maxDetour() || graph.graph().edgeSet().stream()
                .noneMatch(e -> e.penalty().get(Penalty.COVERED) >= 1 || RouteFinder.isUnderground(e))) {
            return narrow;
        }
        RouteFinder.Found wide = findOnce(graph, from, to, extra, score, wideDetour, profile, passable, cost, asides);
        // 비 오는 날 지붕 없는 1m = 지붕 아래 (1 + r)m (F1과 같은 교환비). 더 걷는 거리만큼의 젖는 부담을 지붕 이득이 갚을 때만
        // 넓힌 한도를 쓴다: r × 지붕 이득 ≥ (1 + r) × 더 걷는 길이. 예전 1m 조건은 지붕 10m 때문에 비를 맞으며 380m를 더 걷는 길도 골랐다
        double gain = roofM(wide.safe(), cov) - roofM(narrow.safe(), cov);
        double more = wide.safe().lengthM() - narrow.safe().lengthM();
        return gain > 0 && walkMore * gain >= (1 + walkMore) * more ? wide : narrow;
    }

    /**
     * 한 번 찾기. 비용만 신고의 비용(extra에만 있고 score에는 없음) 때문에 우회 한도에 걸렸으면 그 비용 없이 다시 찾아 그 결과를 쓴다 (2026-11-10).
     * 걸림(detourCapped)은 "기준에 맞춘 길이 한도를 넘었다"는 뜻이라 "지붕 있는 길은 너무 많이 돌아서…"·"더 안전한 길은…"으로 말하는데,
     * 신고를 피하는 길이 한도를 넘은 것이면 틀린 문장이 된다. 걸렸으면 안전 경로가 최단(피하기 뒤 기준 경로) 그대로라 신고 비용이 얻은 것도 없고,
     * 비용 없이 찾은 쪽은 기준에 맞춘 길을 줄 수 있으면 그 길을, 아니면 같은 길을 준다. 다시 찾는 것은 걸렸고 비용만 신고가 있을 때뿐이다
     */
    private static RouteFinder.Found findOnce(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            ToDoubleFunction<RoadEdge> score, double maxDetour, RouteProperties.Profile profile,
            java.util.function.Predicate<RoadEdge> passable, ToDoubleFunction<RoadEdge> cost,
            List<java.util.function.Predicate<RoadEdge>> asides) {
        RouteFinder.Found f = RouteFinder.find(graph, from, to, extra, maxDetour, passable, profile.avoid(), asides, cost);
        // withReports는 비용만 신고가 없으면 score를 그대로 돌려준다
        return f.detourCapped() && extra != score
                ? RouteFinder.find(graph, from, to, score, maxDetour, passable, profile.avoid(), asides, cost) : f;
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

    /**
     * 일몰 후 ~ 일출 전이 밤. auto일 때 leadMin만큼 일몰 전부터 밤으로 본다 (어린이 60분).
     * 비 기준(지붕 기준을 더하는지)과 그 출처(roofSource: auto·manual)를 붙인다
     */
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
