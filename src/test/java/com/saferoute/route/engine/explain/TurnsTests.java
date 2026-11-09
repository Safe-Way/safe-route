package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.search.RoadPath;

/** 꺾는 곳 (FR-34): 갈림길에서 45°보다 크게 바뀔 때만 */
class TurnsTests {

    // A에서 동쪽으로 약 100m 가면 B. B에서 북쪽 C, 동쪽 D, 남쪽 E로 갈라진다
    static final double[] A = {126.9300, 35.1400};
    static final double[] B = {126.9311, 35.1400};
    static final double[] C = {126.9311, 35.1409};
    static final double[] D = {126.9322, 35.1400};
    static final double[] E = {126.9311, 35.1391};

    static RoadEdge edge(long id, long from, long to, double[] a, double[] b) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), "footway", line, EdgePenalty.NONE);
    }

    static final RoadEdge AB = edge(1, 1, 2, A, B);
    static final RoadEdge BC = edge(2, 2, 3, B, C);
    static final RoadEdge BD = edge(3, 2, 4, B, D);
    static final RoadEdge BE = edge(4, 2, 5, B, E);

    static RoadPath path(RoadEdge... edges) {
        List<double[]> line = new ArrayList<>(List.of(A, B));
        double len = 0;
        for (RoadEdge e : edges) {
            len += e.lengthM();
        }
        line.add(edges[edges.length - 1].geometry().get(1));
        return new RoadPath(List.of(edges), line, len);
    }

    static List<Turns.Turn> turns(RoadEdge... edges) {
        return Turns.along(path(edges), 0, 45, 15, 20);
    }

    @Test
    void leftAndRightAtJunction() {
        List<Turns.Turn> left = turns(AB, BC);
        List<Turns.Turn> right = turns(AB, BE);

        assertThat(left).hasSize(1);
        assertThat(left.get(0).left()).isTrue();          // 동쪽으로 가다 북쪽 = 왼쪽
        assertThat(left.get(0).angle()).isCloseTo(90, within(2.0));
        assertThat(left.get(0).atM()).isCloseTo(AB.lengthM(), within(0.5));
        assertThat(right).singleElement().satisfies(t -> assertThat(t.left()).isFalse());
    }

    @Test
    void straightThroughJunctionIsNotATurn() {
        assertThat(turns(AB, BD)).isEmpty();
    }

    @Test
    void bendWithoutJunctionIsAlsoATurn() {
        // 갈림길(다른 길)이 없어도 큰길 모퉁이처럼 방향이 크게 바뀌면 알린다 (FR-34, 파랑새안과의원 맞은편 사례)
        assertThat(turns(AB, BC)).singleElement().satisfies(t -> assertThat(t.left()).isTrue());
    }

    @Test
    void closeTurnsAreKeptAndLinkedWithCrossing() {
        // A→B 동쪽, B에서 오른쪽(남쪽) 횡단보도 약 11m, F에서 다시 왼쪽(동쪽)
        double[] f = {126.9311, 35.1399};          // B 남쪽 약 11m
        double[] g = {126.9322, 35.1399};          // F 동쪽
        RoadEdge bf = new RoadEdge(10, 2, 6, Geo.length(List.of(B, f)), "footway", List.of(B, f),
                new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)));
        RoadEdge fg = edge(11, 6, 7, f, g);
        List<double[]> line = List.of(A, B, f, g);
        RoadPath p = new RoadPath(List.of(AB, bf, fg), line, AB.lengthM() + bf.lengthM() + fg.lengthM());

        List<Turns.Turn> t = Turns.along(p, 0, 45, 15, 20);

        // 둘 다 남고, 첫 꺾는 곳에 횡단보도와 다음 꺾는 곳까지 거리가 붙는다
        assertThat(t).hasSize(2);
        assertThat(t.get(0).left()).isFalse();
        assertThat(t.get(0).crossing()).isTrue();
        assertThat(t.get(0).thenM()).isCloseTo(bf.lengthM(), within(0.5));
        assertThat(t.get(1).left()).isTrue();
        assertThat(t.get(1).crossing()).isFalse();
        assertThat(t.get(1).thenM()).isNull();
    }

    @Test
    void oneBendSeenAtClosePointsIsReportedOnce() {
        // B와 B 북쪽 5m 점 Q에서 동쪽→북쪽으로 한 번 꺾는다. 두 점이 같은 꺾음을 함께 잡는다
        double[] q = {126.9311, 35.140045};       // B 북쪽 약 5m
        double[] r = {126.9311, 35.1409};
        RoadEdge bq = edge(30, 2, 13, B, q);
        RoadEdge qr = edge(31, 13, 14, q, r);
        RoadPath p = new RoadPath(List.of(AB, bq, qr), List.of(A, B, q, r), AB.lengthM() + bq.lengthM() + qr.lengthM());

        assertThat(Turns.along(p, 0, 45, 15, 20)).singleElement()
                .satisfies(t -> assertThat(t.left()).isTrue());
    }

    @Test
    void shortSideStepOntoCrosswalkIsNotATurnButIsACrossing() {
        // 동쪽으로 가다 4m 옆(북쪽)으로 비켜 횡단보도를 타고 계속 동쪽. 길 하나(4m) 안에서만 재면 90° 꺾음으로 나왔다
        double[] j = {126.9311, 35.140036};       // B 북쪽 약 4m
        double[] k = {126.9322, 35.140036};       // j 동쪽
        RoadEdge bj = edge(20, 2, 9, B, j);
        RoadEdge jk = new RoadEdge(21, 9, 10, Geo.length(List.of(j, k)), "footway", List.of(j, k),
                new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)));
        RoadPath p = new RoadPath(List.of(AB, bj, jk), List.of(A, B, j, k), AB.lengthM() + bj.lengthM() + jk.lengthM());

        assertThat(Turns.along(p, 0, 45, 15, 20)).isEmpty();
        // 꺾지 않아도 횡단보도는 따로 알린다
        assertThat(Turns.crossings(p, 0)).singleElement().satisfies(c -> {
            assertThat(c[0]).isCloseTo(AB.lengthM() + bj.lengthM(), within(0.5));
            assertThat(c[1]).isCloseTo(AB.lengthM() + bj.lengthM() + jk.lengthM(), within(0.5));
        });
    }
}
