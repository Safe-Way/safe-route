package com.saferoute.route;

import static com.saferoute.route.TestRoutes.E_P;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.assertNoRoute;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.north;
import static com.saferoute.route.TestRoutes.shortcutOrAround;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 등산로 (2026-11-12): 등산로 없는 길이 있으면 우회 한도 없이 그 길로, allowTrail이면 등산로를 쓴다. 휠체어는 늘 뺀다.
 * 길은 S에서 동쪽 T(약 365m)로 간다.
 */
@DisplayName("등산로 피하기")
class TrailTests {

    static final EdgePenalty TRAIL = new EdgePenalty(Map.of(Penalty.TRAIL, 1.0));

    /** 등산로 지름길 S–T(약 365m)와, 북쪽 400m로 돌아가는 보통 길 S–N–T(약 880m, 2.4배) */
    static RoadGraph trailOrAround() {
        return shortcutOrAround(TRAIL, 400);
    }

    /** trailOrAround에 출발 쪽 등산로 꼬리 S0–S를 붙인 도로망. S0은 S 남쪽 stubM */
    static RoadGraph trailStubAtStart(double stubM) {
        double[] s0 = north(S_P, -stubM);
        return TestRoutes.plus(trailOrAround(), Map.of(4L, s0), edge(4, 4, 1, s0, S_P, TRAIL));
    }

    static RouteService service(RoadGraph g) {
        return TestRoutes.service(g, TestRoutes.pois(List.of()), 12);
    }

    static RouteRequest request(double[] from, String mode, Boolean allowTrail) {
        return new RouteRequest(new Place(from[1], from[0], null), new Place(E_P[1], E_P[0], null), "auto", mode, null, null,
                null, allowTrail);
    }

    @Test
    @DisplayName("등산로 없는 길이 있으면 우회 한도를 넘어도 그 길로 간다")
    void avoidsTrailWithoutDetourLimitByDefault() {
        // route.yml 등산로, 2026-11-12
        RouteResult r = service(trailOrAround()).route(request(S_P, "adult", null));

        // 어른 우회 한도 1.3배를 넘어도 등산로 없는 길로. 최단도 등산로 없는 길 중에서
        assertThat(r.routes()).allSatisfy(rt -> assertThat(rt.distanceM()).isGreaterThan(800));
        assertThat(r.explanation().text()).doesNotContain("등산로");
    }

    @Test
    @DisplayName("allowTrail이 true면 등산로를 피하지 않는다")
    void allowTrailUsesTrail() {
        RouteResult r = service(trailOrAround()).route(request(S_P, "adult", true));

        assertThat(r.routes().get(0).distanceM()).isLessThan(400);
        assertThat(r.explanation().text()).doesNotContain("등산로");
    }

    @Test
    @DisplayName("등산로뿐이면 등산로로 가고 피하는 길이 없다고 알린다")
    void trailOnlyWayIsUsedWithWarning() {
        RoadGraph only = new RoadGraph(Map.of(1L, S_P, 2L, E_P), List.of(edge(1, 1, 2, S_P, E_P, TRAIL)));
        RouteResult r = service(only).route(request(S_P, "adult", null));

        assertThat(r.routes().get(0).distanceM()).isLessThan(400);
        assertThat(r.explanation().notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo("trail");
            assertThat(n.text()).isEqualTo("등산로 약 364m를 지납니다. 등산로를 피하는 길은 없습니다");
        });
    }

    @Test
    @DisplayName("휠체어는 allowTrail이어도 등산로를 빼고 등산로뿐이면 NO_ROUTE다")
    void wheelchairNeverUsesTrailEvenWhenAllowed() {
        // route.yml wheelchair.exclude trail
        RouteResult r = service(trailOrAround()).route(request(S_P, "wheelchair", true));

        assertThat(r.routes()).allSatisfy(rt -> assertThat(rt.distanceM()).isGreaterThan(800));
        RoadGraph only = new RoadGraph(Map.of(1L, S_P, 2L, E_P), List.of(edge(1, 1, 2, S_P, E_P, TRAIL)));
        assertNoRoute(() -> service(only).route(request(S_P, "wheelchair", true)));
    }

    @Test
    @DisplayName("등산로 꼬리에서 출발하면 50m 안 보통 길에 다시 붙이고 알린다")
    void startOnTrailStubSnapsToNearbyRoadInsteadOfOpeningAllTrails() {
        // 등산로 꼬리에 붙으면 등산로 없이는 못 나가므로, 50m 안 보통 길에 다시 붙이고 알린다
        RouteResult r = service(trailStubAtStart(30)).route(request(north(S_P, -30), "adult", null));

        assertThat(r.routes()).allSatisfy(rt -> assertThat(rt.distanceM()).isGreaterThan(800));
        assertThat(r.explanation().notes()).noneSatisfy(n -> assertThat(n.kind()).isEqualTo("trail"));
        assertThat(r.explanation().text()).contains("출발점 근처 약 30m는 등산로 없이 이어진 길이 확인되지 않았습니다");

        // 등산로 없는 길이 50m 넘게 더 멀면(산속) 등산로에 붙여 등산로로 간다
        RouteResult d = service(trailStubAtStart(80)).route(request(north(S_P, -80), "adult", null));
        assertThat(d.explanation().notes()).anySatisfy(n -> assertThat(n.kind()).isEqualTo("trail"));
    }
}
