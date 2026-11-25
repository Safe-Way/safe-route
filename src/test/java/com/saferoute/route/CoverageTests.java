package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.justAbove;
import static com.saferoute.route.TestRoutes.justBelow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.RouteProperties.CoverageSettings;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Coverage.Layer;
import com.saferoute.route.engine.score.Criterion;

/** 커버율: 길 따라(조명·CCTV)와 직선 반경(도움 시설 등) */
@DisplayName("커버율 (길 따라·직선 반경)")
class CoverageTests {

    // 위도 0.0003도 ≈ 33m 떨어진 평행한 두 길 A·B. 서로 이어지지 않는다 (사이에 건물)
    static final double[] A1 = {126.93000, LAT};
    static final double[] A2 = {126.93220, LAT};
    static final double[] B1 = {126.93000, LAT + 0.0003};
    static final double[] B2 = {126.93220, LAT + 0.0003};

    static RoadGraph twoStreets() {
        return new RoadGraph(Map.of(1L, A1, 2L, A2, 3L, B1, 4L, B2), List.of(
                edge(1, 1, 2, A1, A2, EdgePenalty.NONE),
                edge(2, 3, 4, B1, B2, EdgePenalty.NONE)));
    }

    /** Coverage 평면의 위도 1도 길이(m). Coverage.Projection의 110_540과 같다 (private라 숫자로 둔다) */
    static final double FLAT_M_PER_LAT = 110_540;

    static RoadEdge street(RoadGraph g, long id) {
        return g.graph().edgeSet().stream().filter(e -> e.id() == id).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("조명은 길 따라 퍼져 건물 너머 다른 길을 덮지 않고 직선 반경은 덮는다")
    void alongRoadDoesNotReachTheStreetBehindABuilding() {
        // route.yml coverage
        RoadGraph g = twoStreets();
        double[] light = {126.93110, LAT - 0.00004}; // A 가운데에서 약 4m
        Coverage road = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(light), 50, true)), 20, 10);
        Coverage straight = Coverage.compute(g, Map.of(Criterion.HELP, new Layer(List.of(light), 50, false)), 20, 10);

        // 길 따라: A의 가운데 ±50m만, B는 0
        assertThat(road.covered(street(g, 1), Criterion.LIGHT)).isCloseTo(100.0 / 200, within(0.06));
        assertThat(road.covered(street(g, 2), Criterion.LIGHT)).isZero();
        // 직선 반경이면 건물 너머 B도 일부 덮인다 (과대 계산)
        assertThat(straight.covered(street(g, 2), Criterion.HELP)).isGreaterThan(0.2);
    }

    @Test
    @DisplayName("어느 길에서도 멀리 떨어진 등은 길을 비추지 않는다")
    void facilityFarFromAnyRoadIsIgnoredAlongRoad() {
        // snap-to-road-m 밖 (route.yml coverage.snap-to-road-m)
        RoadGraph g = twoStreets();
        double[] farLight = {126.93110, LAT - 0.0003}; // A에서 약 33m (붙이는 한도 20m 밖)
        Coverage road = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(farLight), 30, true)), 20, 10);

        assertThat(road.covered(street(g, 1), Criterion.LIGHT)).isZero();
    }

    @Test
    @DisplayName("길 따라 퍼지는 빛은 교차로를 지나 다음 길들도 반경까지 덮는다")
    void lightSpreadsThroughJunctionToTheNextStreet() {
        // Coverage.alongRoad
        // A–C가 이어진 꺾인 길. C는 모퉁이에서 10m 짧은 길 C1과 나머지 C2로 나뉜다. A 끝(모퉁이 약 4.5m 앞) 등(30m)은
        // C1 전부와 C2 앞 약 15m를 비춘다
        double[] c1 = {126.93220, LAT + 10 / 111_195.0};
        double[] c2 = {126.93220, LAT + 0.0018};
        RoadGraph g = new RoadGraph(Map.of(1L, A1, 2L, A2, 5L, c1, 6L, c2), List.of(
                edge(1, 1, 2, A1, A2, EdgePenalty.NONE),
                edge(3, 2, 5, A2, c1, EdgePenalty.NONE),
                edge(4, 5, 6, c1, c2, EdgePenalty.NONE)));
        double[] corner = {126.93215, LAT};
        Coverage road = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(corner), 30, true)), 20, 10);

        assertThat(road.covered(street(g, 3), Criterion.LIGHT)).isEqualTo(1.0);
        double len = street(g, 4).lengthM();
        assertThat(road.covered(street(g, 4), Criterion.LIGHT)).isCloseTo(15.0 / len, within(10.0 / len));
    }

    @Test
    @DisplayName("빛은 이웃 길에 어느 끝으로 들어가든 그 끝에서 길 따라 잰 반경까지 덮는다")
    void lightReachesARoadThroughItsEndNode() {
        // Coverage.alongRoad
        double[] mid = {126.93110, LAT};
        RoadEdge e = edge(1, 1, 2, A1, mid, EdgePenalty.NONE);
        // F는 동쪽 끝 3에서 가운데 2로 그렸다: 빛은 F의 끝(to) 노드로 들어간다
        RoadEdge f = edge(2, 3, 2, A2, mid, EdgePenalty.NONE);
        RoadGraph g = new RoadGraph(Map.of(1L, A1, 2L, mid, 3L, A2), List.of(e, f));
        // E 위, 가운데 노드 8m 앞 등(30m): F에서 노드 2 쪽 22m 안 표본만 덮인다
        double[] light = {mid[0] - 8 / (111_320 * Math.cos(Math.toRadians(LAT))), LAT};

        Coverage.Sampled onF = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(light), 30, true)), 20, 10)
                .sampled(f, Criterion.LIGHT);
        int n = onF.inside().length;
        for (int i = 0; i < n; i++) {
            assertThat(onF.inside()[i]).as("표본 " + onF.offsetM()[i] + "m").isEqualTo(f.lengthM() - onF.offsetM()[i] + 8 <= 30);
        }
        assertThat(onF.inside()[n - 1]).isTrue();
        assertThat(onF.inside()[0]).isFalse();
    }

    @Test
    @DisplayName("꺾인 길은 조각마다 일정 간격 표본으로 재고 표본 자리는 길 길이 기준이다")
    void samplesFollowABentRoadMeasuredByItsLength() {
        // sample-m 간격, 표본 자리는 from에서 lengthM 기준 거리
        // 시설은 꺾인 길의 둘째 조각에도 붙는다 (Coverage.samples, Coverage.EdgeIndex.nearest)
        double kx = 111_320 * Math.cos(Math.toRadians(LAT));
        double[] p0 = {126.93, LAT};
        double[] p1 = {126.93 + 55 / kx, LAT};
        double[] p2 = {p1[0], LAT + 55 / FLAT_M_PER_LAT};
        // 동쪽 55m → 북쪽 55m. 길 길이는 선 길이(110m)의 두 배로 둔다(나눈 길 조각처럼 선과 다를 수 있다)
        RoadEdge bent = new RoadEdge(1, 1, 2, 220, "footway", List.of(p0, p1, p2), EdgePenalty.NONE);
        RoadGraph g = new RoadGraph(Map.of(1L, p0, 2L, p2), List.of(bent));
        // 둘째 조각 가운데 시설: 선을 따라 82.5m, 길 길이 기준으로는 그 두 배
        double[] onSecond = {p1[0], LAT + 27.5 / FLAT_M_PER_LAT};
        double perLineM = bent.lengthM() / 110;
        double along = 82.5 * perLineM;

        Coverage cov = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(onSecond), 30, true),
                Criterion.HELP, new Layer(List.of(onSecond), 12, false)), 20, 10);

        Coverage.Sampled light = cov.sampled(bent, Criterion.LIGHT);
        double[] at = light.offsetM();
        assertThat(at).hasSize(2 * (int) (55 / 10.0));
        for (int i = 0; i < at.length; i++) {
            assertThat(at[i]).isBetween(0.0, bent.lengthM());
            if (i > 0) {
                assertThat(at[i]).isGreaterThan(at[i - 1]);
            }
            // 길 따라 30m(길 길이 기준)
            assertThat(light.inside()[i]).as("표본 " + at[i] + "m").isEqualTo(Math.abs(at[i] - along) <= 30);
        }
        // 시설 바로 옆 표본(둘째 조각 가운데)은 길 길이 기준 자리에 있다 (선 기준 82.5m가 아니다)
        assertThat(Arrays.stream(at).anyMatch(o -> Math.abs(o - along) < 0.01)).isTrue();
        // 직선 12m: 둘째 조각 표본은 시설과 같은 직선 위라 직선 거리 = 길 거리 ÷ perLineM, 첫 조각은 모두 12m 밖
        long nearHelp = Arrays.stream(at).filter(o -> Math.abs(o - along) / perLineM <= 12).count();
        assertThat(nearHelp).isPositive();
        assertThat(cov.covered(bent, Criterion.HELP)).isCloseTo((double) nearHelp / at.length, within(1e-9));
    }

    @Test
    @DisplayName("등·CCTV는 정한 거리 안의 길에만 붙는다")
    void lightIsJoinedToARoadWithin20m() {
        // coverage.snap-to-road-m (route.yml coverage, Coverage.EdgeIndex.nearest)
        // 커버율은 평면으로 재므로 같은 변환(위도 1도 110,540m, Coverage.Projection과 같은 값)으로 두고 경계 ±0.01m로 본다
        double snap = TestRoutes.props().coverage().snapToRoadM();
        RoadGraph g = twoStreets();
        Coverage in = Coverage.compute(g, Map.of(Criterion.LIGHT,
                new Layer(List.of(new double[] {126.93110, LAT - justBelow(snap) / FLAT_M_PER_LAT}), 30, true)), snap, 10);
        Coverage out = Coverage.compute(g, Map.of(Criterion.LIGHT,
                new Layer(List.of(new double[] {126.93110, LAT - justAbove(snap) / FLAT_M_PER_LAT}), 30, true)), snap, 10);

        assertThat(in.covered(street(g, 1), Criterion.LIGHT)).isPositive();
        assertThat(out.covered(street(g, 1), Criterion.LIGHT)).isZero();
    }

    @Test
    @DisplayName("붙인 곳에서 길 따라 등·CCTV 반경 이하인 표본만 덮인다")
    void lightAndCctvReachAlongTheRoadUpToTheirRange() {
        // coverage.light-m·cctv-m (route.yml coverage, Coverage.alongRoad)
        // 좌표로 둔 시설이라 경계값 자체는 못 만들어 경계 ±0.01m로 본다. 시설은 길 위에 둬 붙이는 거리가 0이다
        CoverageSettings cs = TestRoutes.props().coverage();
        RoadEdge a = edge(1, 1, 2, A1, A2, EdgePenalty.NONE);
        RoadGraph g = new RoadGraph(Map.of(1L, A1, 2L, A2), List.of(a));
        // 표본 자리는 Coverage가 정한 것을 읽는다. 마지막 표본에서 from 쪽으로 반경만큼 떨어진 자리에 시설을 둔다 (sample-m·반경이 바뀌어도 길 안에 남게)
        double[] offsets = samplesAlong(g, a, List.of(), 1, cs).offsetM();
        int k = offsets.length - 1;
        for (double r : new double[] {cs.lightM(), cs.cctvM()}) {
            assertThat(samplesAlong(g, a, List.of(onA(offsets[k] - justBelow(r), a)), r, cs).inside()[k]).as(r + "m 안").isTrue();
            assertThat(samplesAlong(g, a, List.of(onA(offsets[k] - justAbove(r), a)), r, cs).inside()[k]).as(r + "m 밖").isFalse();
        }
    }

    /** 길 따라 반경 radiusM인 시설들로 잰 길 a의 표본별 결과 (표본 결과는 조명 기준에만 남아 LIGHT로 잰다) */
    static Coverage.Sampled samplesAlong(RoadGraph g, RoadEdge a, List<double[]> points, double radiusM, CoverageSettings cs) {
        return Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(points, radiusM, true)), cs.snapToRoadM(), cs.sampleM())
                .sampled(a, Criterion.LIGHT);
    }

    /** 동서로 곧은 길 A 위, from에서 길 길이 기준 atM 자리 */
    static double[] onA(double atM, RoadEdge a) {
        return new double[] {A1[0] + (A2[0] - A1[0]) * atM / a.lengthM(), LAT};
    }

    @Test
    @DisplayName("지하 길은 모든 기준 안으로, 지붕은 길 성질로 보고 지하 선은 지상 등을 안 가져간다")
    void coverageTreatsUndergroundAsInsideAndRoofAsAProperty() {
        EdgePenalty roof = new EdgePenalty(Map.of(Penalty.COVERED, 1.0));
        double[] a = {126.93000, LAT};
        double[] b = {126.93100, LAT};
        double[] c = {126.93000, LAT + 0.00015};
        double[] d = {126.93100, LAT + 0.00015};
        RoadEdge roofed = edge(1, 1, 2, a, b, roof);
        RoadEdge hall = edge(2, 3, 4, c, d, TestRoutes.UNDER);
        RoadGraph g = new RoadGraph(Map.of(1L, a, 2L, b, 3L, c, 4L, d), List.of(roofed, hall));
        Map<Criterion, Layer> noLights = Map.of(Criterion.LIGHT, new Layer(List.of(), 30, true));

        Coverage cov = Coverage.compute(g, noLights, 20, 10);
        assertThat(cov.covered(hall, Criterion.LIGHT)).isEqualTo(1);
        assertThat(cov.covered(hall, Criterion.ROOF)).isEqualTo(1);
        boolean[] inside = cov.sampled(hall, Criterion.LIGHT).inside();
        assertThat(inside).isNotEmpty();
        assertThat(inside).containsOnly(true);
        assertThat(cov.covered(roofed, Criterion.ROOF)).isEqualTo(1);
        assertThat(cov.covered(roofed, Criterion.LIGHT)).isZero();

        // 지하 길 바로 위(3m)의 등도 지상 길(13m)에 붙는다. 지하 선이 지상의 등을 가져가지 않는다
        double[] light = {126.93050, LAT + 0.00012};
        Coverage lit = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(light), 30, true)), 20, 10);
        assertThat(lit.covered(roofed, Criterion.LIGHT)).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("범위 밖 비율은 1 − 커버율이다")
    void missingIsOneMinusCovered() {
        RoadGraph g = twoStreets();
        Coverage none = Coverage.compute(g, Map.of(Criterion.REST, new Layer(List.of(), 120, false)), 20, 10);
        assertThat(none.missing(street(g, 1), Criterion.REST)).isEqualTo(1.0);
    }
}
