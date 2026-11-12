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
 * 비 오는 날 지붕 기준은 더하지 않고 곱한다: 지붕 없는 길은 (길이 + 추가 비용)을 (1 + walk-more)배로 (2026-11-09).
 *
 * <p>시설 고르기 (2026-11-02·03 결정)
 * <ul>
 *   <li>도움 시설: 낮은 경찰·편의점·비상벨·아동안전지킴이집. 밤은 지킴이집(가게)과 건물·화장실 안 비상벨을 뺀다
 *       (A파트 밤 도움 지점 정의, migration 016). 그다음 지금 이용 가능한 곳만 남긴다 ({@link Availability})</li>
 *   <li>쉼 곳: 쉼터·공중화장실 중 지금 이용 가능한 곳</li>
 *   <li>조명·CCTV·보호구역: 시간과 상관없이 모두</li>
 *   <li>지붕(비 오는 날, 2026-11-09): 시설이 아니라 길 성질(road_edge.covered)이라 시설 층을 만들지 않는다</li>
 *   <li>골목길(휠체어, 2026-11-12): 길 성질(길 종류·인도 태그)이라 시설 층을 만들지 않는다. 골목이면 범위 밖 비율 1</li>
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

    /** 커버율로 길마다 더할 비용. 기준이 없으면(가장 짧은 길) null */
    public ToDoubleFunction<RoadEdge> extraCost(Coverage cov, Phase phase) {
        if (!phase.hasWeights()) {
            return null;
        }
        Map<Criterion, Double> w = phase.weights();
        double roof = phase.weight(Criterion.ROOF);
        return e -> {
            double missing = 0;
            for (Map.Entry<Criterion, Double> c : w.entrySet()) {
                if (c.getKey() != Criterion.ROOF) {
                    missing += c.getValue() * cov.missing(e, c.getKey());
                }
            }
            double base = e.lengthM() * missing;
            // 비 (2026-11-09): 지붕 없는 길은 부담 전체(거리 + 기준 부담)를 (1 + roof)배로 본다. λ=1에서 (길이 + base)(1 + roof).
            // 지상 길끼리는 같은 배수라 순위가 맑은 날과 같고(더하기로 넣었을 때는 거리 벌점처럼 작동해 쉼 곳·밝기 기준이 묽어졌다),
            // 지붕 아래 길만 상대적으로 가벼워진다
            return base + roof * cov.missing(e, Criterion.ROOF) * (e.lengthM() + base);
        };
    }

    /** 이 기준에 쓰는 커버율 */
    public Coverage coverage(RoadGraph graph, Phase phase, boolean night, Nearby nearby) {
        if (!phase.hasWeights() || graph.isEmpty()) {
            return Coverage.NONE;
        }
        List<Poi> all = nearby.pois();
        Map<Criterion, Coverage.Layer> layers = new EnumMap<>(Criterion.class);
        for (Criterion c : phase.weights().keySet()) {
            Predicate<Poi> keep = switch (c) {
                case LIGHT -> p -> p.kind().equals("light");
                case CCTV -> p -> p.kind().equals("cctv");
                case SCHOOL -> p -> p.kind().equals("school_zone");
                case HELP -> p -> HELP_KINDS.contains(p.kind()) && usableNow(p, night, nearby);
                case REST -> p -> REST_KINDS.contains(p.kind()) && usableNow(p, night, nearby);
                case ROOF, ALLEY -> null; // 시설이 아니라 길 성질(지붕·골목). Coverage.compute가 길마다 정한다
            };
            if (keep == null) {
                continue;
            }
            List<double[]> points = all.stream().filter(keep).map(Poi::point).toList();
            boolean alongRoad = c == Criterion.LIGHT || c == Criterion.CCTV;
            layers.put(c, new Coverage.Layer(points, radius(c, phase), alongRoad));
        }
        return Coverage.compute(graph, layers, settings.snapToRoadM(), settings.sampleM());
    }

    /**
     * 지금 쓸 수 있는 시설인지. 도움 시설은 밤이면 {@link #nightHelp}도 보고, 도움 시설·쉼 곳은 운영 시간을 본다.
     * 등·CCTV·보호구역은 늘 참. 경로 고르기와 응답의 시설 수(counts)가 같은 기준을 쓴다
     */
    public static boolean usableNow(Poi p, boolean night, Nearby nearby) {
        if (HELP_KINDS.contains(p.kind())) {
            return (!night || nightHelp(p)) && Availability.usable(p, nearby.at(), nearby.holidays());
        }
        return !REST_KINDS.contains(p.kind()) || Availability.usable(p, nearby.at(), nearby.holidays());
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
            case ROOF, ALLEY -> 0;
        };
    }
}
