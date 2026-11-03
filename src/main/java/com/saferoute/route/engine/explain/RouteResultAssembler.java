package com.saferoute.route.engine.explain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.FacilitySource;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 찾은 두 경로로 응답(요구사항 8.3)을 만든다: 거리·시간, 지나는 시설 수, 차이 설명, 지도 아이콘, 데이터 상태.
 */
@Component
public class RouteResultAssembler {

    private static final String NO_DATA_MESSAGE = "이 지역은 안전 데이터가 부족합니다";

    private final FacilityCounter counter;
    private final FacilitySource facilities;
    private final RouteProperties props;

    public RouteResultAssembler(FacilityCounter counter, FacilitySource facilities, RouteProperties props) {
        this.counter = counter;
        this.facilities = facilities;
        this.props = props;
    }

    /**
     * @param profile    누가 걷는지 (걸리는 시간을 이 사람의 보행 속도로 계산)
     * @param phase      지금 쓰는 기준 (낮 또는 밤). 설명 문장에 쓴다
     * @param start, end 요청한 출발·도착 {경도, 위도}
     * @param snapM      출발·도착에서 가장 가까운 길 노드까지 거리의 합
     */
    public RouteResult assemble(Conditions cond, RouteProperties.Profile profile, RouteProperties.Phase phase,
            RouteFinder.Found found, double[] start, double[] end, double snapM) {
        boolean same = found.safe().sameAs(found.shortest());
        List<double[]> shortLine = found.shortest().lineFrom(start, end);
        List<double[]> safeLine = found.safe().lineFrom(start, end);
        int shortM = (int) Math.round(found.shortest().lengthM() + snapM);
        int safeM = (int) Math.round(found.safe().lengthM() + snapM);

        List<Counts> counts = counter.countAlong(List.of(shortLine, safeLine));
        Counts shortCounts = counts.get(0);
        Counts safeCounts = counts.get(1);

        List<RouteResult.Route> routes = new ArrayList<>();
        if (!same) {
            routes.add(route("shortest", shortM, profile.minutes(shortM), shortLine, shortCounts));
        }
        routes.add(route("safe", safeM, profile.minutes(safeM), safeLine, safeCounts));

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
                dataQuality(same ? List.of(found.safe()) : List.of(found.shortest(), found.safe())));
    }

    private RouteResult.Route route(String kind, int meters, int minutes, List<double[]> line, Counts counts) {
        return new RouteResult.Route(kind, meters, minutes, line, FacilityGroup.keyed(counts.byGroup()));
    }

    /** 지도 아이콘. 도움 지점·비상벨·CCTV·등 순서로 최대 개수까지. shortCounts가 null이면 경로가 하나 */
    private List<RouteResult.FacilityMark> marks(Counts shortCounts, Counts safeCounts) {
        Set<Facility> onShort = shortCounts == null ? Set.of() : new HashSet<>(shortCounts.passed());
        Set<Facility> onSafe = new HashSet<>(safeCounts.passed());
        Set<Facility> all = new HashSet<>(onShort);
        all.addAll(onSafe);
        return all.stream()
                .sorted(Comparator.comparing((Facility f) -> FacilityGroup.of(f.type()).markOrder())
                        .thenComparing(Facility::id))
                .limit(props.maxFacilities())
                .map(f -> new RouteResult.FacilityMark(f.type().name(), f.lat(), f.lon(),
                        onShort.contains(f) && onSafe.contains(f) ? "both" : onSafe.contains(f) ? "safe" : "shortest"))
                .toList();
    }

    /** 데이터가 빈 지역을 지나는 길이 비율로 상태를 정한다. 빈 곳을 안전하다고 하지 않는다 (NFR-04) */
    private RouteResult.DataQuality dataQuality(List<RoadPath> paths) {
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
        LocalDate date = facilities.dataDate();
        return new RouteResult.DataQuality(status, gap == 0 ? null : NO_DATA_MESSAGE,
                date == null ? null : date.toString());
    }
}
