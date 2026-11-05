package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteResult.Sidewalk;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.search.RoadPath;

class SidewalksTests {

    private static final double[] A = {126.930, 35.140};
    private static final double[] B = {126.931, 35.140};
    private static final double[] C = {126.932, 35.140};
    private static final double[] D = {126.932, 35.141};
    private static final double[] E = {126.933, 35.141};

    @Test
    void sameSideJoinsAndWalkwaysAreLeftOut() {
        // A→B→C 차도(오른쪽 인도), C→D 인도, D→E 태그 없는 차도
        RoadPath path = path(List.of(
                edge(1, 1, 2, "secondary", List.of(A, B), "right"),
                edge(2, 2, 3, "secondary", List.of(B, C), "right"),
                edge(3, 3, 4, "footway", List.of(C, D), null),
                edge(4, 4, 5, "residential", List.of(D, E), null)),
                List.of(A, B, C, D, E));

        assertThat(Sidewalks.along(path, 0)).containsExactly(
                new Sidewalk(0, 2, "right"), new Sidewalk(3, 4, "unknown"));
        // 출발 점을 앞에 붙이면 번호가 하나씩 밀린다
        assertThat(Sidewalks.along(path, 1).get(0)).isEqualTo(new Sidewalk(1, 3, "right"));
    }

    @Test
    void walkingAgainstDrawnDirectionSwapsSide() {
        // 길은 B→A로 그렸고(인도 left) 걷는 건 A→B: 걷는 사람에겐 오른쪽
        RoadPath path = path(List.of(edge(1, 2, 1, "tertiary", List.of(B, A), "left")), List.of(A, B));

        assertThat(Sidewalks.along(path, 0)).containsExactly(new Sidewalk(0, 1, "right"));
        assertThat(Sidewalks.side("both", false)).isEqualTo("both");
        assertThat(Sidewalks.side("no", false)).isEqualTo("no");
    }

    private static RoadPath path(List<RoadEdge> edges, List<double[]> line) {
        return new RoadPath(edges, line, 0);
    }

    private static RoadEdge edge(long id, long from, long to, String highway, List<double[]> geometry,
            String sidewalk) {
        return new RoadEdge(id, from, to, 100, highway, geometry, EdgePenalty.NONE, sidewalk);
    }
}
