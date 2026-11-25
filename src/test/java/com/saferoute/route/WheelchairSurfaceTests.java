package com.saferoute.route;

import static com.saferoute.route.TestRoutes.E_P;
import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.north;
import static com.saferoute.route.TestRoutes.safe;
import static com.saferoute.route.TestRoutes.shortcutOrAround;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 휠체어 바닥: 비포장 조건부 제외와 알림 (2026-11-04 결정), 좁은 인도 조건부 제외와 알림 (2026-11-13), 재질 모르는 산길 알림.
 * 길은 S에서 동쪽 T(약 365m)로 간다.
 */
@DisplayName("휠체어 바닥 (비포장·좁은 인도)")
class WheelchairSurfaceTests {

    static final EdgePenalty UNPAVED = new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0));

    static RouteResult route(RoadGraph g, String mode) {
        return route(g, mode, 12, "auto");
    }

    static RouteResult route(RoadGraph g, String mode, int hour, String night) {
        return TestRoutes.service(g, TestRoutes.pois(List.of()), hour).route(
                new RouteRequest(new Place(S_P[1], S_P[0], null), new Place(E_P[1], E_P[0], null), night, mode));
    }

    static RoadGraph oneEdge(EdgePenalty p) {
        return new RoadGraph(Map.of(1L, S_P, 2L, E_P), List.of(edge(1, 1, 2, S_P, E_P, p)));
    }

    @Test
    @DisplayName("피할 수 없는 비포장은 주의를 붙이고 가장 안전한 길이라 하지 않는다")
    void unavoidableUnpavedIsWarnedAndNotCalledSafest() {
        // 밤 휠체어, 피할 수 없는 비포장 하나뿐: 주의가 붙으면 "가장 안전한 길"이라고 하지 않는다
        RouteResult r = route(oneEdge(UNPAVED), "wheelchair", 21, "on");

        assertThat(r.explanation().notes()).anyMatch(n -> n.kind().equals("unpaved") && n.level().equals("warn"));
        assertThat(r.explanation().text()).contains("비포장길 약 364m를 지납니다").contains("비포장을 피하는 길은 없습니다");
        assertThat(r.explanation().summary()).isEqualTo("가장 짧은 길로 갑니다");
        assertThat(r.explanation().text()).startsWith("가장 짧은 길로 갑니다 (주의: 비포장길");
    }

    @Test
    @DisplayName("휠체어는 돌아가는 길이 1.2배 안이면 비포장을 피한다")
    void wheelchairAvoidsUnpavedWhenWayAroundIsWithinLimit() {
        // 돌아가는 길 약 1.1배 ≤ 1.2 → 포장길, 알림 없음
        RouteResult r = route(shortcutOrAround(UNPAVED, 80), "wheelchair");

        // 최단(비포장)과 안전(포장 우회) 두 경로를 주고, 안전 경로는 비포장을 지나지 않는다
        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        assertThat(r.routes().get(1).distanceM()).isGreaterThan(390);
        assertThat(r.explanation().text()).isEqualTo("33m(1분) 더 돌지만 비포장을 피하는 길로 갑니다");
    }

    @Test
    @DisplayName("돌아가는 길이 너무 길면 비포장으로 가고 피하면 얼마나 더 걸리는지 알린다")
    void wheelchairTakesUnpavedWhenWayAroundIsTooLongAndSaysHowMuchLonger() {
        // 돌아가는 길 약 1.9배 > 1.2 → 비포장 지름길, 피하면 얼마나 더 걸리는지 알림
        RouteResult r = route(shortcutOrAround(UNPAVED, 300), "wheelchair");

        assertThat(r.routes()).hasSize(1);
        assertThat(r.routes().get(0).distanceM()).isBetween(360, 370);
        assertThat(r.explanation().text()).contains("비포장길 약 364m를 지납니다").containsPattern("비포장을 피하면 \\d+m\\(\\d+분\\) 더 걸립니다");
    }

    @Test
    @DisplayName("비포장 피하기는 휠체어만 하고 어른은 비포장 지름길로 간다")
    void otherModesIgnoreUnpaved() {
        // route.yml wheelchair.avoid-unpaved
        RouteResult r = route(shortcutOrAround(UNPAVED, 80), "adult");

        assertThat(r.routes().get(0).distanceM()).isBetween(360, 370);
        assertThat(r.explanation().text()).doesNotContain("비포장");
    }

    @Test
    @DisplayName("재질 모르는 산길은 피하지 않고 지나며 확인 안 됨으로 알린다")
    void unknownSurfaceIsPassedWithANote() {
        // 재질·난이도 태그가 없는 산길은 포장으로 보고 지나되 "확인 안 됨"으로 알린다. 1.1배 돌아가는 길이 있어도 피하지 않는다
        RouteResult r = route(shortcutOrAround(new EdgePenalty(Map.of(Penalty.SURFACE_UNKNOWN, 1.0)), 80), "wheelchair");

        assertThat(r.routes()).singleElement().satisfies(rt -> assertThat(rt.distanceM()).isBetween(360, 370));
        assertThat(r.explanation().notes())
                .contains(RouteResult.Note.unknown("surface", "바닥 재질을 모르는 산길 약 364m를 지납니다"));
    }

    /** 폭 1.0m 좁은 인도(보도 자료)가 양쪽에 있는 골목급 차도 a–b */
    static RoadEdge narrow(long id, long from, long to, double[] a, double[] b) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), "residential", line,
                new EdgePenalty(Map.of(Penalty.NARROW_SIDEWALK, 1.0)), "both", null, null, null, null, 1.0);
    }

    /** 좁은 인도 차도 S–T와, 북쪽 northM으로 돌아가는 보통 길 S–N–T */
    static RoadGraph narrowOrAround(double northM) {
        double[] n = north(new double[] {126.932, LAT}, northM);
        return new RoadGraph(Map.of(1L, S_P, 2L, E_P, 3L, n), List.of(narrow(1, 1, 2, S_P, E_P),
                edge(2, 1, 3, S_P, n, EdgePenalty.NONE), edge(3, 3, 2, n, E_P, EdgePenalty.NONE)));
    }

    @Test
    @DisplayName("휠체어는 돌아가는 길이 1.2배 안이면 좁은 인도를 피한다")
    void wheelchairAvoidsNarrowSidewalkWithinLimit() {
        // 돌아가는 길 약 1.1배 ≤ 1.2 → 좁은 인도를 뺀 길, 주의 없음. 최단 경로 응답에는 좁은 인도 구간이 그대로 온다
        RouteResult r = route(narrowOrAround(80), "wheelchair");

        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        assertThat(r.routes().get(1).narrows()).isEmpty();
        assertThat(r.routes().get(0).narrowM()).isBetween(360, 370);
        assertThat(r.explanation().text()).isEqualTo("33m(1분) 더 돌지만 좁은 인도를 피하는 길로 갑니다");
    }

    @Test
    @DisplayName("돌아가는 길이 너무 길면 좁은 인도로 가고 알린다")
    void wheelchairGoesThroughNarrowSidewalkWithNoteWhenWayAroundIsTooLong() {
        // 돌아가는 길 약 1.9배 > 1.2 → 좁은 인도로 가고 알린다
        RouteResult r = route(narrowOrAround(300), "wheelchair");

        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo("narrow");
            assertThat(n.text()).isEqualTo("폭 1.5m 안 되는 좁은 인도 약 364m를 지납니다");
        });
    }

    @Test
    @DisplayName("비포장을 못 피해도 좁은 인도는 따로 피한다")
    void wheelchairAvoidsNarrowEvenWhenUnpavedCannotBeAvoided() {
        // S–M 비포장 100m(피하면 북쪽 400m로 +700m), M–T 좁은 인도 100m(피하면 북쪽 40m로 +28m).
        // 둘을 따로 피한다: 좁은 인도는 1.2배 안이라 피하고, 비포장은 못 피해 알린다. "비포장을 피하면"은 비포장만 피한 길로 잰다
        double[] m = {126.9311, LAT};
        double[] t = {126.9322, LAT};
        double[] u = north(new double[] {126.93055, LAT}, 400);
        double[] n = north(new double[] {126.93165, LAT}, 40);
        RoadGraph g = new RoadGraph(Map.of(1L, S_P, 2L, m, 3L, t, 4L, u, 5L, n), List.of(
                edge(1, 1, 2, S_P, m, UNPAVED), narrow(2, 2, 3, m, t),
                edge(3, 1, 4, S_P, u, EdgePenalty.NONE), edge(4, 4, 2, u, m, EdgePenalty.NONE),
                edge(5, 2, 5, m, n, EdgePenalty.NONE), edge(6, 5, 3, n, t, EdgePenalty.NONE)));

        RouteResult r = TestRoutes.service(g, TestRoutes.pois(List.of()), 12).route(new RouteRequest(
                new Place(LAT, S_P[0], null), new Place(LAT, t[0], null), "auto", "wheelchair"));

        assertThat(safe(r).narrows()).isEmpty();
        assertThat(safe(r).unpavedM()).isBetween(95, 105);
        assertThat(r.explanation().summary()).contains("좁은 인도를 피하는 길로 갑니다");
        // 비포장만 피한 길(S–U–M–T 약 906m) − 안내 경로(약 228m) ≈ 678m. 좁은 인도까지 피하는 길(약 934m)로 재면 706m
        assertThat(r.explanation().text()).contains("비포장길 약 100m를 지납니다").containsPattern("비포장을 피하면 6[67]\\dm");
        assertThat(r.explanation().notes()).noneMatch(x -> x.kind().equals("narrow"));
    }

    @Test
    @DisplayName("좁은 인도 구간과 인도 폭은 모든 모드 응답에 담긴다")
    void narrowsAndSidewalkWidthAreInEveryModesResult() {
        // 응답 칸 (앱 "좁은 인도 ○m"): 피하지 않는 어른도 받는다. 인도 폭은 sidewalks에, 차도 폭 widthM과 따로
        RouteResult adult = route(narrowOrAround(80), "adult");
        RouteResult.Route rt = adult.routes().get(0);

        assertThat(rt.narrows()).singleElement().satisfies(n -> {
            assertThat(n.from()).isZero();
            assertThat(n.to()).isEqualTo(1);
            assertThat(n.lengthM()).isBetween(360, 370);
            assertThat(n.widthM()).isEqualTo(1.0);
        });
        assertThat(rt.narrowM()).isEqualTo(rt.narrows().get(0).lengthM());
        assertThat(rt.sidewalks()).singleElement().satisfies(s -> {
            assertThat(s.sidewalkWidthM()).isEqualTo(1.0);
            assertThat(s.widthM()).isNull();
        });
        assertThat(adult.explanation().notes()).noneMatch(n -> n.kind().equals("narrow"));
    }
}
