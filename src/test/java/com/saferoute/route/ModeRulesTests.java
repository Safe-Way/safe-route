package com.saferoute.route;

import static com.saferoute.route.TestRoutes.CURB;
import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.STAIRS;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.at;
import static com.saferoute.route.TestRoutes.diamond;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.north;
import static com.saferoute.route.TestRoutes.props;
import static com.saferoute.route.TestRoutes.safe;
import static com.saferoute.route.TestRoutes.xy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;

/**
 * 모드별 낮·밤 규칙 (결정 문서 「경로 엔진 결정 사항 정리」 2026-11-02·03 확정)을 요청부터 응답까지 확인한다.
 * 작은 마름모 도로망: S–M–T 지름길(200m)과 S–N–T 북쪽 길.
 */
@DisplayName("모드별 낮·밤 규칙")
class ModeRulesTests {

    static final double[] N_NEAR = {126.93110, LAT + 0.0004}; // 북쪽 길 약 1.09배일 때 N 위치
    /** 북쪽 길 바깥(지름길에서 약 100m). 반경 80m면 북쪽 길만 덮는다 */
    static final double[] BEYOND_N = {126.93110, LAT + 0.0009};

    static RouteResult route(RoadGraph g, List<Poi> pois, ZonedDateTime now, String mode, String night,
            String... overrides) {
        RouteService s = TestRoutes.service(g, TestRoutes.pois(pois), now, props(overrides));
        return s.route(new RouteRequest(new Place(LAT, 126.93000, "출발"), new Place(LAT, 126.93220, "도착"), night, mode));
    }

    @Test
    @DisplayName("어른 낮은 기준이 없어 가장 짧은 길 하나만 준다")
    void adultDayIsShortest() {
        // route.yml adult에 day 없음
        RouteResult r = route(diamond(0.0004), List.of(Poi.at("school_zone", N_NEAR[0], N_NEAR[1])), at(12), "adult", "auto");
        assertThat(north(r)).isFalse();
        assertThat(r.routes()).hasSize(1);
    }

    @Test
    @DisplayName("어린이 낮은 도움 시설보다 어린이보호구역 가까운 길을 고른다")
    void childDayPrefersSchoolZoneOverHelpPoint() {
        // 북쪽 N 옆에 학교(보호구역), 지름길 M 옆에 편의점(도움 시설). 반경을 줄여 작은 도로망에서 차이를 만든다
        List<Poi> pois = List.of(Poi.at("school_zone", BEYOND_N[0], BEYOND_N[1]),
                Poi.at("store", TestRoutes.M_P[0], TestRoutes.M_P[1]));
        RouteResult r = route(diamond(0.0006), pois, at(12), "child", "auto",
                "route.profiles.child.day.school-m=80", "route.profiles.child.day.help-m=40");

        assertThat(r.conditions().night()).isFalse();
        assertThat(north(r)).isTrue();
        assertThat(r.explanation().text()).endsWith("어린이보호구역과 도움을 청할 곳이 가까운 길로 갑니다");
    }

    @Test
    @DisplayName("어린이 밤에는 어린이보호구역 기준을 쓰지 않는다")
    void childNightIgnoresSchoolZone() {
        // route.yml child.night
        List<Poi> pois = List.of(Poi.at("school_zone", N_NEAR[0], N_NEAR[1]));
        RouteResult r = route(diamond(0.0004), pois, at(21), "child", "auto",
                "route.profiles.child.day.school-m=80");

        assertThat(r.conditions().night()).isTrue();
        assertThat(north(r)).isFalse();
    }

    @Test
    @DisplayName("어린이는 일몰 60분 전 그 분부터 밤이고 어른은 아직 낮이다")
    void childNightStartsExactly60MinutesBeforeSunset() {
        // night-lead-min분 전, 어른은 0분 (route.yml night-lead-min)
        RouteService s = TestRoutes.service(diamond(0.0004), TestRoutes.pois(List.of()), 12);
        LocalDate day = LocalDate.of(2026, 11, 4);
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        LocalTime sunset = new NightJudge().judge(LAT, S_P[0], ZonedDateTime.of(day, LocalTime.NOON, seoul),
                NightOption.AUTO).sunset();
        int lead = props().profile("child").nightLeadMin();
        ZonedDateTime edge = ZonedDateTime.of(day, sunset.minusMinutes(lead), seoul);

        assertThat(s.conditions(S_P, edge.minusMinutes(1), NightOption.AUTO, lead, false, "auto").night()).isFalse();
        assertThat(s.conditions(S_P, edge, NightOption.AUTO, lead, false, "auto").night()).isTrue();
        assertThat(s.conditions(S_P, edge.plusMinutes(1), NightOption.AUTO, lead, false, "auto").night()).isTrue();
        // 어른(0분)은 같은 시각에 아직 낮
        assertThat(s.conditions(S_P, edge, NightOption.AUTO, props().profile("adult").nightLeadMin(), false, "auto")
                .night()).isFalse();
        // route()가 모드의 night-lead-min을 넘기는지
        assertThat(route(diamond(0.0004), List.of(), edge, "child", "auto").conditions().night()).isTrue();
        assertThat(route(diamond(0.0004), List.of(), edge, "adult", "auto").conditions().night()).isFalse();
    }

    @Test
    @DisplayName("밤에는 어두운 지름길보다 등이 있는 길로 간다")
    void nightPrefersLightOverDarkShortcut() {
        // 북쪽 길에만 등 → 밤에는 북쪽, 낮(어른)에는 지름길
        List<Poi> lights = List.of(Poi.at("light", 126.93055, LAT + 0.0002), Poi.at("light", N_NEAR[0], N_NEAR[1]),
                Poi.at("light", 126.93165, LAT + 0.0002));
        assertThat(north(route(diamond(0.0004), lights, at(21), "adult", "auto"))).isTrue();
        assertThat(north(route(diamond(0.0004), lights, at(12), "adult", "auto"))).isFalse();
    }

    /** 길이가 같은 두 길: 북쪽 S–(100, 60)–T와 남쪽 S–(100, −60)–T */
    static RoadGraph twoEqualWays() {
        double[] s = S_P;
        double[] t = TestRoutes.T_P;
        double[] n = xy(100, 60);
        double[] q = xy(100, -60);
        return new RoadGraph(Map.of(1L, s, 2L, t, 3L, n, 4L, q), List.of(
                edge(1, 1, 3, s, n, EdgePenalty.NONE), edge(2, 3, 2, n, t, EdgePenalty.NONE),
                edge(3, 1, 4, s, q, EdgePenalty.NONE), edge(4, 4, 2, q, t, EdgePenalty.NONE)));
    }

    /** 꼭짓점 (100, side × 60)을 지나는 길 위에 20m마다 놓은 시설 (양 끝 endM 안은 비움) */
    static List<Poi> along(int side, String kind, double endM) {
        List<Poi> out = new ArrayList<>();
        double half = Math.hypot(100, 60);
        for (double d = endM; d <= 2 * half - endM; d += 20) {
            double k = d <= half ? d / half : (2 * half - d) / half;
            double[] p = xy(d <= half ? 100 * k : 200 - 100 * k, side * 60 * k);
            out.add("bell".equals(kind) ? TestRoutes.outdoorBell(out.size(), p[0], p[1])
                    : TestRoutes.plain(kind, out.size(), p[0], p[1], 1));
        }
        return out;
    }

    @Test
    @DisplayName("밤 비중은 밝기 > 도움 시설 > CCTV라 앞 기준이 있는 길로 간다")
    void nightPrefersLightOverHelpOverCctv() {
        // 밝기 5, 도움 시설 2, CCTV 1 (route.yml adult.night.weights)
        // 밝기와 도움 시설은 반경을 같게(help-m = coverage.light-m) 두고 양쪽을 바꿔 놓아 비중만으로 가린다.
        // 양 끝 40m를 비워 어느 시설도 S·T 너머 다른 길까지 덮지 않게 한다
        String sameRadius = "route.profiles.adult.night.help-m=" + props().coverage().lightM();
        for (int lightSide : new int[] {1, -1}) {
            List<Poi> lightVsHelp = new ArrayList<>(along(lightSide, "light", 40));
            lightVsHelp.addAll(along(-lightSide, "bell", 40));
            assertThat(north(route(twoEqualWays(), lightVsHelp, at(21), "adult", "auto", sameRadius)))
                    .as("밝은 쪽 %d", lightSide).isEqualTo(lightSide > 0);
        }
        // 도움 시설(반경 20m)이 CCTV(길 따라 50m)보다 좁아도 비중이 커서 도움 시설 쪽
        List<Poi> helpVsCctv = new ArrayList<>(along(1, "bell", 20));
        helpVsCctv.addAll(along(-1, "cctv", 20));
        assertThat(north(route(twoEqualWays(), helpVsCctv, at(21), "adult", "auto",
                "route.profiles.adult.night.help-m=20"))).isTrue();
    }

    @Test
    @DisplayName("노인 낮은 쉼 곳이 열려 있을 때만 그 길을 고른다")
    void seniorDayPrefersRestPlaceOnlyWhileOpen() {
        // 북쪽에 09~18시 화장실. 6월은 19시도 낮인데 화장실은 닫혀서 지름길
        Poi toilet = new Poi("toilet", "공중화장실", null, BEYOND_N[0], BEYOND_N[1], 9 * 60, 18 * 60, "매일",
                null, null, null, null, null, null);
        ZonedDateTime juneNoon = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneId.of("Asia/Seoul"));
        assertThat(north(route(diamond(0.0005), List.of(toilet), juneNoon, "senior", "auto",
                "route.profiles.senior.day.rest-m=80"))).isTrue();
        RouteResult evening = route(diamond(0.0005), List.of(toilet), juneNoon.withHour(19), "senior", "auto",
                "route.profiles.senior.day.rest-m=80");
        assertThat(evening.conditions().night()).isFalse();
        assertThat(north(evening)).isFalse();
    }

    @Test
    @DisplayName("노인은 계단을 못 피할 때만 계단으로 가고 주의를 알린다")
    void seniorIsWarnedOnlyWhenStairsCannotBeAvoided() {
        // 지름길 S–M이 계단. 북쪽 길이 약 1.1배라 노인은 피한다
        RouteResult avoided = route(diamond(0.0004, STAIRS), List.of(), at(12), "senior", "auto");
        assertThat(avoided.routes()).hasSize(2);
        assertThat(avoided.routes().get(0).stairs()).singleElement().satisfies(s -> {
            assertThat(s.fromM()).isZero();
            assertThat(s.toM()).isBetween(95, 105);
            assertThat(s.ramp()).isNull();
        });
        assertThat(safe(avoided).stairs()).isEmpty();
        assertThat(avoided.explanation().text()).doesNotContain("주의");

        // 북쪽 길이 약 1.9배라 한도(1.2배)를 넘는다: 계단으로 가고 알린다
        RouteResult passed = route(diamond(0.0015, STAIRS), List.of(), at(12), "senior", "auto");
        assertThat(safe(passed).stairs()).hasSize(1);
        assertThat(passed.explanation().text()).doesNotContain("계단을 피하고")
                .endsWith("(주의: 계단 1곳을 지납니다. 계단을 피하는 길은 너무 멉니다)");
        // 앱이 목록으로 보여 줄 수 있게 이유 한 줄과 주의를 나눠 준다
        assertThat(passed.explanation().summary()).doesNotContain("(", "계단");
        assertThat(passed.explanation().notes()).containsExactly(
                RouteResult.Note.warn("stairs", "계단 1곳을 지납니다. 계단을 피하는 길은 너무 멉니다"));
        assertThat(passed.explanation().text()).startsWith(passed.explanation().summary());

        // 어른은 계단을 피하지 않으니 알리지 않지만, 구간은 응답에 있다
        RouteResult adult = route(diamond(0.0015, STAIRS), List.of(), at(12), "adult", "auto");
        assertThat(safe(adult).stairs()).hasSize(1);
        assertThat(adult.explanation().text()).doesNotContain("계단");
    }

    @Test
    @DisplayName("조건부 제외는 모드가 켠 것만 한다")
    void onlyTheConditionalAvoidsTheModeTurnsOnApply() {
        // 노인은 계단만, 비포장도 켜면 둘 다 피한다 (route.yml avoid-stairs·avoid-unpaved)
        EdgePenalty unpaved = new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0));
        String both = "route.profiles.senior.avoid-unpaved=true";
        assertThat(north(route(diamond(0.0004, unpaved), List.of(), at(12), "senior", "auto"))).isFalse();
        assertThat(north(route(diamond(0.0004, unpaved), List.of(), at(12), "senior", "auto", both))).isTrue();
        assertThat(north(route(diamond(0.0004, STAIRS), List.of(), at(12), "senior", "auto", both))).isTrue();
    }

    @Test
    @DisplayName("계단·비포장을 피했다는 말은 피하는 모드만 하고 남은 계단은 피했다 하지 않는다")
    void stairsAndUnpavedAreClaimedOnlyWhenTheModeAvoidsThem() {
        // RouteResultAssembler
        // 어른 밤: 지름길 S–M이 계단·비포장이고 북쪽 길에만 등. 밝은 길로 돌지만 계단·비포장을 피하려 돈 것이 아니다
        EdgePenalty both = new EdgePenalty(Map.of(Penalty.STAIRS, 1.0, Penalty.UNPAVED, 1.0));
        List<Poi> lights = List.of(Poi.at("light", 126.93055, LAT + 0.0002), Poi.at("light", N_NEAR[0], N_NEAR[1]),
                Poi.at("light", 126.93165, LAT + 0.0002));
        RouteResult adult = route(diamond(0.0004, both), lights, at(21), "adult", "auto");
        assertThat(north(adult)).isTrue();
        assertThat(adult.explanation().summary()).doesNotContain("계단", "비포장");

        // 노인 낮: 출발 X에서 S까지 계단 30m(피할 길 없음) 뒤 쉼 곳이 있는 북쪽 길로 돈다. 두 경로 모두 그 계단을 지난다
        double[] x = TestRoutes.east(S_P, -30);
        RoadGraph g = TestRoutes.plus(diamond(0.0005), Map.of(5L, x), edge(15, 5, TestRoutes.S, x, S_P, STAIRS));
        Poi toilet = new Poi("toilet", "공중화장실", null, BEYOND_N[0], BEYOND_N[1], 9 * 60, 18 * 60, "매일",
                null, null, null, null, null, null);
        RouteResult senior = TestRoutes.service(g, TestRoutes.pois(List.of(toilet)), at(12),
                props("route.profiles.senior.day.rest-m=80")).route(new RouteRequest(new Place(LAT, x[0], "출발"),
                        new Place(LAT, TestRoutes.T_P[0], "도착"), "auto", "senior"));
        assertThat(north(senior)).isTrue();
        assertThat(senior.explanation().summary()).doesNotContain("계단");
        assertThat(senior.explanation().notes()).contains(
                RouteResult.Note.warn("stairs", "계단 1곳을 지납니다. 계단을 피하는 길은 없습니다"));
    }

    @Test
    @DisplayName("휠체어 낮은 한도와 관계없이 지날 수 있는 길 중 가장 짧은 길로 간다")
    void wheelchairDayTakesShortestPassableRoute() {
        // 지름길에 확인된 턱 → 북쪽 길(약 1.58배)이 지날 수 있는 길 중 최단이라 한도와 관계없이 그 길로
        RouteResult r = route(diamond(0.0011, CURB), List.of(), at(12), "wheelchair", "auto");

        assertThat(north(r)).isTrue();
        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().text()).isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다");
    }

    @Test
    @DisplayName("휠체어 밤은 1.2배 안이면 등이 있는 길로 간다")
    void wheelchairNightPrefersLightWithinDetourLimit() {
        // 북쪽 길에만 등. 밤에는 1.2배 안이면 북쪽, 낮에는 지름길
        List<Poi> lights = List.of(Poi.at("light", 126.93055, LAT + 0.0002), Poi.at("light", N_NEAR[0], N_NEAR[1]),
                Poi.at("light", 126.93165, LAT + 0.0002));
        assertThat(north(route(diamond(0.0004), lights, at(21), "wheelchair", "auto"))).isTrue();
        assertThat(north(route(diamond(0.0004), lights, at(12), "wheelchair", "auto"))).isFalse();
        // 북쪽 길이 1.2배를 넘으면 밤에도 지름길
        assertThat(north(route(diamond(0.0008), lights, at(21), "wheelchair", "auto"))).isFalse();
    }

    @Test
    @DisplayName("휠체어는 경사로가 확인된 계단만 지나고 노인은 경사로가 있어도 피한다")
    void wheelchairUsesStairsOnlyWithConfirmedRamp() {
        // route.yml wheelchair.exclude stairs
        assertThat(north(route(diamond(0.0004, STAIRS), List.of(), at(12), "wheelchair", "auto"))).isTrue();

        // 경사로가 있는 계단: 휠체어는 지름길, 노인은 여전히 계단으로 보고 피한다
        RoadGraph ramp = diamond(0.0004, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), true));
        assertThat(north(route(ramp, List.of(), at(12), "wheelchair", "auto"))).isFalse();
        assertThat(north(route(ramp, List.of(), at(12), "senior", "auto"))).isTrue();

        // 경사로 없음·모름 계단뿐이면 넓혀 다시 찾아도 길이 없다
        for (Boolean unsure : new Boolean[] {null, false}) {
            double[] t = TestRoutes.T_P;
            RoadGraph only = new RoadGraph(Map.of(1L, S_P, 2L, t),
                    List.of(edge(1, 1, 2, S_P, t, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), unsure))));
            TestRoutes.assertNoRoute(() -> route(only, List.of(), at(12), "wheelchair", "auto"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Penalty.class, names = {"FOOTBRIDGE", "FORD"})
    @DisplayName("휠체어는 한도 밖이어도 육교·징검다리를 지나지 않는다")
    void wheelchairNeverUsesFootbridgeOrFord(Penalty p) {
        // 지름길에 못 지나는 육교·징검다리. 북쪽 길은 약 1.58배로 한도(1.2배) 밖이어도 휠체어는 그 길로
        RoadGraph g = diamond(0.0011, new EdgePenalty(Map.of(p, 1.0)));

        assertThat(north(route(g, List.of(), at(12), "wheelchair", "auto"))).isTrue();
        assertThat(north(route(g, List.of(), at(12), "adult", "auto"))).isFalse();
    }

    @Test
    @DisplayName("광장 가로지르기 길은 다른 모드는 쓰고 휠체어는 쓰지 않는다")
    void otherModesWalkAcrossThePlazaButWheelchairDoesNot() {
        // 보행자 광장 가로지르기 길 (w13): 높낮이를 모르는 선이라 휠체어는 쓰지 않는다 (2026-11-10)
        RoadGraph plaza = diamond(0.0006, new EdgePenalty(Map.of(Penalty.PLAZA, 1.0)));
        for (String mode : List.of("adult", "child", "senior")) {
            assertThat(safe(route(plaza, List.of(), at(12), mode, "off")).distanceM()).as(mode).isLessThan(230);
        }
        assertThat(safe(route(plaza, List.of(), at(12), "wheelchair", "off")).distanceM()).isGreaterThan(230);
    }
}
