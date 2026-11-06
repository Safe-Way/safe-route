package com.saferoute.route.engine.explain;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadEvents;
import com.saferoute.route.engine.facility.Availability;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 찾은 두 경로로 응답(요구사항 8.3)을 만든다: 거리·시간, 지나는 시설 수, 인도 쪽, 차이 설명, 지도 아이콘, 데이터 상태.
 * 걷는 동안 안내(2차)에 쓰는 시설 자리(FR-30)·어두운 구간(FR-31)·꺾는 곳(FR-34)·횡단보도 구간도 여기서 붙인다.
 * 지나는 행사·돌발상황 구간과 그 알림 문장도 붙인다 (2026-11-06).
 */
@Component
public class RouteResultAssembler {

    private static final DateTimeFormatter ENDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FacilityCounter counter;
    private final RouteProperties props;

    public RouteResultAssembler(FacilityCounter counter, RouteProperties props) {
        this.counter = counter;
        this.props = props;
    }

    /**
     * @param profile    누가 걷는지 (걸리는 시간을 이 사람의 보행 속도로 계산)
     * @param phase      지금 쓰는 기준 (낮 또는 밤). 설명 문장에 쓴다
     * @param start, end 요청한 출발·도착 {경도, 위도}
     * @param snapM      출발·도착에서 가장 가까운 길 노드까지 거리의 합
     * @param nearby     이 요청에서 읽은 주변 시설 (점수 계산과 같은 목록)
     */
    /**
     * @param cov    경로를 고를 때 쓴 커버율. 밤이면 고른 경로의 어두운 구간(FR-31)을 여기서 꺼낸다
     * @param events 지금 진행 중인 주변 행사·돌발상황
     * @param avoids 이 요청에서 둘레를 피하려 한 것 (모드·출발·도착으로 RouteService가 정함). 나머지는 알리기만
     */
    public RouteResult assemble(Conditions cond, RouteProperties.Profile profile, RouteProperties.Phase phase,
            RouteFinder.Found found, double[] start, double[] end, double snapM, Nearby nearby, Coverage cov,
            List<RoadEvent> events, java.util.function.Predicate<RoadEvent> avoids) {
        boolean same = found.safe().sameAs(found.shortest());
        List<double[]> shortLine = found.shortest().lineFrom(start, end);
        List<double[]> safeLine = found.safe().lineFrom(start, end);
        int shortM = (int) Math.round(found.shortest().lengthM() + snapM);
        int safeM = (int) Math.round(found.safe().lengthM() + snapM);

        // 쉼 곳은 경로 고를 때처럼 지금 이용 가능한 곳만 센다
        List<Poi> pois = nearby.pois().stream()
                .filter(p -> FacilityGroup.of(p.kind()) != FacilityGroup.REST
                        || Availability.usable(p, nearby.at(), nearby.holidays()))
                .toList();
        List<Counts> counts = counter.countAlong(List.of(shortLine, safeLine), pois);
        Counts shortCounts = counts.get(0);
        Counts safeCounts = counts.get(1);

        List<RouteResult.Event> shortEvents = events(events, shortLine, avoids);
        List<RouteResult.Event> safeEvents = events(events, safeLine, avoids);

        List<RouteResult.Route> routes = new ArrayList<>();
        if (!same) {
            routes.add(route("shortest", shortM, profile.minutes(shortM), shortLine, shortCounts,
                    covered(found.shortest(), phase, cov),
                    Sidewalks.along(found.shortest(), found.shortest().startOffset(start)),
                    dark(cond, found.shortest(), start, cov), turns(found.shortest(), start),
                    crossings(found.shortest(), start), stairs(found.shortest(), start), shortEvents));
        }
        routes.add(route("safe", safeM, profile.minutes(safeM), safeLine, safeCounts,
                covered(found.safe(), phase, cov),
                Sidewalks.along(found.safe(), found.safe().startOffset(start)),
                dark(cond, found.safe(), start, cov), turns(found.safe(), start),
                crossings(found.safe(), start), stairs(found.safe(), start), safeEvents));

        int extraM = same ? 0 : safeM - shortM;
        // 반올림을 따로 하면 "22분 / 24분"인데 "(1분) 더"가 나올 수 있어, 두 경로 시간의 차로 구한다
        int extraMin = same ? 0 : profile.minutes(safeM) - profile.minutes(shortM);
        Map<FacilityGroup, Integer> diff = safeCounts.minus(shortCounts);
        ExplanationBuilder.Avoided avoided = same ? ExplanationBuilder.Avoided.NONE : new ExplanationBuilder.Avoided(
                profile.avoidStairs() && avoids(found, RouteFinder::isStairs),
                profile.avoidUnpaved() && avoids(found, RouteFinder::isUnpaved),
                shortEvents.stream().anyMatch(e -> e.avoid() && safeEvents.stream()
                        .noneMatch(x -> x.kind().equals(e.kind()) && x.title().equals(e.title()))));
        String summary = ExplanationBuilder.text(cond, profile, phase, same, found.detourCapped(), extraM, extraMin,
                diff, avoided);
        summary = ExplanationBuilder.withEvents(summary, extraM, extraMin, same ? List.of() : shortEvents, safeEvents);
        List<RouteResult.Note> notes = new ArrayList<>();
        if (profile.avoidStairs()) {
            notes.addAll(ExplanationBuilder.stairsNotes(stairs(found.safe(), start).size(), found.avoidFree() != null));
        }
        notes.addAll(ExplanationBuilder.eventNotes(summary, same ? List.of() : shortEvents, safeEvents));
        summary = ExplanationBuilder.withWarnings(summary, notes);

        return new RouteResult(
                RouteResult.Conditions.of(cond),
                routes,
                RouteResult.Explanation.of(summary, notes, extraM, extraMin, FacilityGroup.keyed(diff)),
                marks(same ? null : shortCounts, safeCounts, same ? null : shortLine, safeLine),
                dataQuality(nearby));
    }

    private RouteResult.Route route(String kind, int meters, int minutes, List<double[]> line, Counts counts,
            Map<String, Integer> covered, List<RouteResult.Sidewalk> sidewalks, List<RouteResult.Stretch> dark, List<RouteResult.Turn> turns,
            List<RouteResult.Stretch> crossings, List<RouteResult.Stair> stairs, List<RouteResult.Event> events) {
        return new RouteResult.Route(kind, meters, minutes, line, FacilityGroup.keyed(counts.byGroup()), covered,
                sidewalks,
                dark, turns, crossings, stairs, events);
    }

    /** 이 요청 기준마다 경로가 그 범위 안을 걷는 길이(m). 키는 기준 이름 소문자 (school, help, rest, light, cctv) */
    static Map<String, Integer> covered(RoadPath path, RouteProperties.Phase phase, Coverage cov) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (com.saferoute.route.engine.score.Criterion c : phase.weights().keySet()) {
            double m = 0;
            for (RoadEdge e : path.edges()) {
                m += e.lengthM() * cov.covered(e, c);
            }
            out.put(c.name().toLowerCase(java.util.Locale.ROOT), (int) Math.round(m));
        }
        return out;
    }

    /** 최단 경로에 있는 길을 안전 경로는 지나지 않는지 */
    private static boolean avoids(RouteFinder.Found found, java.util.function.Predicate<RoadEdge> which) {
        return found.shortest().edges().stream().anyMatch(which) && found.safe().edges().stream().noneMatch(which);
    }

    /** 경로 위 계단 구간. 경사로 값이 같은 계단이 이어지면 한 구간으로 묶는다 */
    static List<RouteResult.Stair> stairs(RoadPath path, double[] start) {
        List<RouteResult.Stair> out = new ArrayList<>();
        double at = Geo.distance(start, path.line().get(0));
        double from = -1;
        Boolean ramp = null;
        for (RoadEdge e : path.edges()) {
            boolean stair = e.penalty().get(Penalty.STAIRS) >= 1;
            Boolean r = e.penalty().wheelchairRamp();
            if (from >= 0 && (!stair || !java.util.Objects.equals(r, ramp))) {
                out.add(new RouteResult.Stair((int) Math.round(from), (int) Math.round(at), ramp));
                from = -1;
            }
            if (stair && from < 0) {
                from = at;
                ramp = r;
            }
            at += e.lengthM();
        }
        if (from >= 0) {
            out.add(new RouteResult.Stair((int) Math.round(from), (int) Math.round(at), ramp));
        }
        return out;
    }

    /** 경로 선이 영향 반경을 지나는 행사·돌발상황. 들어가는 곳 순서 */
    private static List<RouteResult.Event> events(List<RoadEvent> events, List<double[]> line,
            java.util.function.Predicate<RoadEvent> avoids) {
        List<RouteResult.Event> out = new ArrayList<>();
        for (RoadEvent e : events) {
            double[] s = RoadEvents.stretch(e, line);
            if (s != null) {
                out.add(new RouteResult.Event(e.kind(), e.title(), e.place(), e.message(),
                        e.endsAt() == null ? null : e.endsAt().format(ENDS),
                        (int) Math.round(s[0]), (int) Math.round(s[1]), avoids.test(e)));
            }
        }
        out.sort(Comparator.comparingInt(RouteResult.Event::fromM));
        return out;
    }

    private static List<RouteResult.Stretch> crossings(RoadPath path, double[] start) {
        double startM = Geo.distance(start, path.line().get(0));
        return Turns.crossings(path, startM).stream()
                .map(c -> new RouteResult.Stretch((int) Math.round(c[0]), (int) Math.round(c[1])))
                .toList();
    }

    private List<RouteResult.Turn> turns(RoadPath path, double[] start) {
        RouteProperties.Turn t = props.turn();
        double startM = Geo.distance(start, path.line().get(0));
        return Turns.along(path, startM, t.minAngle(), t.lookM(), t.linkM()).stream()
                .map(x -> new RouteResult.Turn((int) Math.round(x.atM()), x.left() ? "left" : "right",
                        (int) Math.round(x.angle()), x.crossing(),
                        x.thenM() == null ? null : (int) Math.round(x.thenM())))
                .toList();
    }

    /** 밤에만. 시작 거리는 요청한 출발점부터 재서 시설 along.atM과 같은 기준으로 맞춘다 */
    private List<RouteResult.Stretch> dark(Conditions cond, RoadPath path, double[] start, Coverage cov) {
        if (!cond.night()) {
            return List.of();
        }
        RouteProperties.DarkStretch d = props.darkStretch();
        double startM = Geo.distance(start, path.line().get(0));
        return DarkStretches.along(path, startM, cov, d.mergeGapM(), d.minM()).stream()
                .map(s -> new RouteResult.Stretch((int) Math.round(s.fromM()), (int) Math.round(s.toM())))
                .toList();
    }

    /**
     * 지도 아이콘. 도움 지점·비상벨·CCTV·등 순서로 최대 개수까지. shortCounts가 null이면 경로가 하나.
     * 시설마다 지나는 경로에서의 자리(몇 m 지점, 왼쪽·오른쪽)를 붙인다 (FR-30)
     */
    private List<RouteResult.FacilityMark> marks(Counts shortCounts, Counts safeCounts, List<double[]> shortLine,
            List<double[]> safeLine) {
        Set<Poi> onShort = shortCounts == null ? Set.of() : new HashSet<>(shortCounts.passed());
        Set<Poi> onSafe = new HashSet<>(safeCounts.passed());
        Set<Poi> all = new HashSet<>(onShort);
        all.addAll(onSafe);
        return all.stream()
                .sorted(Comparator.comparingInt((Poi p) -> markOrder(p.kind())).thenComparingLong(Poi::id))
                .limit(props.maxFacilities())
                .map(p -> {
                    Map<String, RouteResult.Along> along = new LinkedHashMap<>();
                    if (onShort.contains(p)) {
                        along.put("shortest", along(p, shortLine));
                    }
                    if (onSafe.contains(p)) {
                        along.put("safe", along(p, safeLine));
                    }
                    String on = along.size() == 2 ? "both" : along.keySet().iterator().next();
                    return new RouteResult.FacilityMark(type(p), p.lat(), p.lon(), on, along);
                })
                .toList();
    }

    private static RouteResult.Along along(Poi p, List<double[]> line) {
        Geo.Along a = Geo.along(new double[] {p.lon(), p.lat()}, line);
        return new RouteResult.Along((int) Math.round(a.atM()), a.left() ? "left" : "right");
    }

    /** 지도 아이콘이 많을 때 먼저 남기는 순서. 등은 너무 많아서 맨 뒤 */
    static int markOrder(String kind) {
        return switch (kind) {
            case "police", "store", "guard_house" -> 0;
            case "bell" -> 1;
            case "cctv", "shelter", "toilet", "school_zone" -> 2;
            default -> 3;
        };
    }

    /** 응답의 type: 시설 표 이름 (poi 종류에서 바꿈. 등은 poi의 세부 종류로 가로등·보안등을 나눈다) */
    static String type(Poi p) {
        return switch (p.kind()) {
            case "light" -> "가로등".equals(p.sub()) ? "STREETLIGHT" : "SECURITY_LIGHT";
            case "cctv" -> "CCTV";
            case "bell" -> "EMERGENCY_BELL";
            case "police" -> "POLICE";
            case "store" -> "CONVENIENCE_STORE";
            case "guard_house" -> "CHILD_SAFETY_HOUSE";
            default -> p.kind().toUpperCase();
        };
    }

    /**
     * 데이터 부족 알림은 띄우지 않는다 (2026-11-06). 늘 OK, message null, 시설 데이터 기준일만 준다.
     * <ul>
     *   <li>안전 경로는 같은 데이터로 최단과 비교한 상대적인 길이지, "이 길은 안전하다"는 절대 판정이 아니다</li>
     *   <li>데이터가 빈 길(광주는 대학 캠퍼스 안 조명·CCTV)은 시설이 없는 길로 계산돼 오히려 피하는 쪽이라,
     *       빈 곳을 안전하다고 하는 일이 없다 (NFR-04)</li>
     *   <li>캠퍼스를 조금만 지나도 "이 지역은 안전 데이터가 부족합니다"가 떠서 지역 전체가 위험해 보였다</li>
     * </ul>
     * 앱이 깨지지 않게 칸은 남긴다.
     */
    private static RouteResult.DataQuality dataQuality(Nearby nearby) {
        return new RouteResult.DataQuality("OK", null,
                nearby.dataDate() == null ? null : nearby.dataDate().toString());
    }
}
