package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/** 응답의 시설 수(counts)·기준 범위 길이(covered)·지도 아이콘 (2026-11-06) */
@DisplayName("응답 시설 수·범위·아이콘")
class RouteFacilitiesTests {

    static RouteResult route(RoadGraph g, List<Poi> pois, int hour, String mode, String night, String... overrides) {
        return TestRoutes.service(g, TestRoutes.pois(pois), at(hour), props(overrides))
                .route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), night, mode));
    }

    @Test
    @DisplayName("밤에는 지킴이집과 건물 안 비상벨을 세지 않는다")
    void nightCountsLeaveOutShopsAndIndoorBells() {
        // 길 옆 화장실 안 비상벨(24시간)과 평일 9~18시 지킴이집
        RoadGraph straight = new RoadGraph(Map.of(S, S_P, T, T_P), List.of(edge(1, S, T, S_P, T_P, EdgePenalty.NONE)));
        Poi indoorBell = new Poi("bell", "화장실", 1, M_P[0], LAT + 0.0001, 0, 24 * 60, "매일", null, null,
                null, null, null, null, 1, 1);
        Poi guard = new Poi("guard_house", null, null, M_P[0], LAT - 0.0001, null, null, null, null, null,
                9 * 60, 18 * 60, "평일", true, 2, 1);
        List<Poi> pois = List.of(indoorBell, guard);

        RouteResult noon = route(straight, pois, 12, "adult", "off");
        assertThat(noon.routes().get(0).counts()).containsEntry("bell", 1).containsEntry("help", 1);
        // 밤에는 지킴이집·건물 안 비상벨을 경로 고를 때 안 쓰니 세지도 않는다
        RouteResult night = route(straight, pois, 21, "adult", "on");
        assertThat(night.routes().get(0).counts()).containsEntry("bell", 0).containsEntry("help", 0);
    }

    @Test
    @DisplayName("열린 쉼 곳과 어린이보호구역을 세고 지도에 표시한다")
    void openRestPlacesAndSchoolZonesAreCountedAndMarked() {
        // M 바로 옆 09~18시 화장실과 초등학교 (지름길 위)
        Poi toilet = new Poi("toilet", "공중화장실", null, M_P[0], M_P[1] + 0.0002, 9 * 60, 18 * 60, "매일",
                null, null, null, null, null, null, 30, 1);
        Poi school = new Poi("school_zone", "초등학교", null, M_P[0], M_P[1] - 0.0010, null, null, null,
                null, null, null, null, null, null, 31, 1);

        RouteResult noon = route(diamond(0.0004), List.of(toilet, school), 12, "adult", "auto");
        assertThat(safe(noon).counts()).containsEntry("rest", 1).containsEntry("school", 1);
        assertThat(noon.facilities()).extracting(RouteResult.FacilityMark::type)
                .contains("TOILET", "SCHOOL_ZONE");
        // 쉼 곳·보호구역 차이는 낮 문장(note)으로만 말하고 시설 차이 문장에는 넣지 않는다
        assertThat(noon.explanation().text()).doesNotContain("쉼 곳", "어린이보호구역");

        // 기준 범위 안을 걷는 길이: 어린이 낮은 보호구역·도움 시설, 어른 낮은 기준이 없어 빈 맵
        RouteResult child = route(diamond(0.0004), List.of(toilet, school), 12, "child", "auto");
        assertThat(safe(child).covered()).containsOnlyKeys("school", "help");
        assertThat(safe(child).covered().get("school")).isBetween(0, safe(child).distanceM());
        assertThat(safe(noon).covered()).isEmpty();

        // 20시엔 화장실이 닫혀 세지 않는다. 보호구역은 시간과 상관없다
        RouteResult night = route(diamond(0.0004), List.of(toilet, school), 20, "adult", "auto");
        assertThat(night.routes().get(0).counts()).containsEntry("rest", 0).containsEntry("school", 1);
        assertThat(night.facilities()).extracting(RouteResult.FacilityMark::type).doesNotContain("TOILET");
    }

    @Test
    @DisplayName("지도 시설 아이콘은 정한 개수까지, 비상벨·도움 시설을 먼저 넣는다")
    void facilityMarksAreCappedWithHelpAndBellFirst() {
        // max-facilities (route.yml max-facilities)
        RouteResult r = route(diamond(0.0006), brightSideFacilities(), 21, "adult", "auto", "route.max-facilities=2");

        assertThat(r.facilities()).extracting(RouteResult.FacilityMark::type).containsExactly("EMERGENCY_BELL", "CCTV");
    }
}
