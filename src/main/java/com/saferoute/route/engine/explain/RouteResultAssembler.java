package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 찾은 두 경로로 응답(요구사항 8.3)을 만든다: 거리·시간, 지나는 시설 수, 인도 쪽, 차이 설명, 지도 아이콘, 데이터 상태.
 */
@Component
public class RouteResultAssembler {

    private static final String NO_DATA_MESSAGE = "이 지역은 안전 데이터가 부족합니다";

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
    public RouteResult assemble(Conditions cond, RouteProperties.Profile profile, RouteProperties.Phase phase,
            RouteFinder.Found found, double[] start, double[] end, double snapM, Nearby nearby) {
        boolean same = found.safe().sameAs(found.shortest());
        List<double[]> shortLine = found.shortest().lineFrom(start, end);
        List<double[]> safeLine = found.safe().lineFrom(start, end);
        int shortM = (int) Math.round(found.shortest().lengthM() + snapM);
        int safeM = (int) Math.round(found.safe().lengthM() + snapM);

        List<Counts> counts = counter.countAlong(List.of(shortLine, safeLine), nearby.pois());
        Counts shortCounts = counts.get(0);
        Counts safeCounts = counts.get(1);

        List<RouteResult.Route> routes = new ArrayList<>();
        if (!same) {
            routes.add(route("shortest", shortM, profile.minutes(shortM), shortLine, shortCounts,
                    Sidewalks.along(found.shortest(), found.shortest().startOffset(start))));
        }
        routes.add(route("safe", safeM, profile.minutes(safeM), safeLine, safeCounts,
                Sidewalks.along(found.safe(), found.safe().startOffset(start))));

        int extraM = same ? 0 : safeM - shortM;
        // 반올림을 따로 하면 "22분 / 24분"인데 "(1분) 더"가 나올 수 있어, 두 경로 시간의 차로 구한다
        int extraMin = same ? 0 : profile.minutes(safeM) - profile.minutes(shortM);
        Map<FacilityGroup, Integer> diff = safeCounts.minus(shortCounts);
        String text = ExplanationBuilder.text(cond, profile, phase, same, found.detourCapped(), extraM, extraMin, diff);

        return new RouteResult(
                RouteResult.Conditions.of(cond),
                routes,
                new RouteResult.Explanation(text, extraM, extraMin, FacilityGroup.keyed(diff)),
                marks(same ? null : shortCounts, safeCounts),
                dataQuality(same ? List.of(found.safe()) : List.of(found.shortest(), found.safe()), nearby));
    }

    private RouteResult.Route route(String kind, int meters, int minutes, List<double[]> line, Counts counts,
            List<RouteResult.Sidewalk> sidewalks) {
        return new RouteResult.Route(kind, meters, minutes, line, FacilityGroup.keyed(counts.byGroup()), sidewalks);
    }

    /** 지도 아이콘. 도움 지점·비상벨·CCTV·등 순서로 최대 개수까지. shortCounts가 null이면 경로가 하나 */
    private List<RouteResult.FacilityMark> marks(Counts shortCounts, Counts safeCounts) {
        Set<Poi> onShort = shortCounts == null ? Set.of() : new HashSet<>(shortCounts.passed());
        Set<Poi> onSafe = new HashSet<>(safeCounts.passed());
        Set<Poi> all = new HashSet<>(onShort);
        all.addAll(onSafe);
        return all.stream()
                .sorted(Comparator.comparingInt((Poi p) -> markOrder(p.kind())).thenComparingLong(Poi::id))
                .limit(props.maxFacilities())
                .map(p -> new RouteResult.FacilityMark(type(p), p.lat(), p.lon(),
                        onShort.contains(p) && onSafe.contains(p) ? "both" : onSafe.contains(p) ? "safe" : "shortest"))
                .toList();
    }

    /** 지도 아이콘이 많을 때 먼저 남기는 순서. 등은 너무 많아서 맨 뒤 */
    static int markOrder(String kind) {
        return switch (kind) {
            case "police", "store", "guard_house" -> 0;
            case "bell" -> 1;
            case "cctv" -> 2;
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

    /** 데이터가 빈 지역을 지나는 길이 비율로 상태를 정한다. 빈 곳을 안전하다고 하지 않는다 (NFR-04) */
    private RouteResult.DataQuality dataQuality(List<RoadPath> paths, Nearby nearby) {
        Set<RoadEdge> edges = new HashSet<>();
        paths.forEach(p -> edges.addAll(p.edges()));
        double total = 0;
        double gap = 0;
        for (RoadEdge e : edges) {
            total += e.lengthM();
            if (e.penalty().dataGap()) {
                gap += e.lengthM();
            }
        }
        String status = gap == 0 ? "OK" : gap >= total ? "NO_DATA" : "PARTIAL";
        return new RouteResult.DataQuality(status, gap == 0 ? null : NO_DATA_MESSAGE,
                nearby.dataDate() == null ? null : nearby.dataDate().toString());
    }
}
