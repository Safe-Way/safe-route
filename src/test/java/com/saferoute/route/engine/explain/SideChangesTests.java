package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;

/** 큰 길을 건너는 곳과 횡단보도 자리 (specs/014) */
@DisplayName("큰 길 건너는 자리")
class SideChangesTests {

    private static final double LAT = 35.14;

    // 가운데 노드 0에서 동서남북 100m
    private static final Map<Long, double[]> NODES = Map.of(0L, at(0, 0), 1L, at(-100, 0), 2L, at(100, 0),
            3L, at(0, 100), 4L, at(0, -100));

    @Test
    @DisplayName("큰 길에서 걷는 쪽이 left ↔ right로 바뀌는 노드에서 그 길 횡단보도로 건넌다")
    void leftToRightUsesCrosswalkOnTheBigRoad() {
        for (String highway : List.of("primary", "tertiary")) {
            RoadEdge in = road(1, 1, 0, highway, "left");
            RoadEdge out = road(2, 0, 2, highway, "right");
            double[] cw = at(8, 0);

            assertThat(SideChanges.along(path(in, out), 5, graph(in, out), List.of(cw)))
                    .singleElement().satisfies(c -> {
                        // 경로 선 첫 점이 응답 geometry 5번: 가운데 노드는 6번
                        assertThat(c.at()).isEqualTo(6);
                        assertThat(c.lat()).isEqualTo(cw[1]);
                        assertThat(c.lon()).isEqualTo(cw[0]);
                        assertThat(c.kind()).isEqualTo("crossing");
                        // 횡단보도 점은 양 끝을 모른다
                        assertThat(c.from()).isNull();
                        assertThat(c.to()).isNull();
                    });
        }
    }

    @Test
    @DisplayName("인도 쪽은 걷는 방향 기준이라 거꾸로 걷는 길은 쪽을 바꿔 비교한다")
    void edgeWalkedAgainstDrawnDirectionFlipsSide() {
        // 동쪽 길을 2→0으로 그렸고 인도는 그린 방향 왼쪽: 0→2로 걸으면 오른쪽
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 2, 0, "primary", "left");
        RoadPath p = new RoadPath(List.of(in, out), List.of(at(-100, 0), at(0, 0), at(100, 0)),
                in.lengthM() + out.lengthM());
        double[] cw = at(8, 0);

        assertThat(SideChanges.along(p, 0, graph(in, out), List.of(cw))).singleElement().satisfies(c -> {
            assertThat(c.at()).isEqualTo(1);
            assertThat(c.lon()).isEqualTo(cw[0]);
        });
    }

    @Test
    @DisplayName("같은 길로 되돌아가는 곳은 쪽이 뒤집혀도 건넌 것이 아니다")
    void turningBackOnSameEdgeIsNotACrossing() {
        // SideChanges.find
        // 들를 곳(노드 0)에서 같은 길로 되돌아간다: 걷는 쪽은 right → left로 바뀌지만 건너지 않았다
        RoadEdge in = road(1, 1, 0, "primary", "right");
        RoadPath p = new RoadPath(List.of(in, in), List.of(at(-100, 0), at(0, 0), at(-100, 0)), 2 * in.lengthM());

        assertThat(SideChanges.along(p, 0, graph(in), List.of(at(-8, 0)))).isEmpty();
    }

    @Test
    @DisplayName("노드 30m 안이면서 큰 길 선 5m 안인 횡단보도가 없으면 보내지 않는다")
    void noCrosswalkWithin30mOrOnlyOffTheLineSendsNothing() {
        // NEAR_M, LINE_M (1안)
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");

        assertThat(SideChanges.along(path(in, out), 0, graph(in, out), List.of(at(40, 0)))).isEmpty();
        // 20m 안이지만 큰 길 선에서 10m: 옆 골목을 건너는 횡단보도
        assertThat(SideChanges.along(path(in, out), 0, graph(in, out), List.of(at(3, 10)))).isEmpty();
    }

    @Test
    @DisplayName("한쪽만 both면 큰 길 교차로에서 45°보다 크게 꺾을 때만 건넌다")
    void oneBothSideOnlyWhenTurningAtBigJunction() {
        // 큰 길 교차로는 큰 길 3개 이상, TURN_DEG (민수 요청 ②)
        RoadEdge w = road(1, 1, 0, "primary", null);
        RoadEdge e = road(2, 0, 2, "primary", "right");
        RoadEdge n = road(3, 0, 3, "primary", "right");
        RoadEdge s = road(4, 0, 4, "primary", null);
        RoadGraph junction = graph(w, e, n, s);
        // 북쪽 갈래 위와 동쪽 갈래 위 횡단보도
        List<double[]> cw = List.of(at(0, 8), at(8, 0));

        // both → right라도 곧장 지나면 보내지 않는다
        assertThat(SideChanges.along(path(w, e), 0, junction, cw)).isEmpty();
        // both → right: 꺾으면 북쪽 갈래 횡단보도에서 건넌다
        assertThat(SideChanges.along(path(w, n), 0, junction, cw)).singleElement().satisfies(c -> {
            assertThat(c.at()).isEqualTo(1);
            assertThat(c.lat()).isEqualTo(at(0, 8)[1]);
        });
        // right → both도 같다: 북쪽에서 내려와 동쪽(both)으로 꺾으면 건넌다
        RoadEdge fromNorth = road(5, 3, 0, "primary", "right");
        RoadEdge eBoth = road(2, 0, 2, "primary", null);
        assertThat(SideChanges.along(path(fromNorth, eBoth), 0, graph(fromNorth, eBoth, w, s), cw)).singleElement()
                .satisfies(c -> assertThat(c.at()).isEqualTo(1));
        // 큰 길 2개뿐인 모퉁이는 꺾어도 건너지 않는다
        assertThat(SideChanges.along(path(w, n), 0, graph(w, n), cw)).isEmpty();
    }

    @Test
    @DisplayName("앞뒤가 둘 다 both면 양쪽에 인도가 있어 보내지 않는다")
    void bothToBothIsNotACrossingEvenWhenTurning() {
        // 진행표 w19
        // 앞뒤 모두 인도 태그 없는 양방향 큰 길(both): 양쪽에 인도가 있어 건널 필요가 없다 (w19, 지그재그)
        RoadEdge w = road(1, 1, 0, "primary", null);
        RoadEdge e = road(2, 0, 2, "primary", null);
        RoadEdge n = road(3, 0, 3, "primary", null);
        RoadEdge s = road(4, 0, 4, "primary", null);
        RoadGraph junction = graph(w, e, n, s);
        List<double[]> cw = List.of(at(0, 8), at(8, 0));

        assertThat(SideChanges.along(path(w, n), 0, junction, cw)).isEmpty();
        assertThat(SideChanges.along(path(w, e), 0, junction, cw)).isEmpty();
    }

    @Test
    @DisplayName("횡단보도 점이 없으면 큰 길과 만나는 횡단보도 길의 가운데로 건넌다")
    void footwayCrossingMiddleWhenNoPoint() {
        // 큰 길 선과 만나거나 꼭짓점이 LINE_M 안에 닿는 footway=crossing
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");
        RoadEdge zebra = crosswalk(10, 5, 6, at(10, -8), at(10, 8));
        // 큰 길 선을 넘지 않고 끝이 선 3m 앞에서 멈춘 횡단보도 길도 닿은 것으로 본다
        RoadEdge shortZebra = crosswalk(11, 7, 8, at(10, 3), at(10, 20));
        assertThat(SideChanges.along(path(in, out), 0, graph(in, out, shortZebra), List.of()))
                .singleElement().satisfies(c -> {
                    assertThat(c.kind()).isEqualTo("footway");
                    assertThat(c.lat()).isCloseTo(at(10, 11.5)[1], within(1e-9));
                });

        assertThat(SideChanges.along(path(in, out), 0, graph(in, out, zebra), List.of()))
                .singleElement().satisfies(c -> {
                    assertThat(c.kind()).isEqualTo("footway");
                    assertThat(c.lon()).isCloseTo(at(10, 0)[0], within(1e-9));
                    assertThat(c.lat()).isCloseTo(at(10, 0)[1], within(1e-9));
                    // 양 끝은 횡단보도 길의 첫 점·끝 점 [위도, 경도]
                    assertThat(c.from()).containsExactly(at(10, -8)[1], at(10, -8)[0]);
                    assertThat(c.to()).containsExactly(at(10, 8)[1], at(10, 8)[0]);
                });
    }

    @Test
    @DisplayName("at은 응답 geometry 점 번호라 앞 길들의 꺾임점까지 모두 센다")
    void atCountsEveryGeometryPointOfEarlierEdges() {
        // 들어오는 큰 길이 꺾임점 하나를 더 가진 선: 서쪽 끝(0번) – 꺾임점(1번) – 가운데 노드(2번)
        double[] bend = at(-50, 2);
        List<double[]> line = List.of(at(-100, 0), bend, at(0, 0));
        RoadEdge in = new RoadEdge(1, 1, 0, Geo.length(line), "primary", line, EdgePenalty.NONE, "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");
        RoadPath p = path(in, out);

        assertThat(SideChanges.along(p, 0, graph(in, out), List.of(at(8, 0)))).singleElement()
                .satisfies(c -> assertThat(p.line().get(c.at())).isEqualTo(at(0, 0)));
    }

    @Test
    @DisplayName("both 구간 앞뒤에 따로 그린 인도가 서로 반대쪽이면 그 구간에서 건넌다")
    void sidewalksOnOppositeSidesAroundBothStretchCrossOnIt() {
        // 진행표 w20
        // 남쪽 인도 → 연결 골목 8m → 태그 없는 큰 길(both, 동쪽으로) → 북쪽 골목 7m → 골목 횡단보도 → 북쪽 인도 (w20)
        Map<Long, double[]> n = Map.of(1L, at(-100, -8), 2L, at(-50, -8), 3L, at(-50, 0), 4L, at(50, 0), 5L, at(50, 7),
                6L, at(56, 7), 7L, at(150, 7));
        RoadEdge south = road(1, 1, 2, "footway", null, n);
        RoadEdge link = road(2, 2, 3, "residential", null, n);
        RoadEdge big = road(3, 3, 4, "primary", null, n);
        RoadEdge alley = road(4, 4, 5, "residential", null, n);
        RoadEdge zebra = crosswalk(5, 5, 6, n.get(5L), n.get(6L));
        RoadEdge north = road(6, 6, 7, "footway", null, n);
        RoadPath p = path(south, link, big, alley, zebra, north);
        RoadGraph g = graph(south, link, big, alley, zebra, north);
        // 큰 길 위 횡단보도 점: 구간 서쪽 끝(geometry 2번)에서 30m, 동쪽 끝(3번)에서 70m
        double[] cw = at(-20, 0);

        SideChanges.Found found = SideChanges.find(p, 0, g, List.of(cw));
        assertThat(found.crossAt()).singleElement().satisfies(c -> {
            assertThat(c.at()).isEqualTo(2);
            assertThat(c.lon()).isEqualTo(cw[0]);
            assertThat(c.kind()).isEqualTo("crossing");
        });
        assertThat(found.withoutCrosswalk()).isFalse();
        // 출발 점을 앞에 붙여 경로 선이 응답 geometry 5번에서 시작해도 같은 구간 점을 가리킨다
        assertThat(SideChanges.find(p, 5, g, List.of(cw)).crossAt()).singleElement()
                .satisfies(c -> assertThat(c.at()).isEqualTo(7));
        // 구간 선 LINE_M 안 횡단보도 점이 여럿이면 선에 가장 가까운 점
        double[] closer = at(10, 1);
        assertThat(SideChanges.find(p, 0, g, List.of(closer, at(-20, 3))).crossAt()).singleElement()
                .satisfies(c -> assertThat(c.lon()).isEqualTo(closer[0]));
        // 횡단보도가 없으면 보내지 않고 알린다. 골목 횡단보도 길은 큰 길 선에서 7m라 쓰지 않는다
        SideChanges.Found none = SideChanges.find(p, 0, g, List.of());
        assertThat(none.crossAt()).isEmpty();
        assertThat(none.withoutCrosswalk()).isTrue();
    }

    @Test
    @DisplayName("쪽을 정한 both 구간이 바로 앞 큰 길 구간과 반대쪽이면 그 구간에서 건넌다")
    void bothStretchResolvedOppositeToPreviousTaggedStretchCrossesOnIt() {
        // 인도로 쪽을 정함, left ↔ right (진행표 w20)
        // 남쪽 인도 태그(오른쪽) 큰 길 → 태그 없는 큰 길(both) → 북쪽 골목 7m → 북쪽 인도: both 구간에서 건넌다 (w20)
        Map<Long, double[]> n = Map.of(1L, at(-100, 0), 2L, at(0, 0), 3L, at(100, 0), 4L, at(100, 7), 5L, at(200, 7));
        RoadEdge tagged = road(1, 1, 2, "primary", "right", n);
        RoadEdge big = road(2, 2, 3, "primary", null, n);
        RoadEdge alley = road(3, 3, 4, "residential", null, n);
        RoadEdge north = road(4, 4, 5, "footway", null, n);
        RoadPath p = path(tagged, big, alley, north);
        RoadGraph g = graph(tagged, big, alley, north);

        // 큰 길 2개뿐이라 노드 규칙(both 한쪽은 큰 길 교차로에서만)은 안 잡는다. both 구간 위 횡단보도: 동쪽 끝(2번)에 가깝다
        assertThat(SideChanges.find(p, 0, g, List.of(at(80, 0))).crossAt()).singleElement()
                .satisfies(c -> assertThat(c.at()).isEqualTo(2));
        assertThat(SideChanges.find(p, 0, g, List.of()).withoutCrosswalk()).isTrue();
        // 앞 큰 길 인도가 북쪽(왼쪽)이면 같은 쪽이라 건너지 않는다
        RoadEdge sameSide = road(1, 1, 2, "primary", "left", n);
        SideChanges.Found same = SideChanges.find(path(sameSide, big, alley, north), 0,
                graph(sameSide, big, alley, north), List.of(at(80, 0)));
        assertThat(same.crossAt()).isEmpty();
        assertThat(same.withoutCrosswalk()).isFalse();
    }

    @Test
    @DisplayName("both 구간 앞뒤 인도가 같은 쪽이면 건너지 않고 횡단보도 없음 주의도 없다")
    void sidewalksOnSameSideAroundBothStretchIsNoCrossing() {
        Map<Long, double[]> n = Map.of(1L, at(-100, 8), 2L, at(-50, 8), 3L, at(-50, 0), 4L, at(50, 0), 5L, at(50, 8),
                6L, at(150, 8));
        RoadEdge before = road(1, 1, 2, "footway", null, n);
        RoadEdge link = road(2, 2, 3, "residential", null, n);
        RoadEdge big = road(3, 3, 4, "primary", null, n);
        RoadEdge alley = road(4, 4, 5, "residential", null, n);
        RoadEdge after = road(5, 5, 6, "footway", null, n);

        SideChanges.Found found = SideChanges.find(path(before, link, big, alley, after), 0,
                graph(before, link, big, alley, after), List.of());
        assertThat(found.crossAt()).isEmpty();
        assertThat(found.withoutCrosswalk()).isFalse();
    }

    @Test
    @DisplayName("노드에서 이미 건너는 자리를 보낸 구간은 다시 보내지 않는다")
    void stretchStartingAtNodeCrossingIsNotSentTwice() {
        // 구간 규칙으로 다시 보내지 않음 (SideChanges.find)
        // 남쪽 인도(right) 큰 길로 동쪽으로 와서 큰 길 교차로 0에서 북쪽(both)으로 꺾고, 북쪽 끝에서 서쪽 7m 골목 → 서쪽 인도
        Map<Long, double[]> n = Map.of(1L, at(-100, 0), 0L, at(0, 0), 2L, at(100, 0), 4L, at(0, -100), 3L, at(0, 100),
                5L, at(-7, 100), 6L, at(-7, 200));
        RoadEdge tagged = road(1, 1, 0, "primary", "right", n);
        RoadEdge east = road(2, 0, 2, "primary", null, n);
        RoadEdge south = road(4, 0, 4, "primary", null, n);
        RoadEdge big = road(3, 0, 3, "primary", null, n);
        RoadEdge alley = road(5, 3, 5, "residential", null, n);
        RoadEdge walk = road(6, 5, 6, "footway", null, n);

        // 북쪽 구간은 서쪽 인도로 left가 되어 앞 구간(right)과 반대지만, 노드 0에서 이미 건넜다
        SideChanges.Found found = SideChanges.find(path(tagged, big, alley, walk), 0,
                graph(tagged, east, south, big, alley, walk), List.of(at(0, 8)));
        assertThat(found.crossAt()).singleElement().satisfies(c -> assertThat(c.at()).isEqualTo(1));
        assertThat(found.withoutCrosswalk()).isFalse();
    }

    @Test
    @DisplayName("구간과 다른 노드에서 건너는 자리를 모두 보내고 at 순서로 늘어놓는다")
    void stretchCrossingAndLaterNodeCrossingAreBothSentInOrder() {
        // w20 구간(큰 길 both, 남쪽 인도 → 북쪽 인도) 뒤에 left → right로 바뀌는 큰 길 노드 8
        Map<Long, double[]> n = Map.of(1L, at(-100, -8), 2L, at(-50, -8), 3L, at(-50, 0), 4L, at(50, 0), 5L, at(50, 7),
                6L, at(56, 7), 7L, at(150, 7), 8L, at(250, 7), 9L, at(350, 7));
        RoadEdge south = road(1, 1, 2, "footway", null, n);
        RoadEdge link = road(2, 2, 3, "residential", null, n);
        RoadEdge big = road(3, 3, 4, "primary", null, n);
        RoadEdge alley = road(4, 4, 5, "residential", null, n);
        RoadEdge zebra = crosswalk(5, 5, 6, n.get(5L), n.get(6L));
        RoadEdge north = road(6, 6, 7, "footway", null, n);
        RoadEdge left = road(7, 7, 8, "primary", "left", n);
        RoadEdge right = road(8, 8, 9, "primary", "right", n);

        assertThat(SideChanges.find(path(south, link, big, alley, zebra, north, left, right), 0,
                graph(south, link, big, alley, zebra, north, left, right), List.of(at(258, 7), at(-20, 0))).crossAt())
                .extracting(c -> c.at()).containsExactly(2, 7);
    }

    @Test
    @DisplayName("횡단보도 점은 노드 30m·큰 길 선 5m 안이면 쓰고 넘으면 쓰지 않는다")
    void crosswalkPointWithinNearAndLineLimitsCounts() {
        // NEAR_M, LINE_M
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");
        RoadPath p = path(in, out);
        RoadGraph g = graph(in, out);

        assertThat(SideChanges.along(p, 0, g, List.of(at(SideChanges.NEAR_M - 0.01, 0)))).hasSize(1);
        assertThat(SideChanges.along(p, 0, g, List.of(at(SideChanges.NEAR_M + 0.01, 0)))).isEmpty();
        assertThat(SideChanges.along(p, 0, g, List.of(at(3, SideChanges.LINE_M - 0.01)))).hasSize(1);
        assertThat(SideChanges.along(p, 0, g, List.of(at(3, SideChanges.LINE_M + 0.01)))).isEmpty();
    }

    @Test
    @DisplayName("한쪽만 both인 큰 길 교차로는 방향이 45°보다 크게 바뀌어야 꺾은 것이다")
    void oneBothSideCountsOnlyTurnsSharperThanTurnDeg() {
        // TURN_DEG
        for (double turn : new double[] {SideChanges.TURN_DEG - 0.01, SideChanges.TURN_DEG + 0.01}) {
            // 동쪽으로 와서 왼쪽(북쪽)으로 turn도 꺾는 갈래 노드 7
            double h = Math.toRadians(90 - turn);
            Map<Long, double[]> n = new HashMap<>(NODES);
            n.put(7L, at(100 * Math.sin(h), 100 * Math.cos(h)));
            RoadEdge w = road(1, 1, 0, "primary", null, n);
            RoadEdge e = road(2, 0, 2, "primary", null, n);
            RoadEdge s = road(4, 0, 4, "primary", null, n);
            RoadEdge bend = road(7, 0, 7, "primary", "right", n);
            double[] cw = at(8 * Math.sin(h), 8 * Math.cos(h));

            assertThat(SideChanges.along(path(w, bend), 0, graph(w, e, s, bend), List.of(cw)))
                    .hasSize(turn > SideChanges.TURN_DEG ? 1 : 0);
        }
    }

    /** (126.93, LAT)에서 동쪽·북쪽으로 m만큼 떨어진 점 */
    private static double[] at(double eastM, double northM) {
        return new double[] {126.93 + eastM / (111_195.0 * Math.cos(Math.toRadians(LAT))), LAT + northM / 111_195.0};
    }

    private static RoadEdge road(long id, long from, long to, String highway, String sidewalk) {
        return road(id, from, to, highway, sidewalk, NODES);
    }

    private static RoadEdge road(long id, long from, long to, String highway, String sidewalk,
            Map<Long, double[]> nodes) {
        List<double[]> line = List.of(nodes.get(from), nodes.get(to));
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE, sidewalk);
    }

    private static RoadEdge crosswalk(long id, long from, long to, double[] a, double[] b) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), "footway", line,
                new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)));
    }

    private static RoadGraph graph(RoadEdge... edges) {
        Map<Long, double[]> nodes = new HashMap<>();
        for (RoadEdge e : edges) {
            nodes.put(e.from(), e.geometry().get(0));
            nodes.put(e.to(), e.geometry().get(e.geometry().size() - 1));
        }
        return new RoadGraph(nodes, List.of(edges));
    }

    /** 길마다 from → to로 차례로 지나는 경로 */
    private static RoadPath path(RoadEdge... edges) {
        List<double[]> line = new ArrayList<>(List.of(edges[0].geometry().get(0)));
        double len = 0;
        for (RoadEdge e : edges) {
            line.addAll(e.geometry().subList(1, e.geometry().size()));
            len += e.lengthM();
        }
        return new RoadPath(List.of(edges), line, len);
    }
}
