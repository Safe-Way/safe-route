package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.facility.Poi;

/** 응답의 쉼 곳·어린이보호구역 수와 아이콘, 계단 구간 (2026-11-06) */
class RestSchoolStairsTests {

    static RouteResult.Route last(RouteResult r) {
        return r.routes().get(r.routes().size() - 1);
    }

    @Test
    void stairsAreListedAndSeniorIsWarnedWhenItCannotAvoidThem() {
        // 지름길 S–M이 계단. 북쪽 길이 약 1.1배라 노인은 피한다
        RouteResult avoided = ModeRulesTests.route(RouteFinderTests.diamond(0.0004, RouteFinderTests.STAIRS),
                List.of(), at(12), "senior", "auto");
        assertThat(avoided.routes()).hasSize(2);
        assertThat(avoided.routes().get(0).stairs()).singleElement().satisfies(s -> {
            assertThat(s.fromM()).isZero();
            assertThat(s.toM()).isBetween(95, 105);
            assertThat(s.ramp()).isNull();
        });
        assertThat(last(avoided).stairs()).isEmpty();
        assertThat(avoided.explanation().text()).doesNotContain("주의");

        // 북쪽 길이 약 1.9배라 한도(1.2배)를 넘는다: 계단으로 가고 알린다
        RouteResult passed = ModeRulesTests.route(RouteFinderTests.diamond(0.0015, RouteFinderTests.STAIRS),
                List.of(), at(12), "senior", "auto");
        assertThat(last(passed).stairs()).hasSize(1);
        assertThat(passed.explanation().text()).doesNotContain("계단을 피하고")
                .endsWith("(주의: 계단 1곳을 지납니다. 계단을 피하는 길은 너무 멉니다)");
        // 앱이 목록으로 보여 줄 수 있게 이유 한 줄과 주의를 나눠 준다
        assertThat(passed.explanation().summary()).doesNotContain("(", "계단");
        assertThat(passed.explanation().notes()).containsExactly(
                RouteResult.Note.warn("stairs", "계단 1곳을 지납니다. 계단을 피하는 길은 너무 멉니다"));
        assertThat(passed.explanation().text()).startsWith(passed.explanation().summary());

        // 어른은 계단을 피하지 않으니 알리지 않지만, 구간은 응답에 있다
        RouteResult adult = ModeRulesTests.route(RouteFinderTests.diamond(0.0015, RouteFinderTests.STAIRS),
                List.of(), at(12), "adult", "auto");
        assertThat(last(adult).stairs()).hasSize(1);
        assertThat(adult.explanation().text()).doesNotContain("계단");
    }

    @Test
    void openRestPlacesAndSchoolZonesAreCountedAndMarked() {
        // M 바로 옆 09~18시 화장실과 초등학교 (지름길 위)
        Poi toilet = new Poi("toilet", "공중화장실", null, M_P[0], M_P[1] + 0.0002, 9 * 60, 18 * 60, "매일",
                null, null, null, null, null, null, 30, 1);
        Poi school = new Poi("school_zone", "초등학교", null, M_P[0], M_P[1] - 0.0010, null, null, null,
                null, null, null, null, null, null, 31, 1);

        RouteResult noon = ModeRulesTests.route(diamond(0.0004), List.of(toilet, school), at(12), "adult", "auto");
        assertThat(last(noon).counts()).containsEntry("rest", 1).containsEntry("school", 1);
        assertThat(noon.facilities()).extracting(RouteResult.FacilityMark::type)
                .contains("TOILET", "SCHOOL_ZONE");
        // 쉼 곳·보호구역 차이는 낮 문장(note)으로만 말하고 시설 차이 문장에는 넣지 않는다
        assertThat(noon.explanation().text()).doesNotContain("쉼 곳", "어린이보호구역");

        // 기준 범위 안을 걷는 길이: 어린이 낮은 보호구역·도움 시설, 어른 낮은 기준이 없어 빈 맵
        RouteResult child = ModeRulesTests.route(diamond(0.0004), List.of(toilet, school), at(12), "child", "auto");
        assertThat(last(child).covered()).containsOnlyKeys("school", "help");
        assertThat(last(child).covered().get("school")).isBetween(0, last(child).distanceM());
        assertThat(last(noon).covered()).isEmpty();

        // 20시엔 화장실이 닫혀 세지 않는다. 보호구역은 시간과 상관없다
        RouteResult night = ModeRulesTests.route(diamond(0.0004), List.of(toilet, school), at(20), "adult", "auto");
        RouteResult.Route shortest = night.routes().get(0);
        assertThat(shortest.counts()).containsEntry("rest", 0).containsEntry("school", 1);
        assertThat(night.facilities()).extracting(RouteResult.FacilityMark::type).doesNotContain("TOILET");
    }
}
