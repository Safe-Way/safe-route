package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteResult.Sidewalk;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
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
        // 큰 길이라도 인도 없음 태그는 그대로. 연결로도 큰 길과 같고, 골목급은 일방이어도 모름
        RoadEdge none = edge(7, 1, 2, "secondary", List.of(A, B), "no", null, 0, null);
        RoadEdge small = edge(8, 1, 2, "residential", List.of(A, B), null, null, 0, null);
        RoadEdge link = edge(9, 1, 2, "primary_link", List.of(A, B), null, null, null, null);
        RoadEdge smallOneway = edge(10, 1, 2, "service", List.of(A, B), null, null, 1, null);
        RoadEdge linkOneway = edge(11, 1, 2, "tertiary_link", List.of(A, B), null, null, 1, null);
        assertThat(Sidewalks.along(path(List.of(none), List.of(A, B)), 0)).extracting(Sidewalk::side).containsExactly("no");
        assertThat(Sidewalks.along(path(List.of(small), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("unknown");
        assertThat(Sidewalks.along(path(List.of(link), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("both");
        assertThat(Sidewalks.along(path(List.of(smallOneway), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("unknown");
        assertThat(Sidewalks.along(path(List.of(linkOneway), List.of(A, B)), 0)).extracting(Sidewalk::side)
                .containsExactly("right");
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

    @Test
    void bothTakesSideOfSeparateSidewalkPastAlleyAndCrosswalk() {
        // 서남동 행정복지센터 앞 (w20): 태그 없는 동서 큰 길(both) → 북쪽 골목 7m → 골목 횡단보도 → 따로 그린 북쪽 인도
        double[] w = m(-100, 0), o = m(0, 0), n1 = m(0, 7), n2 = m(6, 7), n3 = m(100, 7);
        RoadEdge road = way(1, 1, 2, "primary", false, w, o);
        RoadEdge alley = way(2, 2, 3, "residential", false, o, n1);
        RoadEdge zebra = way(3, 3, 4, "footway", true, n1, n2);
        RoadEdge walk = way(4, 4, 5, "footway", false, n2, n3);

        // 동쪽으로 걸으면 북쪽 인도는 왼쪽
        assertThat(Sidewalks.along(path(List.of(road, alley, zebra, walk), List.of(w, o, n1, n2, n3)), 0).get(0).side())
                .isEqualTo("left");
        // 거꾸로 걸으면 인도가 큰 길 앞에 있다(뒤엔 없음). 서쪽으로 걸으면 북쪽은 오른쪽
        List<Sidewalk> back = Sidewalks.along(path(List.of(walk, zebra, alley, road), List.of(n3, n2, n1, o, w)), 0);
        assertThat(back.get(back.size() - 1).side()).isEqualTo("right");
    }

    @Test
    void walkwayFartherThan25mFromTheRoadIsNotItsSidewalk() {
        double[] w = m(-100, 0), o = m(0, 0);
        for (double north : new double[] {20, 28}) {
            double[] n1 = m(0, north), n2 = m(100, north);
            RoadEdge road = way(1, 1, 2, "primary", false, w, o);
            RoadEdge alley = way(2, 2, 3, "residential", false, o, n1);
            RoadEdge walk = way(3, 3, 4, "footway", false, n1, n2);

            assertThat(Sidewalks.along(path(List.of(road, alley, walk), List.of(w, o, n1, n2)), 0).get(0).side())
                    .isEqualTo(north < 25 ? "left" : "both");
        }
    }

    /** (126.93, 35.14)에서 동쪽·북쪽으로 m만큼 떨어진 점 */
    private static double[] m(double eastM, double northM) {
        return new double[] {126.93 + eastM / (111_195.0 * Math.cos(Math.toRadians(35.14))), 35.14 + northM / 111_195.0};
    }

    /** 길이를 실제로 잰 길. crosswalk면 횡단보도 길(footway=crossing) */
    private static RoadEdge way(long id, long from, long to, String highway, boolean crosswalk, double[]... points) {
        List<double[]> line = List.of(points);
        return new RoadEdge(id, from, to, Geo.length(line), highway, line,
                crosswalk ? new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)) : EdgePenalty.NONE);
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
