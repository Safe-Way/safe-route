package com.saferoute.route.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;

/** 역 출구를 지상 길 위 수직의 발에 잇기 (비 오는 날 지하 길, 2026-11-09) */
class UndergroundLinksTests {

    static final double LAT = 35.14;
    static final double[] A = {126.93000, LAT};
    static final double[] MID = {126.93110, LAT};
    static final double[] B = {126.93220, LAT};
    /** 꺾임점이 하나 있는 동서 방향 지상 길 약 200m (1 → 2) */
    static final RoadEdge ROAD = new RoadEdge(100, 1, 2, Geo.length(List.of(A, MID, B)), "residential",
            List.of(A, MID, B), EdgePenalty.NONE, "both", "금남로");
    /** 첫 선분 위 약 45m 지점(경도 126.93050)에서 북쪽 약 22m */
    static final UndergroundLinks.Exit X1 = new UndergroundLinks.Exit(-3001603, new double[] {126.93050, LAT + 0.0002}, 100);
    /** 둘째 선분 위 남쪽 약 22m */
    static final UndergroundLinks.Exit X2 = new UndergroundLinks.Exit(-3001501, new double[] {126.93160, LAT - 0.0002}, 100);

    static List<RoadEdge> pieces(List<RoadEdge> out) {
        return out.stream().filter(e -> e.highway().equals("residential")).toList();
    }

    static List<RoadEdge> links(List<RoadEdge> out) {
        return out.stream().filter(e -> e.highway().equals("footway")).toList();
    }

    @Test
    void exitInTheMiddleSplitsTheRoadAndAddsALink() {
        List<RoadEdge> out = UndergroundLinks.attach(List.of(ROAD), List.of(X1));

        List<RoadEdge> pieces = pieces(out);
        assertThat(pieces).hasSize(2);
        assertThat(links(out)).hasSize(1);
        // 조각 길이 합 = 원래 길이, 성질·인도 쪽·이름은 그대로
        assertThat(pieces.stream().mapToDouble(RoadEdge::lengthM).sum()).isCloseTo(ROAD.lengthM(), within(1e-6));
        assertThat(pieces).allSatisfy(p -> {
            assertThat(p.name()).isEqualTo("금남로");
            assertThat(p.sidewalk()).isEqualTo("both");
        });
        long foot = pieces.get(0).to();
        assertThat(pieces.get(0).from()).isEqualTo(1);
        assertThat(pieces.get(1).from()).isEqualTo(foot);
        assertThat(pieces.get(1).to()).isEqualTo(2);
        double[] footP = {126.93050, LAT};
        assertThat(pieces.get(0).lengthM()).isCloseTo(Geo.distance(A, footP), within(0.01));

        RoadEdge link = links(out).get(0);
        assertThat(link.from()).isEqualTo(X1.node());
        assertThat(link.to()).isEqualTo(foot);
        assertThat(link.lengthM()).isCloseTo(Geo.distance(X1.point(), footP), within(0.01));
        // 지붕·지하 성질이 없는 지상 연결 길 (비 맞는 구간)
        assertThat(link.penalty().get(Penalty.UNDERGROUND)).isZero();
        // 새 번호는 요청 임시 번호·지하 길 번호와 겹치지 않는 범위
        assertThat(foot).isLessThanOrEqualTo(UndergroundLinks.NODE_BASE);
        assertThat(out.stream().filter(e -> e.id() != ROAD.id())).allSatisfy(
                e -> assertThat(e.id()).isLessThanOrEqualTo(UndergroundLinks.EDGE_BASE));
    }

    @Test
    void twoExitsOnOneRoadSplitInOrderAlongTheRoad() {
        // 거꾸로 넣어도 길 처음에서 가까운 차례로 나눈다 (평동역 1·2번 출구가 같은 길)
        List<RoadEdge> out = UndergroundLinks.attach(List.of(ROAD), List.of(X2, X1));

        List<RoadEdge> pieces = pieces(out);
        assertThat(pieces).hasSize(3);
        assertThat(links(out)).hasSize(2);
        assertThat(pieces.get(0).from()).isEqualTo(1);
        assertThat(pieces.get(1).from()).isEqualTo(pieces.get(0).to());
        assertThat(pieces.get(2).from()).isEqualTo(pieces.get(1).to());
        assertThat(pieces.get(2).to()).isEqualTo(2);
        assertThat(pieces.stream().mapToDouble(RoadEdge::lengthM).sum()).isCloseTo(ROAD.lengthM(), within(1e-6));
        // 가운데 조각은 꺾임점(MID)을 지난다
        assertThat(pieces.get(1).geometry()).contains(MID);
        assertThat(links(out)).extracting(RoadEdge::from).containsExactly(X1.node(), X2.node());
        assertThat(links(out).get(0).to()).isEqualTo(pieces.get(0).to());
        assertThat(links(out).get(1).to()).isEqualTo(pieces.get(1).to());
    }

    @Test
    void exitNearAnEndJoinsThatEndWithoutSplitting() {
        // 길 처음에서 1m 안에 발이 내린다
        UndergroundLinks.Exit x = new UndergroundLinks.Exit(-3000101, new double[] {126.930005, LAT + 0.0001}, 100);
        List<RoadEdge> out = UndergroundLinks.attach(List.of(ROAD), List.of(x));

        assertThat(out).hasSize(2).contains(ROAD);
        RoadEdge link = links(out).get(0);
        assertThat(link.to()).isEqualTo(1);
        assertThat(link.geometry().get(1)).isEqualTo(A);
    }

    @Test
    void exitWhoseRoadIsNotLoadedIsLeftOut() {
        UndergroundLinks.Exit x = new UndergroundLinks.Exit(-3000101, X1.point(), 999);
        assertThat(UndergroundLinks.attach(List.of(ROAD), List.of(x))).containsExactly(ROAD);
    }
    /** 출구 점에서 북쪽으로 나가는 지상 길 (A파트가 출구 점에 그은 선) */
    static RoadEdge surface(long id, Penalty p) {
        double[] far = {126.93050, LAT + 0.0005};
        return new RoadEdge(id, X1.node(), 3, Geo.distance(X1.point(), far), "footway", List.of(X1.point(), far),
                p == null ? EdgePenalty.NONE : new EdgePenalty(java.util.Map.of(p, 1.0)));
    }

    @Test
    void exitAlreadyOnAUsableSurfaceEdgeGetsNoLink() {
        // 휠체어 확인한 광장 선(광장 표시 없음)이 출구 점에 닿은 문화전당역 4번 출구: 곧은 연결 길을 더하지 않는다 (2026-11-16)
        RoadEdge plazaYes = surface(200, null);
        assertThat(UndergroundLinks.attach(List.of(ROAD, plazaYes), List.of(X1))).containsExactly(ROAD, plazaYes);
    }

    @Test
    void exitTouchingOnlyStairsOrUnknownPlazaKeepsItsLink() {
        // 계단·높낮이 모르는 광장 선으로는 휠체어가 못 나가므로 그대로 잇는다. 지하 길도 지상 길로 치지 않는다
        List<RoadEdge> out = UndergroundLinks.attach(
                List.of(ROAD, surface(201, Penalty.STAIRS), surface(202, Penalty.PLAZA), surface(203, Penalty.UNDERGROUND)),
                List.of(X1));
        assertThat(pieces(out)).hasSize(2);
        assertThat(out).filteredOn(e -> e.from() == X1.node() && e.to() == pieces(out).get(0).to()).hasSize(1);
    }
}
