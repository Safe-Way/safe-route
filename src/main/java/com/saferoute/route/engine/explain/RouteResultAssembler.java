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
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.score.RouteScorer;
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
    /** 남은 골목길이 이보다 짧으면 알리지 않는다. 출발·도착을 붙일 때 들어가는 몇 m 골목 입구까지 알리면 거의 모든 경로에 붙는다 */
    static final int ALLEY_NOTE_MIN_M = 30;

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
        return assemble(cond, profile, phase, found, start, end, snapM, nearby, cov, events, avoids, (e, path) -> true,
                new RoadGraph(Map.of(), List.of()), List.of());
    }

    /**
     * @param passes     경로가 그 행사·신고를 지나는지 (반경 안을 지난 것 중에서 더 거른다). 신고는 신고된 길을 지날 때만 (2026-11-10)
     * @param graph      경로를 찾은 도로망. 큰 길을 건너는 곳의 교차로·횡단보도 길을 본다 ({@link SideChanges})
     * @param crosswalks 횡단보도 점 {경도, 위도}
     */
    public RouteResult assemble(Conditions cond, RouteProperties.Profile profile, RouteProperties.Phase phase,
            RouteFinder.Found found, double[] start, double[] end, double snapM, Nearby nearby, Coverage cov,
            List<RoadEvent> events, java.util.function.Predicate<RoadEvent> avoids,
            java.util.function.BiPredicate<RoadEvent, RoadPath> passes, RoadGraph graph, List<double[]> crosswalks) {
        boolean same = found.safe().sameAs(found.shortest());
        List<double[]> shortLine = found.shortest().lineFrom(start, end);
        List<double[]> safeLine = found.safe().lineFrom(start, end);
        int shortM = (int) Math.round(found.shortest().lengthM() + snapM);
        int safeM = (int) Math.round(found.safe().lengthM() + snapM);

        // 경로 고를 때처럼 지금 쓸 수 있는 시설만 센다 (밤 도움 시설, 운영 시간).
        // 지하 구간은 빼고 지상 조각만 센다: 지하를 걷는 사람은 바로 위 지상 가로등·CCTV를 지나지 않는다 (2026-11-09).
        // 아이콘의 경로 위 자리(along)는 전체 선 기준 그대로
        List<Poi> pois = nearby.pois().stream().filter(p -> RouteScorer.usableNow(p, cond.night(), nearby)).toList();
        List<Counts> counts = counter.countAlongPieces(List.of(found.shortest().surfaceLines(start, end),
                found.safe().surfaceLines(start, end)), pois);
        Counts shortCounts = counts.get(0);
        Counts safeCounts = counts.get(1);

        List<RouteResult.Event> shortEvents = events(events, shortLine, avoids,
                e -> passes.test(e, found.shortest()));
        List<RouteResult.Event> safeEvents = events(events, safeLine, avoids, e -> passes.test(e, found.safe()));
        // 응답 covered와 설명의 지붕 있는 길 차이가 같은 값을 쓰도록 한 번만 계산한다
        Map<String, Integer> shortCovered = same ? Map.of() : covered(found.shortest(), phase, cov);
        Map<String, Integer> safeCovered = covered(found.safe(), phase, cov);

        // 골목길 구간 (2026-11-12). 응답 alleys와 설명의 줄인 길이·남은 길이가 같은 값을 쓴다
        List<RouteResult.Alley> shortAlleys = Sidewalks.alleys(found.shortest(), found.shortest().startOffset(start));
        List<RouteResult.Alley> safeAlleys = Sidewalks.alleys(found.safe(), found.safe().startOffset(start));

        List<RouteResult.Route> routes = new ArrayList<>();
        if (!same) {
            routes.add(route("shortest", shortM, profile.minutes(shortM), shortLine, shortCounts,
                    shortCovered,
                    Sidewalks.along(found.shortest(), found.shortest().startOffset(start)),
                    dark(cond, found.shortest(), start, cov), turns(found.shortest(), start),
                    crossings(found.shortest(), start),
                    SideChanges.along(found.shortest(), found.shortest().startOffset(start), graph, crosswalks),
                    stairs(found.shortest(), start), shortEvents, found.shortest(), shortAlleys));
        }
        SideChanges.Found safeCross = SideChanges.find(found.safe(), found.safe().startOffset(start), graph, crosswalks);
        routes.add(route("safe", safeM, profile.minutes(safeM), safeLine, safeCounts,
                safeCovered,
                Sidewalks.along(found.safe(), found.safe().startOffset(start)),
                dark(cond, found.safe(), start, cov), turns(found.safe(), start),
                crossings(found.safe(), start),
                safeCross.crossAt(),
                stairs(found.safe(), start), safeEvents, found.safe(), safeAlleys));

        int extraM = same ? 0 : safeM - shortM;
        // 반올림을 따로 하면 "22분 / 24분"인데 "(1분) 더"가 나올 수 있어, 두 경로 시간의 차로 구한다
        int extraMin = same ? 0 : profile.minutes(safeM) - profile.minutes(shortM);
        Map<FacilityGroup, Integer> diff = safeCounts.minus(shortCounts);
        ExplanationBuilder.Avoided avoided = same ? ExplanationBuilder.Avoided.NONE : new ExplanationBuilder.Avoided(
                profile.avoidStairs() ? stairs(found.shortest(), start).size() : 0,
                profile.avoidStairs() ? stairs(found.safe(), start).size() : 0,
                profile.avoidUnpaved() && avoids(found, RouteFinder::isUnpaved),
                !ExplanationBuilder.avoided(shortEvents, safeEvents).isEmpty());
        int roofGainM = same ? 0 : safeCovered.getOrDefault("roof", 0) - shortCovered.getOrDefault("roof", 0);
        int alleyCutM = same ? 0 : alleyM(shortAlleys) - alleyM(safeAlleys);
        String summary = ExplanationBuilder.text(cond, profile, phase, same, found.detourCapped(), extraM, extraMin,
                diff, avoided, roofGainM, alleyCutM);
        summary = ExplanationBuilder.withEvents(summary, extraM, extraMin, same ? List.of() : shortEvents, safeEvents);
        List<RouteResult.Note> notes = new ArrayList<>();
        if (profile.avoidStairs()) {
            notes.addAll(ExplanationBuilder.stairsNotes(stairs(found.safe(), start).size(), found.avoidFree() != null));
        }
        notes.addAll(ExplanationBuilder.eventNotes(summary, same ? List.of() : shortEvents, safeEvents));
        ExplanationBuilder.undergroundNote(found.safe()).ifPresent(notes::add);
        // 따로 그린 인도가 큰 길 양쪽으로 갈리는데 그 구간에 횡단보도가 없다 (진행표 w20). 여러 곳이어도 한 번
        if (safeCross.withoutCrosswalk()) {
            notes.add(RouteResult.Note.warn("crossing", "횡단보도 없이 길을 건너요"));
        }
        // 골목길 기준을 쓰는 때(휠체어)에 골목길이 남으면 알린다 (2026-11-12)
        if (phase.weight(Criterion.ALLEY) > 0 && alleyM(safeAlleys) >= ALLEY_NOTE_MIN_M) {
            notes.add(RouteResult.Note.info("alley", "골목길 약 %dm를 지납니다".formatted(alleyM(safeAlleys))));
        }
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
            List<RouteResult.Stretch> crossings, List<RouteResult.CrossAt> crossAt, List<RouteResult.Stair> stairs,
            List<RouteResult.Event> events, RoadPath path, List<RouteResult.Alley> alleys) {
        return new RouteResult.Route(kind, meters, minutes, line, FacilityGroup.keyed(counts.byGroup()), covered,
                sidewalks,
                dark, turns, crossings, stairs, events, (int) Math.round(path.lengthM(RouteFinder::isUnpaved)),
                (int) Math.round(path.lengthM(RouteFinder::isSurfaceUnknown)), List.of(), crossAt, alleys, alleyM(alleys));
    }

    private static int alleyM(List<RouteResult.Alley> alleys) {
        return alleys.stream().mapToInt(RouteResult.Alley::lengthM).sum();
    }

    /**
     * 이 요청 기준마다 경로가 그 범위 안을 걷는 길이(m). 키는 기준 이름 소문자 (school, help, rest, light, cctv, 비 오는 날 roof).
     * 골목길은 alleyM으로 따로 주므로 넣지 않는다 (휠체어 낮은 그대로 빈 맵)
     */
    static Map<String, Integer> covered(RoadPath path, RouteProperties.Phase phase, Coverage cov) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Criterion c : phase.weights().keySet()) {
            if (c == Criterion.ALLEY) {
                continue;
            }
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

    /**
     * 경로 선이 영향 반경을 지나는 행사·돌발상황. 들어가는 곳 순서.
     * 출발·도착이 반경 안(near)이면 passes와 상관없이 넣는다 (그곳에서 출발하거나 그리로 가는 길)
     */
    private static List<RouteResult.Event> events(List<RoadEvent> events, List<double[]> line,
            java.util.function.Predicate<RoadEvent> avoids, java.util.function.Predicate<RoadEvent> passes) {
        List<RouteResult.Event> out = new ArrayList<>();
        for (RoadEvent e : events) {
            double[] s = RoadEvents.stretch(e, line);
            if (s != null && (passes.test(e) || near(e, line) != null)) {
                out.add(new RouteResult.Event(e.kind(), e.title(), e.place(), e.message(),
                        e.endsAt() == null ? null : e.endsAt().format(ENDS),
                        (int) Math.round(s[0]), (int) Math.round(s[1]), avoids.test(e), near(e, line)));
            }
        }
        out.sort(Comparator.comparingInt(RouteResult.Event::fromM));
        return out;
    }

    /** 경로 선의 첫 점(요청한 출발)·끝 점(도착)이 영향 범위 안인지. RouteService가 피하지 않을 행사를 고르는 기준과 같다 */
    static String near(RoadEvent e, List<double[]> line) {
        boolean start = Geo.distance(e.point(), line.get(0)) <= e.radiusM();
        boolean end = Geo.distance(e.point(), line.get(line.size() - 1)) <= e.radiusM();
        return start && end ? "both" : start ? "start" : end ? "end" : null;
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
