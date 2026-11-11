package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                new Sidewalk(0, 2, "right", "secondary", null, null, null, 8.0),
                new Sidewalk(3, 4, "unknown", "residential", null, null, null, 4.8));
        // 출발 점을 앞에 붙이면 번호가 하나씩 밀린다
        assertThat(Sidewalks.along(path, 1).get(0).from()).isEqualTo(1);
        assertThat(Sidewalks.along(path, 1).get(0).to()).isEqualTo(3);
    }

    @Test
    void walkingAgainstDrawnDirectionSwapsSide() {
        // 길은 B→A로 그렸고(인도 left) 걷는 건 A→B: 걷는 사람에겐 오른쪽
        RoadPath path = path(List.of(edge(1, 2, 1, "tertiary", List.of(B, A), "left")), List.of(A, B));

        assertThat(Sidewalks.along(path, 0)).extracting(Sidewalk::side).containsExactly("right");
        assertThat(Sidewalks.side("both", false)).isEqualTo("both");
        assertThat(Sidewalks.side("no", false)).isEqualTo("no");
    }

    @Test
    void onewayWithoutTagHasSidewalkOnRightOfTraffic() {
        // A→B로 그린 일방통행(차도 A→B): 인도는 그린 방향 오른쪽. A→B로 걸으면 오른쪽, B→A로 걸으면 왼쪽
        RoadEdge ab = edge(1, 1, 2, "primary", List.of(A, B), null, null, 1, null);
        assertThat(Sidewalks.along(path(List.of(ab), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("right");
        assertThat(Sidewalks.along(path(List.of(ab), List.of(B, A)), 0)).extracting(Sidewalk::side)
                .containsExactly("left");
        // oneway=-1: 차가 B→A로 가니 그린 방향 기준 왼쪽, A→B로 걸으면 왼쪽
        RoadEdge back = edge(2, 1, 2, "primary", List.of(A, B), null, null, -1, null);
        assertThat(Sidewalks.along(path(List.of(back), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("left");
        // 태그가 있으면 태그가 먼저
        RoadEdge tagged = edge(3, 1, 2, "primary", List.of(A, B), "both", null, 1, null);
        assertThat(Sidewalks.along(path(List.of(tagged), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("both");
        // oneway=no·태그 없음은 일방 규칙을 쓰지 않고, 태그 없는 양방향 큰 길은 양쪽(w15). 응답 oneway는 false·null
        RoadEdge two = edge(4, 1, 2, "primary", List.of(A, B), null, null, 0, null);
        RoadEdge unknown = edge(5, 1, 2, "primary", List.of(A, B), null, null, null, null);
        assertThat(Sidewalks.along(path(List.of(two), List.of(A, B)), 0)).extracting(Sidewalk::side, Sidewalk::oneway)
                .containsExactly(tuple("both", false));
        assertThat(Sidewalks.along(path(List.of(unknown), List.of(A, B)), 0)).extracting(Sidewalk::side, Sidewalk::oneway)
                .containsExactly(tuple("both", null));
        // 큰 길이라도 인도 없음 태그는 그대로, 작은 길·진출입로는 모름 그대로
        RoadEdge none = edge(7, 1, 2, "secondary", List.of(A, B), "no", null, 0, null);
        RoadEdge small = edge(8, 1, 2, "residential", List.of(A, B), null, null, 0, null);
        RoadEdge link = edge(9, 1, 2, "primary_link", List.of(A, B), null, null, null, null);
        assertThat(Sidewalks.along(path(List.of(none), List.of(A, B)), 0)).extracting(Sidewalk::side).containsExactly("no");
        assertThat(Sidewalks.along(path(List.of(small), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("unknown");
        assertThat(Sidewalks.along(path(List.of(link), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("unknown");
        // 요청 예: 3차로 일방 → 3 × 3.25 / 2 + 1.5 = 6.375 → 6.4
        RoadEdge three = edge(6, 1, 2, "primary", List.of(A, B), null, 3, 1, null);
        assertThat(Sidewalks.along(path(List.of(three), List.of(A, B)), 0))
                .containsExactly(new Sidewalk(0, 1, "right", "primary", 3, true, null, 6.4));
    }

    @Test
    void offsetFromWidthThenLanesThenDefault() {
        // 폭이 있으면 폭/2 + 1.5
        assertThat(Sidewalks.offsetM("primary", 4, false, 7.0)).isEqualTo(5.0);
        // 차로 수: 4 × 3.25 / 2 + 1.5 = 8.0
        assertThat(Sidewalks.offsetM("primary", 4, false, null)).isEqualTo(8.0);
        // 기본 차로 수: 일방 primary 3 → 6.375 → 6.4, 일방 trunk 2 → 4.75 → 4.8, 양방 _link 2, 그 밖 일방 1 → 3.125 → 3.1
        assertThat(Sidewalks.offsetM("primary", null, true, null)).isEqualTo(6.4);
        assertThat(Sidewalks.offsetM("trunk", null, true, null)).isEqualTo(4.8);
        assertThat(Sidewalks.offsetM("primary_link", null, false, null)).isEqualTo(4.8);
        assertThat(Sidewalks.offsetM("residential", null, true, null)).isEqualTo(3.1);
        assertThat(Sidewalks.offsetM(null, null, false, null)).isEqualTo(4.8);
        // 일방인지 모르면 양방 기본값: primary 4 → 8.0
        assertThat(Sidewalks.offsetM("primary", null, null, null)).isEqualTo(8.0);
    }

    @Test
    void splitsWhenLanesChangeAndJoinsWhenEqual() {
        RoadPath path = path(List.of(
                edge(1, 1, 2, "primary", List.of(A, B), "right", 4, 0, null),
                edge(2, 2, 3, "primary", List.of(B, C), "right", 4, 0, null),
                edge(3, 3, 4, "primary", List.of(C, D), "right", 6, 0, null)),
                List.of(A, B, C, D));

        assertThat(Sidewalks.along(path, 0)).containsExactly(
                new Sidewalk(0, 2, "right", "primary", 4, false, null, 8.0),
                new Sidewalk(2, 3, "right", "primary", 6, false, null, 11.3));
    }

    private static RoadPath path(List<RoadEdge> edges, List<double[]> line) {
        return new RoadPath(edges, line, 0);
    }

    private static RoadEdge edge(long id, long from, long to, String highway, List<double[]> geometry,
            String sidewalk) {
        return new RoadEdge(id, from, to, 100, highway, geometry, EdgePenalty.NONE, sidewalk);
    }

    private static RoadEdge edge(long id, long from, long to, String highway, List<double[]> geometry,
            String sidewalk, Integer lanes, Integer oneway, Double widthM) {
        return new RoadEdge(id, from, to, 100, highway, geometry, EdgePenalty.NONE, sidewalk, null, lanes, oneway,
                widthM);
    }
}
