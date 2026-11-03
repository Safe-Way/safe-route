package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.ingest.FacilityTable;

import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.explain.ExplanationBuilder;
import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityGroup;

/** 차이 설명 문장과 시설 세기 (FR-06, 요구사항 6.3) */
class ExplanationTests {

    static final Conditions NIGHT = new Conditions(true, "auto", null);
    static final RouteProperties.Profile ADULT = TestRoutes.props().profile("adult");

    @Test
    void biggestThreeDifferencesInOrder() {
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 180, 2, Map.of(
                FacilityGroup.CCTV, 12, FacilityGroup.LIGHT, 30, FacilityGroup.BELL, 3, FacilityGroup.HELP, 2));

        assertThat(text).isEqualTo("180m(2분) 더 돌지만 가로등 +30개, CCTV +12대, 비상벨 +3개");
    }

    @Test
    void lossesAreNotMentioned() {
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 120, 2,
                Map.of(FacilityGroup.CCTV, 4, FacilityGroup.LIGHT, -3));

        assertThat(text).isEqualTo("120m(2분) 더 돌지만 CCTV +4대");
    }

    @Test
    void zeroMinutesIsLeftOut() {
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 40, 0, Map.of(FacilityGroup.CCTV, 2)))
                .isEqualTo("40m 더 돌지만 CCTV +2대");
    }

    @Test
    void sameDistanceAndNoFacilityGain() {
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 0, 0, Map.of()))
                .isEqualTo("같은 거리로 더 밝고 사람 눈이 닿는 길로 갑니다");
    }

    @Test
    void dayDoesNotClaimSafest() {
        assertThat(ExplanationBuilder.text(new Conditions(false, "auto", null), ADULT, ADULT.day(), true, false, 0, 0, Map.of()))
                .isEqualTo("지금은 밤이 아니라 가장 짧은 길을 안내합니다");
        assertThat(ExplanationBuilder.text(new Conditions(false, "manual", null), ADULT, ADULT.day(), true, false, 0, 0, Map.of()))
                .isEqualTo("밤 기준을 꺼서 가장 짧은 길을 안내합니다");
    }

    @Test
    void dayModesExplainWithTheirNote() {
        RouteProperties.Profile child = TestRoutes.props().profile("child");
        RouteProperties.Profile senior = TestRoutes.props().profile("senior");
        Conditions day = new Conditions(false, "auto", null);

        assertThat(ExplanationBuilder.text(day, child, child.day(), false, false, 60, 1, Map.of(FacilityGroup.LIGHT, 3)))
                .isEqualTo("60m(1분) 더 돌지만 어린이보호구역과 도움을 청할 곳이 가까운 길로 갑니다");
        assertThat(ExplanationBuilder.text(day, senior, senior.day(), false, false, 30, 0, Map.of()))
                .isEqualTo("30m 더 돌지만 계단을 피하고 쉴 곳이 가까운 길로 갑니다");
    }

    @Test
    void wheelchairSaysItIsTheShortestPassableRoute() {
        RouteProperties.Profile wheel = TestRoutes.props().profile("wheelchair");
        assertThat(ExplanationBuilder.text(NIGHT, wheel, wheel.night(), true, false, 0, 0, Map.of()))
                .isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다");
    }

    @Test
    void sameRouteAndCappedRoute() {
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), true, false, 0, 0, Map.of())).isEqualTo("가장 짧은 길이 가장 안전한 길입니다");
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), true, true, 0, 0, Map.of()))
                .isEqualTo("더 안전한 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다");
    }

    @Test
    void onlyThreeItemsEvenWhenFourGained() {
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 300, 4, Map.of(
                FacilityGroup.LIGHT, 10, FacilityGroup.CCTV, 8, FacilityGroup.BELL, 6, FacilityGroup.HELP, 1));

        assertThat(text).isEqualTo("300m(4분) 더 돌지만 가로등 +10개, CCTV +8대, 비상벨 +6개");
    }

    @Test
    void tiesFollowFixedOrder() {
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 90, 1, Map.of(
                FacilityGroup.HELP, 2, FacilityGroup.BELL, 2, FacilityGroup.CCTV, 2));

        assertThat(text).isEqualTo("90m(1분) 더 돌지만 CCTV +2대, 비상벨 +2개, 도움 지점 +2곳");
    }

    @Test
    void unitsPerGroup() {
        assertThat(FacilityGroup.LIGHT.gain(3)).isEqualTo("가로등 +3개");
        assertThat(FacilityGroup.CCTV.gain(3)).isEqualTo("CCTV +3대");
        assertThat(FacilityGroup.BELL.gain(3)).isEqualTo("비상벨 +3개");
        assertThat(FacilityGroup.HELP.gain(3)).isEqualTo("도움 지점 +3곳");
    }

    @Test
    void countsWithinRadiusAndMergesDuplicates() {
        List<Facility> facilities = List.of(
                // 같은 CCTV가 두 출처에 5m 떨어져 들어 있음 → 카메라 3대 하나로
                new Facility(FacilityTable.CCTV, 1, 126.93100, 35.14020, 3),
                new Facility(FacilityTable.CCTV, 2, 126.93105, 35.14020, 2),
                // 같은 자리 가로등·보안등 → 하나
                new Facility(FacilityTable.STREETLIGHT, 3, 126.93050, 35.14010, 1),
                new Facility(FacilityTable.SECURITY_LIGHT, 4, 126.93050, 35.14010, 1),
                // 길에서 약 45m: 조명 반경(30m) 밖
                new Facility(FacilityTable.SECURITY_LIGHT, 5, 126.93150, 35.14040, 1),
                new Facility(FacilityTable.CONVENIENCE_STORE, 6, 126.93200, 35.13990, 1));
        FacilityCounter counter = new FacilityCounter(RouteServiceTests.facilities(facilities), TestRoutes.props());

        Counts c = counter.countAlong(List.of(List.of(TestRoutes.S_P, TestRoutes.T_P))).get(0);

        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(3);
        assertThat(c.get(FacilityGroup.LIGHT)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.BELL)).isZero();
    }
}
