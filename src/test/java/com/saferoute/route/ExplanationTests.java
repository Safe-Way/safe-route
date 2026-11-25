package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.explain.ExplanationBuilder;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.search.RoadPath;

/** 차이 설명 문장과 시설 세기 (FR-06, 요구사항 6.3) */
@DisplayName("차이 설명 문장")
class ExplanationTests {

    static final Conditions NIGHT = new Conditions(true, "auto", null);
    static final RouteProperties.Profile ADULT = TestRoutes.props().profile("adult");

    @Test
    @DisplayName("밤 설명은 더 걷는 거리·시간과 늘어난 시설 차이 큰 순 세 가지를 말한다")
    void biggestThreeDifferencesInOrder() {
        // FR-06, 요구사항 6.3
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 180, 2, Map.of(
                FacilityGroup.CCTV, 12, FacilityGroup.LIGHT, 30, FacilityGroup.BELL, 3, FacilityGroup.HELP, 2));

        assertThat(text).isEqualTo("180m(2분) 더 돌지만 가로등 +30개, CCTV +12대, 비상벨 +3개");
    }

    @Test
    @DisplayName("줄어든 시설은 설명에 넣지 않는다")
    void lossesAreNotMentioned() {
        // FR-06
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 120, 2,
                Map.of(FacilityGroup.CCTV, 4, FacilityGroup.LIGHT, -3));

        assertThat(text).isEqualTo("120m(2분) 더 돌지만 CCTV +4대");
    }

    @Test
    @DisplayName("더 걷는 시간이 0분이면 시간을 빼고 거리만 말한다")
    void zeroMinutesIsLeftOut() {
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 40, 0, Map.of(FacilityGroup.CCTV, 2)))
                .isEqualTo("40m 더 돌지만 CCTV +2대");
    }

    @Test
    @DisplayName("더 걷지 않고 시설 차이도 없으면 정해진 밤 문장으로 말한다")
    void sameDistanceAndNoFacilityGain() {
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 0, 0, Map.of()))
                .isEqualTo("같은 거리로 더 밝고 사람 눈이 닿는 길로 갑니다");
    }

    @Test
    @DisplayName("낮(자동)이나 밤 기준을 끈 때는 안전하다 하지 않고 가장 짧은 길이라 말한다")
    void dayDoesNotClaimSafest() {
        assertThat(ExplanationBuilder.text(new Conditions(false, "auto", null), ADULT, ADULT.day(), true, false, 0, 0, Map.of()))
                .isEqualTo("지금은 밤이 아니라 가장 짧은 길을 안내합니다");
        assertThat(ExplanationBuilder.text(new Conditions(false, "manual", null), ADULT, ADULT.day(), true, false, 0, 0, Map.of()))
                .isEqualTo("밤 기준을 꺼서 가장 짧은 길을 안내합니다");
    }

    @Test
    @DisplayName("낮 기준이 있는 모드는 시설 차이 대신 그 모드 note 문장으로 말한다")
    void dayModesExplainWithTheirNote() {
        // route.yml profiles.*.day.note
        RouteProperties.Profile child = TestRoutes.props().profile("child");
        RouteProperties.Profile senior = TestRoutes.props().profile("senior");
        Conditions day = new Conditions(false, "auto", null);

        assertThat(ExplanationBuilder.text(day, child, child.day(), false, false, 60, 1, Map.of(FacilityGroup.LIGHT, 3)))
                .isEqualTo("60m(1분) 더 돌지만 어린이보호구역과 도움을 청할 곳이 가까운 길로 갑니다");
        assertThat(ExplanationBuilder.text(day, senior, senior.day(), false, false, 30, 0, Map.of()))
                .isEqualTo("30m 더 돌지만 쉴 곳이 가까운 길로 갑니다");
    }

    static final Conditions DAY = new Conditions(false, "auto", null);
    static final RouteProperties.Profile SENIOR = TestRoutes.props().profile("senior");

    @Test
    @DisplayName("노인 낮 설명은 쉼 곳 차이를 말하고 가로등 차이는 말하지 않는다")
    void seniorDayNamesRestPlacesButNotLights() {
        // 가로등 차이는 노인 낮 기준이 아니라 말하지 않는다
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 96, 2,
                Map.of(FacilityGroup.REST, 11, FacilityGroup.LIGHT, -4), ExplanationBuilder.Avoided.NONE))
                .isEqualTo("96m(2분) 더 돌지만 쉼 곳 +11곳");
    }

    @Test
    @DisplayName("노인 낮은 피한 계단(일부면 개수)과 비포장을 설명에 넣는다")
    void seniorDaySaysHowManyStairsItAvoided() {
        // route.yml senior.avoid-stairs
        ExplanationBuilder.Avoided stairs = new ExplanationBuilder.Avoided(1, 0, false, false);
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 96, 2,
                Map.of(FacilityGroup.REST, 11), stairs))
                .isEqualTo("96m(2분) 더 돌지만 계단을 피하고 쉼 곳 +11곳");
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 40, 1, Map.of(), stairs))
                .isEqualTo("40m(1분) 더 돌지만 계단을 피하는 길로 갑니다");
        // 일부만 피했으면 개수로. 남은 계단은 주의(stairsNotes)가 따로 알린다
        ExplanationBuilder.Avoided some = new ExplanationBuilder.Avoided(3, 1, false, false);
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 96, 2,
                Map.of(FacilityGroup.REST, 11), some))
                .isEqualTo("96m(2분) 더 돌지만 계단 3곳 중 2곳을 피하고 쉼 곳 +11곳");
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 40, 1, Map.of(), some))
                .isEqualTo("40m(1분) 더 돌지만 계단 3곳 중 2곳을 피하는 길로 갑니다");
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 40, 1, Map.of(),
                new ExplanationBuilder.Avoided(3, 1, true, false)))
                .isEqualTo("40m(1분) 더 돌지만 계단 3곳 중 2곳과 비포장을 피하는 길로 갑니다");
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 40, 1, Map.of(),
                new ExplanationBuilder.Avoided(1, 0, true, false)))
                .isEqualTo("40m(1분) 더 돌지만 계단·비포장을 피하는 길로 갑니다");
    }

    @Test
    @DisplayName("계단을 하나도 못 피했으면 계단을 피했다고 하지 않는다")
    void noStairsAvoidedIsNotClaimed() {
        // 하나도 못 피했으면(같거나 더 많으면) 계단을 피했다고 하지 않는다
        assertThat(ExplanationBuilder.text(DAY, SENIOR, SENIOR.day(), false, false, 96, 2,
                Map.of(FacilityGroup.REST, 11), new ExplanationBuilder.Avoided(1, 1, false, false)))
                .isEqualTo("96m(2분) 더 돌지만 쉼 곳 +11곳");
    }

    @Test
    @DisplayName("어린이 낮 설명은 도움 지점·어린이보호구역·비상벨 차이를 말한다")
    void childDayNamesSchoolZoneAndHelpPoints() {
        // 도움 지점에는 비상벨도 든다
        RouteProperties.Profile child = TestRoutes.props().profile("child");
        assertThat(ExplanationBuilder.text(DAY, child, child.day(), false, false, 84, 1,
                Map.of(FacilityGroup.SCHOOL, 1, FacilityGroup.HELP, 2, FacilityGroup.BELL, 1),
                ExplanationBuilder.Avoided.NONE))
                .isEqualTo("84m(1분) 더 돌지만 도움 지점 +2곳, 어린이보호구역 +1곳, 비상벨 +1개");
    }

    @Test
    @DisplayName("휠체어 낮에 행사만 피해 돌았으면 비포장 문장을 쓰지 않는다")
    void wheelchairDayDetourForEventsOnlyDoesNotMentionUnpaved() {
        // 행사만 피해 돌았으면 비포장 문장을 쓰지 않는다 (withEvents가 바꿀 수 있게 낮 문장)
        RouteProperties.Profile wheel = TestRoutes.props().profile("wheelchair");
        assertThat(ExplanationBuilder.text(DAY, wheel, wheel.day(), false, false, 30, 1, Map.of(),
                new ExplanationBuilder.Avoided(0, 0, false, true))).isEqualTo("지금은 밤이 아니라 가장 짧은 길을 안내합니다");
    }

    @Test
    @DisplayName("비 오는 날 지붕 있는 길 차이를 시설 차이보다 먼저 말한다")
    void roofGainComesFirstThenFacilities() {
        RouteProperties.Phase rainyNight = ADULT.rainy(TestRoutes.props().rain()).night();
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, rainyNight, false, false, 144, 2, Map.of(FacilityGroup.LIGHT, 12),
                ExplanationBuilder.Avoided.NONE, 780)).isEqualTo("144m(2분) 더 돌지만 비가 와서 지붕 있는 길 +780m, 가로등 +12개");
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, rainyNight, false, false, 144, 2, Map.of(),
                ExplanationBuilder.Avoided.NONE, 780)).isEqualTo("144m(2분) 더 돌지만 비가 와서 지붕 있는 길 +780m");
    }

    @Test
    @DisplayName("둘레를 피했다는 알림은 안전 경로가 지나지 않는 것만 말한다")
    void onlyEventsLeftBehindAreClaimedAsAvoided() {
        // 피하는 것(또는 신고) 중에서 (ExplanationBuilder.avoided)
        RouteResult.Event festival = new RouteResult.Event("행사", "충장축제", null, null, null, 70, 130, false, null);
        RouteResult.Event work = new RouteResult.Event("공사", "금남로 보도 공사", null, null, null, 70, 130, true, null);
        // 어른의 행사는 피하는 것이 아니다
        assertThat(ExplanationBuilder.eventNotes("80m 더 돌지만 가로등 +4개", List.of(festival), List.of()))
                .noneMatch(n -> n.text().contains("피했습니다"));
        // 안전 경로도 지나는 공사는 피한 것이 아니다
        assertThat(ExplanationBuilder.eventNotes("80m 더 돌지만 가로등 +4개", List.of(work), List.of(work)))
                .noneMatch(n -> n.text().contains("피했습니다"));
        assertThat(ExplanationBuilder.eventNotes("80m 더 돌지만 가로등 +4개", List.of(work), List.of()))
                .contains(RouteResult.Note.info("event", "금남로 보도 공사 둘레도 피했습니다"));
    }

    @Test
    @DisplayName("주의 알림이 있으면 가장 안전한 길이라 하지 않고 정보 알림은 그대로 둔다")
    void sameRouteIsNotCalledSafestWhenThereIsAWarning() {
        // 주의는 warn, 정보는 info
        var warn = List.of(com.saferoute.route.api.RouteResult.Note.warn("event", "축제 구간을 지납니다"));
        var info = List.of(com.saferoute.route.api.RouteResult.Note.info("event", "축제 구간을 지납니다"));
        assertThat(ExplanationBuilder.withWarnings("가장 짧은 길이 가장 안전한 길입니다", warn)).isEqualTo("가장 짧은 길로 갑니다");
        assertThat(ExplanationBuilder.withWarnings("가장 짧은 길이 가장 안전한 길입니다", info))
                .isEqualTo("가장 짧은 길이 가장 안전한 길입니다");
    }

    @Test
    @DisplayName("휠체어 낮에 같은 길이면 지날 수 있는 길 중 가장 짧은 길이라고 말한다")
    void wheelchairSaysItIsTheShortestPassableRouteInDaytime() {
        RouteProperties.Profile wheel = TestRoutes.props().profile("wheelchair");
        Conditions day = new Conditions(false, "auto", null);
        assertThat(ExplanationBuilder.text(day, wheel, wheel.day(), true, false, 0, 0, Map.of()))
                .isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다");
    }

    @Test
    @DisplayName("같은 길이면 가장 짧은 길이 가장 안전하다 하고 한도에 걸리면 너무 돌아서라 말한다")
    void sameRouteAndCappedRoute() {
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), true, false, 0, 0, Map.of())).isEqualTo("가장 짧은 길이 가장 안전한 길입니다");
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), true, true, 0, 0, Map.of()))
                .isEqualTo("더 안전한 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다");
    }

    @Test
    @DisplayName("시설 차이가 같으면 정해진 순서(CCTV, 비상벨, 도움 지점)로 말한다")
    void tiesFollowFixedOrder() {
        String text = ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 90, 1, Map.of(
                FacilityGroup.HELP, 2, FacilityGroup.BELL, 2, FacilityGroup.CCTV, 2));

        assertThat(text).isEqualTo("90m(1분) 더 돌지만 CCTV +2대, 비상벨 +2개, 도움 지점 +2곳");
    }

    @Test
    @DisplayName("시설 묶음마다 단위(개·대·곳)를 붙인다")
    void unitsPerGroup() {
        assertThat(FacilityGroup.LIGHT.gain(3)).isEqualTo("가로등 +3개");
        assertThat(FacilityGroup.CCTV.gain(3)).isEqualTo("CCTV +3대");
        assertThat(FacilityGroup.BELL.gain(3)).isEqualTo("비상벨 +3개");
        assertThat(FacilityGroup.HELP.gain(3)).isEqualTo("도움 지점 +3곳");
    }

    @Test
    @DisplayName("골목길 때문에 돌았으면 줄인 골목길 길이로 말한다")
    void wheelchairSaysHowMuchAlleyItCut() {
        // 골목길 기준 (2026-11-12): 돌아간 이유가 골목길이면 줄인 길이로 말한다
        RouteProperties.Profile wheel = TestRoutes.props().profile("wheelchair");
        Conditions day = new Conditions(false, "auto", null);
        ExplanationBuilder.Avoided none = ExplanationBuilder.Avoided.NONE;

        assertThat(ExplanationBuilder.text(day, wheel, wheel.day(), false, false, 96, 2, Map.of(), none, 0, 64))
                .isEqualTo("96m(2분) 더 돌지만 큰 길로 가서 골목길을 64m 줄여요");
        assertThat(ExplanationBuilder.text(day, wheel, wheel.day(), false, false, 96, 2, Map.of(),
                new ExplanationBuilder.Avoided(0, 0, true, false), 0, 64))
                .isEqualTo("96m(2분) 더 돌지만 비포장을 피하고 큰 길로 가서 골목길을 64m 줄여요");
        // 밤: 시설 차이 뒤에 붙인다
        assertThat(ExplanationBuilder.text(NIGHT, wheel, wheel.night(), false, false, 96, 2,
                Map.of(FacilityGroup.LIGHT, 12), none, 0, 64))
                .isEqualTo("96m(2분) 더 돌지만 가로등 +12개, 골목길 -64m");
        // 골목길 기준을 쓰지 않는 모드는 말하지 않는다
        assertThat(ExplanationBuilder.text(NIGHT, ADULT, ADULT.night(), false, false, 96, 2,
                Map.of(FacilityGroup.LIGHT, 12), none, 0, 64))
                .isEqualTo("96m(2분) 더 돌지만 가로등 +12개");
    }

    @Test
    @DisplayName("노인 낮 설명은 쉼 곳을 먼저, 줄인 골목길을 뒤에 말한다")
    void seniorSaysRestFirstThenAlley() {
        // 노인 낮 (2026-11-12, 진행표 w22): 쉼 곳(비중 4)을 먼저, 줄인 골목길을 뒤에. 쉼 곳 차이가 없으면 휠체어와 같은 문장
        RouteProperties.Profile senior = TestRoutes.props().profile("senior");
        Conditions day = new Conditions(false, "auto", null);
        ExplanationBuilder.Avoided none = ExplanationBuilder.Avoided.NONE;

        assertThat(ExplanationBuilder.text(day, senior, senior.day(), false, false, 17, 1, Map.of(FacilityGroup.REST, 2), none, 0, 232))
                .isEqualTo("17m(1분) 더 돌지만 쉼 곳 +2곳, 골목길 -232m");
        assertThat(ExplanationBuilder.text(day, senior, senior.day(), false, false, 17, 1, Map.of(), none, 0, 232))
                .isEqualTo("17m(1분) 더 돌지만 큰 길로 가서 골목길을 232m 줄여요");
        assertThat(ExplanationBuilder.text(day, senior, senior.day(), false, false, 17, 1, Map.of(),
                new ExplanationBuilder.Avoided(1, 0, false, false), 0, 232))
                .isEqualTo("17m(1분) 더 돌지만 계단을 피하고 큰 길로 가서 골목길을 232m 줄여요");
    }

    @Test
    @DisplayName("출발·도착 근처 알림은 종류 이름과 받침에 맞는 조사를 붙인다")
    void nearNotesNameTheKindWithTheRightParticle() {
        // 행사가 아닌 종류, 받침, 여러 건
        List<RouteResult.Event> safe = List.of(
                new RouteResult.Event("공사", "무진대로 공사", null, null, null, 0, 50, false, "start"),
                new RouteResult.Event("재난", "금남로 침수", null, null, null, 0, 50, false, "end"),
                new RouteResult.Event("행사", "충장축제", null, null, null, 0, 50, false, "both"),
                new RouteResult.Event("행사", "비엔날레 콘서트", null, null, null, 0, 50, false, "both"));

        assertThat(ExplanationBuilder.eventNotes("요약", List.of(), safe)).extracting(RouteResult.Note::text)
                .containsExactly("출발지 근처에 무진대로 공사가 있어요",
                        "도착지 근처에 금남로 침수 재난이 있어요",
                        "출발지와 도착지 근처에서 충장축제 행사가 열리고 있어요",
                        "출발지와 도착지 근처에서 비엔날레 콘서트 행사가 열리고 있어요");
    }

    @Test
    @DisplayName("신고가 셋 이상이면 둘과 '외 ○곳'으로 줄이고 받침에 맞는 조사를 붙인다")
    void manyReportsGetTheRightParticle() {
        List<RouteResult.Event> passing = java.util.stream.Stream.of("공사", "턱", "사고")
                .map(t -> new RouteResult.Event("신고", t, null, null, null, 0, 10, true, null)).toList();
        // "외 1곳" 끝 글자 "곳"에 받침이 있어 "이"
        assertThat(ExplanationBuilder.eventNotes("", List.of(), passing))
                .containsExactly(RouteResult.Note.warn("event", "앞에 공사 신고, 턱 신고 외 1곳이 있는 곳을 지나요"));
    }

    @Test
    @DisplayName("지하 길을 지나면 들어가는 출구·지나는 곳·나오는 출구를 차례로 알린다")
    void undergroundNoteNamesTheWayInAndOut() {
        // 지하 길이 없으면 알리지 않는다 (2026-11-09)
        double[] p = {126.93, 35.14};
        RoadEdge link = new RoadEdge(1, 1, 2, 30, "footway", List.of(p, p), EdgePenalty.NONE);
        RoadEdge stairs = new RoadEdge(2, 2, 3, 68, "steps", List.of(p, p), TestRoutes.UNDER, null, "문화전당역 3번 출구 계단");
        RoadEdge hall1 = new RoadEdge(3, 3, 4, 93, "corridor", List.of(p, p), TestRoutes.UNDER, null, "금남지하상가");
        RoadEdge hall2 = new RoadEdge(4, 4, 5, 93, "corridor", List.of(p, p), TestRoutes.UNDER, null, "금남지하상가");
        RoadEdge lift = new RoadEdge(5, 5, 6, 65, "elevator", List.of(p, p), TestRoutes.UNDER, null, "금남로4가역 1번 출구 엘리베이터");

        assertThat(ExplanationBuilder.undergroundNote(new RoadPath(List.of(link, stairs, hall1, hall2, lift), List.of(p), 349)))
                .contains(RouteResult.Note.info("underground",
                        "지하로 지납니다: 문화전당역 3번 출구(계단) → 금남지하상가 → 금남로4가역 1번 출구(엘리베이터)"));
        assertThat(ExplanationBuilder.undergroundNote(new RoadPath(List.of(link), List.of(p), 30))).isEmpty();
    }
}
