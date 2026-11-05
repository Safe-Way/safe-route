package com.saferoute.route;

import java.time.Clock;
import java.util.ArrayList;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
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
import com.saferoute.route.data.RoadGraphSource;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.route.engine.search.RouteFinder;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;
import com.saferoute.situation.NightResult;

/**
 * 경로 엔진의 입구 (요구사항 8.1). 순서만 정하고, 각 단계는 맡은 클래스가 한다.
 * <ol>
 *   <li>요청 검사 (SAME_POINT, TOO_FAR, OUT_OF_AREA)</li>
 *   <li>밤 판단: C파트 {@link NightJudge}. 어린이처럼 일몰 몇 분 전부터 밤으로 보는 모드는 여기서 앞당긴다</li>
 *   <li>주변 도로망 읽기, 출발·도착을 길에 붙이기: {@link RoadGraphSource}. 휠체어는 지날 수 있는 길로 이어진 곳에 붙인다</li>
 *   <li>주변 시설을 한 번 읽기: {@link PoiSource}. 점수·설명·지도 아이콘이 같은 목록을 쓴다</li>
 *   <li>모드·낮밤 기준으로 길마다 더할 비용: {@link RouteScorer} (시설 커버율을 요청 때 계산)</li>
 *   <li>최단·안전 경로: {@link RouteFinder}. 못 찾으면 범위를 넓혀 한 번 더 찾는다.
 *       휠체어가 비포장을 지나면 피할 때 얼마나 더 걸리는지 설명에 알린다</li>
 *   <li>시설 세기, 설명, 응답 만들기: {@link RouteResultAssembler}</li>
 * </ol>
 * 사용: {@code RouteResult r = routeService.route(request);}
 * 경로를 못 주면 {@link RouteException}을 던진다. {@code error().httpStatus()}와 {@code error().name()}을
 * 그대로 응답에 쓰면 된다. night 값이 auto·on·off가 아니거나 mode를 모르면 IllegalArgumentException(400으로 보면 됨).
 *
 * 사용자 위치를 남기지 않도록 출발·도착 좌표는 로그에 쓰지 않는다 (NFR-07).
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

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

    public RouteService(RoadGraphSource roads, PoiSource pois, NightJudge nightJudge, RouteScorer scorer,
            RouteResultAssembler assembler, RouteProperties props, Clock clock) {
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
            log.info("경로 계산 {}ms, 경로 {}개", elapsedMs(started), result.routes().size());
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
        NightOption option = NightOption.from(req.night());
        RouteProperties.Profile profile = props.profile(req.mode());
        validate(start, end);

        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KOREA);
        Conditions cond = conditions(start, now, option, profile.nightLeadMin());
        RouteProperties.Phase phase = profile.phase(cond.night());

        Attempt a = attempt(roads.load(BBox.around(List.of(start, end), props.searchMarginM())),
                start, end, cond, profile, phase, now);
        if (a == null && props.retryMarginM() > props.searchMarginM()) {
            // 강·철도·큰 도로를 범위 밖 다리로 돌아가야 하는 길. 넓혀 한 번 더 찾는다 (우회 한도 등 규칙은 같다)
            a = attempt(roads.load(BBox.around(List.of(start, end), props.retryMarginM())),
                    start, end, cond, profile, phase, now);
        }
        if (a == null) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        RouteResult result = assembler.assemble(cond, profile, phase, a.found(), start, end, a.snapM(), a.nearby());
        if (profile.avoidUnpaved()) {
            result = withSurfaceNote(result, a.found(), profile);
        }
        return withNote(result, a.snapNote());
    }

    /** 한 도로망에서 찾은 결과. snapNote: 휠체어가 가장 가까운 길이 아닌 곳에 붙었을 때 알림 (없으면 "") */
    private record Attempt(RouteFinder.Found found, double snapM, Nearby nearby, String snapNote) {
    }

    /** 길에 붙인 곳. moved: 모든 길 기준으로 가장 가까운 곳이 아닌 곳에 붙였는지 (휠체어) */
    private record Placed(RoadGraph.Snap snap, boolean moved) {
    }

    /** 이 도로망에서 경로를 찾는다. 길에 붙이지 못하거나 이어지지 않으면 null */
    private Attempt attempt(RoadGraph graph, double[] start, double[] end, Conditions cond,
            RouteProperties.Profile profile, RouteProperties.Phase phase, ZonedDateTime now) {
        RoadGraph.Snap from;
        RoadGraph.Snap to;
        try {
            from = snap(graph, start);
            to = snap(graph, end);
        } catch (RouteException e) {
            return null;
        }
        Nearby nearby = nearby(graph, phase, now);
        ToDoubleFunction<RoadEdge> extra = scorer.extraCost(graph, phase, cond.night(), nearby);
        try {
            return new Attempt(RouteFinder.find(graph, from.node(), to.node(), extra, profile.maxDetour(),
                    profile::passable, profile.avoid()), from.distanceM() + to.distanceM(), nearby, "");
        } catch (RouteException e) {
            if (profile.exclude().isEmpty()) {
                return null;
            }
        }
        // 휠체어: 붙인 곳이 계단으로만 드나드는 구역일 수 있다. 지날 수 있는 길로 이어진 곳에 다시 붙여 한 번 더 찾는다.
        // 대부분의 요청은 위에서 찾으므로 연결 덩어리 계산은 못 찾았을 때만 한다
        Set<Long> part = graph.mainPart(profile::passable);
        try {
            Placed f = place(graph, start, from, part);
            Placed t = place(graph, end, to, part);
            if (!f.moved() && !t.moved()) {
                return null;
            }
            RouteFinder.Found found = RouteFinder.find(graph, f.snap().node(), t.snap().node(), extra,
                    profile.maxDetour(), profile::passable, profile.avoid());
            return new Attempt(found, f.snap().distanceM() + t.snap().distanceM(), nearby,
                    placeNote("출발점", f) + placeNote("도착점", t));
        } catch (RouteException e) {
            return null;
        }
    }

    /**
     * 안내하는 경로의 바닥을 설명 문장 뒤에 알린다 (휠체어, 2026-11-04 결정).
     * <ul>
     *   <li>비포장을 지나면: 피하면 얼마나 더 걸리는지, 비포장 없이는 이어지지 않으면 피하는 길이 없다고</li>
     *   <li>재질을 모르는 산길을 지나면: "확인 안 됨"</li>
     * </ul>
     */
    static RouteResult withSurfaceNote(RouteResult r, RouteFinder.Found found, RouteProperties.Profile profile) {
        double unpaved = lengthOf(found.safe(), RouteFinder::isUnpaved);
        double unknown = lengthOf(found.safe(), e -> e.penalty().get(Penalty.SURFACE_UNKNOWN) >= 1);
        StringBuilder note = new StringBuilder();
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
            note.append(" (주의: 비포장길 약 %dm를 지납니다. %s)".formatted(Math.round(unpaved), avoid));
        }
        if (unknown > 0) {
            note.append(" (확인 안 됨: 바닥 재질을 모르는 산길 약 %dm를 지납니다)".formatted(Math.round(unknown)));
        }
        return withNote(r, note.toString());
    }

    /** 설명 문장 끝에 알림을 붙인다 */
    private static RouteResult withNote(RouteResult r, String note) {
        if (note.isEmpty()) {
            return r;
        }
        RouteResult.Explanation e = r.explanation();
        return new RouteResult(r.conditions(), r.routes(),
                new RouteResult.Explanation(e.text() + note, e.extraM(), e.extraMin(), e.diff()),
                r.facilities(), r.dataQuality());
    }

    private static double lengthOf(com.saferoute.route.engine.search.RoadPath p,
            java.util.function.Predicate<RoadEdge> which) {
        return p.edges().stream().filter(which).mapToDouble(RoadEdge::lengthM).sum();
    }

    /** 도로망 범위를 가장 큰 반경만큼 넓혀 그 안 시설을 한 번 읽는다 */
    private Nearby nearby(RoadGraph graph, RouteProperties.Phase phase, ZonedDateTime now) {
        RouteProperties.CountRadius c = props.countRadiusM();
        double margin = Math.max(Math.max(Math.max(phase.helpM(), phase.restM()), Math.max(phase.schoolM(),
                props.coverage().cctvM())), Math.max(Math.max(c.light(), c.cctv()), Math.max(c.bell(), c.help())));
        BBox box = BBox.around(new ArrayList<>(graph.nodes().values()), margin);
        return new Nearby(pois.within(box, POI_KINDS), pois.holidays(), now.toLocalDateTime(), pois.dataDate());
    }

    /** 일몰 후 ~ 일출 전이 밤. auto일 때 leadMin만큼 일몰 전부터 밤으로 본다 (어린이 60분) */
    Conditions conditions(double[] start, ZonedDateTime now, NightOption option, int leadMin) {
        NightResult r = nightJudge.judge(start[1], start[0], now, option);
        boolean night = r.night();
        if (option == NightOption.AUTO && !night && leadMin > 0 && r.sunset() != null) {
            LocalTime t = now.toLocalTime();
            night = !t.isBefore(r.sunset().minusMinutes(leadMin));
        }
        return new Conditions(night, r.nightSource(), r.sunset());
    }

    private void validate(double[] start, double[] end) {
        double straight = Geo.distance(start, end);
        if (straight < props.samePointM()) {
            throw new RouteException(RouteError.SAME_POINT);
        }
        if (straight > props.tooFarM()) {
            throw new RouteException(RouteError.TOO_FAR);
        }
        if (!roads.covers(start[0], start[1]) || !roads.covers(end[0], end[1])) {
            throw new RouteException(RouteError.OUT_OF_AREA);
        }
    }

    /** 가장 가까운 길 노드. 너무 멀면 길 위가 아니라고 본다 */
    private RoadGraph.Snap snap(RoadGraph graph, double[] p) {
        RoadGraph.Snap s = graph.nearest(p[0], p[1]);
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
    private Placed place(RoadGraph graph, double[] p, RoadGraph.Snap any, Set<Long> part) {
        if (part.contains(any.node())) {
            return new Placed(any, false);
        }
        RoadGraph.Snap s = graph.nearest(p[0], p[1], part);
        if (s == null || s.distanceM() - any.distanceM() > props.passableSnapExtraM()) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        return new Placed(s, true);
    }

    /** 옆 길에 붙였으면 남은 거리를 알린다. 계단으로만 드나드는 곳이라 휠체어 길이 확인되지 않은 구간이다 */
    private static String placeNote(String which, Placed p) {
        return p.moved() ? " (주의: %s 근처 약 %dm는 휠체어로 이어진 길이 확인되지 않았습니다)".formatted(which,
                Math.round(p.snap().distanceM())) : "";
    }

    /** {경도, 위도} */
    private static double[] point(Place p) {
        if (p == null || p.lat() == null || p.lon() == null) {
            throw new IllegalArgumentException("origin과 destination의 lat·lon이 필요합니다");
        }
        return new double[] {p.lon(), p.lat()};
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
