package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.S;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.engine.facility.Availability;
import com.saferoute.route.engine.facility.Availability.Status;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RouteFinder;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;

/**
 * 경곗값: 경계 바로 안과 바로 밖에서 결과가 바뀌는지 (「경로 엔진 테스트 문서」 경곗값 검사).
 * 거리는 경계 ± 0.01m, 우회 한도는 경계 × (1 ± 1e-9), 시각은 경계 ± 1분으로 잰다.
 */
class BoundaryTests {

    /** 위도 1도의 길이(m). 같은 경도에서 위도만 바꾸면 Geo.distance가 정확히 이 비율이다 */
    static final double M_PER_LAT = Geo.distance(0, 0, 0, 1);

    /** S에서 정북으로 meters만큼 간 점 {경도, 위도} */
    static double[] northOfS(double meters) {
        return new double[] {S_P[0], S_P[1] + meters / M_PER_LAT};
    }

    /**
     * S–M–T 직선 길 + 북쪽 6km에 길과 이어지지 않은 노드 F.
     * F는 서비스 지역(노드 전체를 감싼 사각형)을 북쪽으로 넓히는 용도이고, 붙이기는 가장 큰 덩어리에만 하므로 F에는 안 붙는다.
     */
    static RoadGraph lineWithFarNode() {
        double[] f = northOfS(6000);
        return new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, 99L, f), List.of(
                edge(1, S, M, S_P, M_P, EdgePenalty.NONE), edge(2, M, T, M_P, T_P, EdgePenalty.NONE)));
    }

    static RouteService service() {
        return TestRoutes.service(lineWithFarNode(), TestRoutes.pois(List.of()), TestRoutes.at(12), props());
    }

    static RouteRequest request(double[] from, double[] to) {
        return new RouteRequest(new Place(from[1], from[0], null), new Place(to[1], to[0], null), "auto", "adult");
    }

    static RouteError errorOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RouteException e) {
            return e.error();
        }
    }

    // ── 요청 거리·지역 ──

    /** S에서 정동으로 meters만큼 간 점. 짧은 거리라 경도 0.001도의 길이로 나눠 구한다 */
    static double[] eastOfS(double meters) {
        double perDeg = Geo.distance(S_P[0], S_P[1], S_P[0] + 0.001, S_P[1]) / 0.001;
        return new double[] {S_P[0] + meters / perDeg, S_P[1]};
    }

    @Test
    void samePointBelow50m() {
        // S와 그 동쪽 50.01m의 K를 잇는 길. 두 점이 다른 노드에 붙도록 길을 따로 만든다
        double[] k = eastOfS(50.01);
        double[] f = northOfS(6000);
        RoadGraph g = new RoadGraph(Map.of(S, S_P, 5L, k, 99L, f), List.of(edge(1, S, 5L, S_P, k, EdgePenalty.NONE)));
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), TestRoutes.at(12), props());

        assertThat(errorOf(() -> s.route(request(S_P, eastOfS(49.99))))).isEqualTo(RouteError.SAME_POINT);
        assertThatCode(() -> s.route(request(S_P, k))).doesNotThrowAnyException();
    }

    @Test
    void tooFarAbove5000m() {
        RouteService s = service();
        // 5,000m 안이면 거리 검사는 통과하고, 도착점에서 가장 가까운 길이 너무 멀어 NO_ROUTE가 된다
        assertThat(errorOf(() -> s.route(request(S_P, northOfS(4999.99))))).isEqualTo(RouteError.NO_ROUTE);
        assertThat(errorOf(() -> s.route(request(S_P, northOfS(5000.01))))).isEqualTo(RouteError.TOO_FAR);
    }

    @Test
    void snapUpTo300m() {
        RouteService s = service();
        // 도착점에서 가장 가까운 길 노드는 S (T는 대각선이라 더 멀다)
        assertThatCode(() -> s.route(request(T_P, northOfS(299.99)))).doesNotThrowAnyException();
        assertThat(errorOf(() -> s.route(request(T_P, northOfS(300.01))))).isEqualTo(RouteError.NO_ROUTE);
    }

    @Test
    void serviceAreaEdgeIsInside() {
        RouteService s = service();
        double[] onEdge = northOfS(4000);
        onEdge[1] = S_P[1];                       // 남쪽 경계선(노드 중 가장 작은 위도) 위
        onEdge[0] = T_P[0];                       // 동쪽 경계선 위 (T)
        double[] outside = {T_P[0] + 1e-7, T_P[1]};
        assertThat(errorOf(() -> s.route(request(S_P, onEdge)))).isNotEqualTo(RouteError.OUT_OF_AREA);
        assertThat(errorOf(() -> s.route(request(S_P, outside)))).isEqualTo(RouteError.OUT_OF_AREA);
    }

    @Test
    void checksRunInOrder() {
        RouteService s = service();
        // 지역 밖 두 점이 30m 떨어져 있으면 OUT_OF_AREA가 아니라 SAME_POINT
        double[] a = {S_P[0] - 0.01, S_P[1]};
        double[] b = {a[0], a[1] + 30 / M_PER_LAT};
        assertThat(errorOf(() -> s.route(request(a, b)))).isEqualTo(RouteError.SAME_POINT);
        // 입력 오류는 거리 검사보다 먼저
        assertThatThrownBy(() -> s.route(new RouteRequest(new Place(S_P[1], S_P[0], null),
                new Place(S_P[1], S_P[0], null), "auto", "runner"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankModeAndNightUseDefaults() {
        RouteService s = service();
        var r = s.route(new RouteRequest(new Place(S_P[1], S_P[0], null), new Place(T_P[1], T_P[0], null), null, null));
        assertThat(r.conditions().nightSource()).isEqualTo("auto");
    }

    // ── 경로 고르기 ──

    /** 북쪽 길(S–N–T)이 지름길보다 몇 배 긴지 */
    static double northRatio(RoadGraph g) {
        RouteFinder.Found any = RouteFinderTests.find(g, S, T, RouteFinderTests.DIRECT_IS_DARK, 0, e -> true, false);
        return any.safe().lengthM() / any.shortest().lengthM();
    }

    @Test
    void detourLimitIsInclusive() {
        RoadGraph g = RouteFinderTests.diamond(0.0006, EdgePenalty.NONE);
        double ratio = northRatio(g);

        RouteFinder.Found in = RouteFinderTests.find(g, S, T, RouteFinderTests.DIRECT_IS_DARK, ratio * (1 + 1e-9),
                e -> true, false);
        RouteFinder.Found out = RouteFinderTests.find(g, S, T, RouteFinderTests.DIRECT_IS_DARK, ratio * (1 - 1e-9),
                e -> true, false);
        assertThat(RouteFinderTests.goesNorth(in)).isTrue();
        assertThat(RouteFinderTests.goesNorth(out)).isFalse();
        assertThat(out.detourCapped()).isTrue();
    }

    @Test
    void seniorStairAvoidanceLimitIsInclusive() {
        RoadGraph g = RouteFinderTests.diamond(0.0006, RouteFinderTests.STAIRS);
        RouteFinder.Found free = RouteFinderTests.find(g, S, T, null, 0, e -> true, true);
        double ratio = free.safe().lengthM() / free.shortest().lengthM();

        RouteFinder.Found in = RouteFinderTests.find(g, S, T, null, ratio * (1 + 1e-9), e -> true, true);
        RouteFinder.Found out = RouteFinderTests.find(g, S, T, null, ratio * (1 - 1e-9), e -> true, true);
        assertThat(in.safe().edges()).noneMatch(RouteFinder::isStairs);
        assertThat(out.safe().edges()).anyMatch(RouteFinder::isStairs);
    }

    // ── 시설 반경 (지나는 시설 세기) ──

    @Test
    void countRadiusIsInclusive() {
        FacilityCounter counter = new FacilityCounter(props());
        List<double[]> line = List.of(S_P, T_P);
        List<Poi> pois = List.of(
                Poi.at("light", 126.9311, LAT + 29.99 / M_PER_LAT), Poi.at("light", 126.9311, LAT - 30.01 / M_PER_LAT),
                Poi.at("cctv", 126.9311, LAT + 49.99 / M_PER_LAT), Poi.at("cctv", 126.9311, LAT - 50.01 / M_PER_LAT));
        FacilityCounter.Counts c = counter.countAlong(List.of(line), pois).get(0);
        assertThat(c.get(FacilityGroup.LIGHT)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(1);
    }

    // ── 시각·운영시간 ──

    @Test
    void childNightStartsExactly60MinutesBeforeSunset() {
        RouteService s = service();
        LocalDate day = LocalDate.of(2026, 11, 4);
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        LocalTime sunset = new NightJudge().judge(LAT, S_P[0], ZonedDateTime.of(day, LocalTime.NOON, seoul),
                NightOption.AUTO).sunset();
        ZonedDateTime edge = ZonedDateTime.of(day, sunset.minusMinutes(60), seoul);

        assertThat(s.conditions(S_P, edge.minusMinutes(1), NightOption.AUTO, 60, false, "auto").night()).isFalse();
        assertThat(s.conditions(S_P, edge, NightOption.AUTO, 60, false, "auto").night()).isTrue();
        assertThat(s.conditions(S_P, edge.plusMinutes(1), NightOption.AUTO, 60, false, "auto").night()).isTrue();
        // 어른(0분)은 같은 시각에 아직 낮
        assertThat(s.conditions(S_P, edge, NightOption.AUTO, 0, false, "auto").night()).isFalse();
    }

    static final Set<LocalDate> NO_HOLIDAY = Set.of();

    @Test
    void openingHoursStartInclusiveEndExclusive() {
        Poi toilet = AvailabilityTests.poi("toilet", 9 * 60, 18 * 60, "매일", null, null, null, null, null, null);
        LocalDateTime wed = LocalDateTime.of(2026, 11, 4, 0, 0);
        assertThat(Availability.usable(toilet, wed.withHour(8).withMinute(59), NO_HOLIDAY)).isFalse();
        assertThat(Availability.usable(toilet, wed.withHour(9), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(toilet, wed.withHour(17).withMinute(59), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(toilet, wed.withHour(18), NO_HOLIDAY)).isFalse();
    }

    @Test
    void seasonIncludesFirstAndLastDay() {
        Poi cold = AvailabilityTests.poi("shelter", null, null, null, "11-15", "03-15", null, null, null, null);
        // 2026-11-14(토)는 기간 전, 11-15(일)는 기간 안이지만 주말이라 닫힘
        assertThat(Availability.status(cold, LocalDateTime.of(2026, 11, 14, 12, 0), NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);
        assertThat(Availability.status(cold, LocalDateTime.of(2026, 11, 15, 12, 0), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        // 2027-03-15(월)은 기간 마지막 날, 03-16(화)은 기간 뒤
        assertThat(Availability.status(cold, LocalDateTime.of(2027, 3, 15, 12, 0), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, LocalDateTime.of(2027, 3, 16, 12, 0), NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);
    }

    @Test
    void shelterWithoutHoursIsWeekday9To18() {
        Poi cold = AvailabilityTests.poi("shelter", null, null, null, "11-15", "03-15", null, null, null, null);
        LocalDateTime wed = LocalDateTime.of(2026, 12, 2, 0, 0);
        assertThat(Availability.status(cold, wed.withHour(8).withMinute(59), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        assertThat(Availability.status(cold, wed.withHour(9), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, wed.withHour(17).withMinute(59), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, wed.withHour(18), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        assertThat(Availability.status(cold, wed.withHour(12), Set.of(wed.toLocalDate()))).isEqualTo(Status.CLOSED);
    }
}
