package com.saferoute.route.engine.score;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.springframework.stereotype.Component;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.RouteProperties.Phase;
import com.saferoute.route.engine.facility.Availability;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 모드·낮밤 기준에 맞춰 길마다 더할 비용을 만든다.
 * 추가 비용 = 길이 × Σ 비중 × (범위 밖 비율). 범위 안에 다 들면 0, 다 밖이면 길이 × 비중 합.
 *
 * <p>시설 고르기 (2026-11-02·03 결정)
 * <ul>
 *   <li>도움 시설: 낮은 경찰·편의점·비상벨·아동안전지킴이집. 밤은 지킴이집(가게)과 건물·화장실 안 비상벨을 뺀다
 *       (A파트 밤 도움 지점 정의, migration 016). 그다음 지금 이용 가능한 곳만 남긴다 ({@link Availability})</li>
 *   <li>쉼 곳: 쉼터·공중화장실 중 지금 이용 가능한 곳</li>
 *   <li>조명·CCTV·보호구역: 시간과 상관없이 모두</li>
 * </ul>
 */
@Component
public class RouteScorer {

    static final Set<String> HELP_KINDS = Set.of("police", "bell", "store", "guard_house");
    static final Set<String> REST_KINDS = Set.of("shelter", "toilet");

    private final RouteProperties.CoverageSettings settings;

    public RouteScorer(RouteProperties props) {
        this.settings = props.coverage();
    }

    /** 이 기준에서 길마다 더할 비용. 기준이 없으면(가장 짧은 길) null */
    public ToDoubleFunction<RoadEdge> extraCost(RoadGraph graph, Phase phase, boolean night, Nearby nearby) {
        return extraCost(coverage(graph, phase, night, nearby), phase);
    }

    /** 이미 계산한 커버율로 길마다 더할 비용. 기준이 없으면 null */
    public ToDoubleFunction<RoadEdge> extraCost(Coverage cov, Phase phase) {
        if (!phase.hasWeights()) {
            return null;
        }
        Map<Criterion, Double> w = phase.weights();
        return e -> {
            double missing = 0;
            for (Map.Entry<Criterion, Double> c : w.entrySet()) {
                missing += c.getValue() * cov.missing(e, c.getKey());
            }
            return e.lengthM() * missing;
        };
    }

    /** 이 기준에 쓰는 커버율 */
    public Coverage coverage(RoadGraph graph, Phase phase, boolean night, Nearby nearby) {
        Set<Criterion> wanted = phase.weights().keySet();
        if (wanted.isEmpty() || graph.isEmpty()) {
            return Coverage.NONE;
        }
        List<Poi> all = nearby.pois();
        Map<Criterion, Coverage.Layer> layers = new EnumMap<>(Criterion.class);
        for (Criterion c : wanted) {
            Predicate<Poi> keep = switch (c) {
                case LIGHT -> p -> p.kind().equals("light");
                case CCTV -> p -> p.kind().equals("cctv");
                case SCHOOL -> p -> p.kind().equals("school_zone");
                case HELP -> p -> HELP_KINDS.contains(p.kind()) && (!night || nightHelp(p))
                        && Availability.usable(p, nearby.at(), nearby.holidays());
                case REST -> p -> REST_KINDS.contains(p.kind()) && Availability.usable(p, nearby.at(), nearby.holidays());
            };
            List<double[]> points = all.stream().filter(keep).map(Poi::point).toList();
            boolean alongRoad = c == Criterion.LIGHT || c == Criterion.CCTV;
            layers.put(c, new Coverage.Layer(points, radius(c, phase), alongRoad));
        }
        return Coverage.compute(graph, layers, settings.snapToRoadM(), settings.sampleM());
    }

    /** 밤에도 도움을 청할 수 있는 종류: 지킴이집(가게)과 건물·화장실 안 비상벨은 뺀다 */
    static boolean nightHelp(Poi p) {
        return switch (p.kind()) {
            case "guard_house" -> false;
            case "bell" -> p.indoor() != null && p.indoor() == 0;
            default -> true;
        };
    }

    double radius(Criterion c, Phase phase) {
        return switch (c) {
            case LIGHT -> settings.lightM();
            case CCTV -> settings.cctvM();
            case HELP -> phase.helpM();
            case REST -> phase.restM();
            case SCHOOL -> phase.schoolM();
        };
    }
}
