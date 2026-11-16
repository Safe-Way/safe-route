package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.report.ReportService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.transit.TransitResult;

/** 11-11 학교 서버 비서 점검 35/39 뒤 고친 것 (6·11·18번) */
class EvalFix1111Tests {

    @Test
    void eval6AmbiguousElevatorIsAskedByRulesBeforeGemini() {
        AssistantTools tools = new AssistantTools(mock(KakaoLocalClient.class), mock(TripRecommender.class),
                mock(FacilityRepository.class), mock(MemoryRepository.class), mock(ReportService.class),
                mock(WeatherDustService.class), new NightJudge());
        RuleBrain rules = new RuleBrain(tools, mock(MemoryRepository.class), mock(UnmetRepository.class));
        AssistantRequest req = new AssistantRequest(null, "엘베 있는 데로 가자", 35.148, 126.926, null, null, null, null,
                null, "wheelchair");
        Turn t = new Turn(req, Conversation.start(), LocalDateTime.of(2026, 11, 11, 12, 0), false, null);
        // noTool 은 제미나이보다 먼저 돈다
        Outcome o = rules.noTool(t);
        assertThat(o).isNotNull();
        assertThat(o.ask()).contains("지하철", "육교", "?");
        assertThat(t.convo.asked()).isEqualTo("elevator");
        // 육교·역을 말했으면 되묻지 않는다
        assertThat(RuleBrain.elevatorAmbiguous("엘베있는육교로가자")).isFalse();
        assertThat(RuleBrain.elevatorAmbiguous("엘리베이터있는지하철역으로가줘")).isFalse();
        assertThat(RuleBrain.elevatorAmbiguous("엘베있는육교도보여주고CCTV는빼줘")).isFalse();
    }

    @Test
    void eval11SameToolTwiceInARowIsListedOnce() {
        assertThat(AssistantService.called(List.of("search_place", "search_place", "recommend_trip")))
                .containsExactly("search_place", "recommend_trip");
        assertThat(AssistantService.called(List.of("report_problem:confirm", "get_my_info", "report_problem")))
                .containsExactly("report_problem", "get_my_info", "report_problem");
    }

    @Test
    void eval18NightTransitSaysTheWalkToTheStopIsBright() {
        List<TransitResult.Leg> legs = List.of();
        assertThat(AssistantService.mustSayTransit("집까지 18분 걸려요.", legs, List.of(TripScorer.NIGHT_STOP_BRIGHT)))
                .isEqualTo("집까지 18분 걸려요. " + TripScorer.NIGHT_STOP_SAY);
        assertThat(TripScorer.NIGHT_STOP_SAY).contains("밝은", "가로등");
        // 밝지 않으면(이유가 없으면) 말하지 않는다
        assertThat(AssistantService.mustSayTransit("집까지 18분 걸려요.", legs, List.of())).isEqualTo("집까지 18분 걸려요.");
    }

    @Test
    void eval13BodyOnlyIsRememberedAndAsksWhereBeforeGemini() {
        AssistantTools tools = new AssistantTools(mock(KakaoLocalClient.class), mock(TripRecommender.class),
                mock(FacilityRepository.class), mock(MemoryRepository.class), mock(ReportService.class),
                mock(WeatherDustService.class), new NightJudge());
        RuleBrain rules = new RuleBrain(tools, mock(MemoryRepository.class), mock(UnmetRepository.class));
        AssistantRequest req = new AssistantRequest(null, "오늘 무릎이 아파", 35.148, 126.926, null, null, null, null,
                null, "senior");
        Turn t = new Turn(req, Conversation.start(), LocalDateTime.of(2026, 11, 12, 9, 55), false, null);
        Outcome o = rules.noTool(t);
        assertThat(o).isNotNull();
        assertThat(o.ask()).contains("쉴 곳", "어디로");
        assertThat(t.convo.bodyNote()).isEqualTo("오늘 무릎이 아파");
        assertThat(t.convo.asked()).isEqualTo("destination");
        // 휠체어로 바꾸지 않는다
        assertThat(t.convo.mode()).isNull();
        // 찾는 곳·가실 곳·다친 말이 같이 있으면 이 규칙이 아님
        assertThat(RuleBrain.bodyOnly("배아파화장실어디야")).isFalse();
        assertThat(RuleBrain.bodyOnly("다리아파서문화전당가자")).isFalse();
        assertThat(RuleBrain.bodyOnly("다리를다쳐서휠체어타고다녀")).isFalse();
        assertThat(RuleBrain.bodyOnly("오늘좀피곤해")).isTrue();
    }

    @Test
    void eval18NightTransitThatIsNotBrighterSaysTheDarkLengthAndHowToGetHelp() {
        String dark = TripScorer.nightStopDark(734);
        assertThat(dark).isEqualTo("밤이라 정류장까지 가는 길에 어두운 곳이 약 730m 있어요");
        List<TransitResult.Leg> legs = List.of();
        assertThat(AssistantService.mustSayTransit("집까지 25분 걸려요.", legs, List.of(dark, "걷기보다 18분 빨라요")))
                .isEqualTo("집까지 25분 걸려요. " + dark + ".");
        // 이미 말했으면 또 붙이지 않는다
        assertThat(AssistantService.mustSayTransit("어두운 곳이 좀 있어요.", legs, List.of(dark)))
                .isEqualTo("어두운 곳이 좀 있어요.");
        assertThat(Sentences.scared("집에 갈 건데 밤이라 무서워")).isTrue();
        assertThat(Sentences.scared("집에 갈래")).isFalse();
        assertThat(Sentences.SCARED_TIP).contains("도와줘");
        // '도와줘'는 급한 말 규칙이 받는다
        assertThat(RuleBrain.urgent("도와줘")).isTrue();
    }

    @Test
    void eval2WheelchairWhyIsSaidOnlyOnce() {
        // 11-12 기록: 제미나이가 앞 문장만 말했는데 서버가 두 문장을 다 붙여 "…빼고 골랐어요"가 두 번 나옴
        AssistantReply.Pick walk = new AssistantReply.Pick("walk", java.util.Map.of(), "걸어서 23분", 23, "safe");
        String said = "국립아시아문화전당까지 23분 걸려요. 휠체어가 못 지나는 것으로 확인된 길은 빼고 골랐어요. "
                + "지난번 이런 날씨에도 걸어가셨어요.";
        String out = AssistantService.mustSay(said, "wheelchair", walk);
        assertThat(out).isEqualTo("국립아시아문화전당까지 23분 걸려요. 지난번 이런 날씨에도 걸어가셨어요. "
                + AssistantService.WHEELCHAIR_WHY);
        assertThat(out.split("못 지나는", -1)).hasSize(2); // 한 번만
        // 두 문장을 다 말했으면 그대로
        String full = "23분 걸려요. " + AssistantService.WHEELCHAIR_WHY;
        assertThat(AssistantService.mustSay(full, "wheelchair", walk)).isEqualTo(full);
    }

    @Test
    void eval11SeniorAnswerAlwaysTellsHowToFindARestPlace() {
        AssistantReply.Pick transit = new AssistantReply.Pick("transit", java.util.Map.of(), "16분", 16, null);
        AssistantReply.Pick walk = new AssistantReply.Pick("walk", java.util.Map.of(), "걸어서 12분", 12, "safe");
        assertThat(AssistantService.mustSay("남광주시장까지 16분 걸려요.", "senior", transit))
                .isEqualTo("남광주시장까지 16분 걸려요. " + AssistantService.SENIOR_REST_TRANSIT);
        assertThat(AssistantService.mustSay("12분 걸려요.", "senior", walk)).contains("쉬어 가세요");
        // 이미 말했거나 어르신이 아니면 그대로
        assertThat(AssistantService.mustSay("쉴 곳이 3곳 있어요.", "senior", walk)).isEqualTo("쉴 곳이 3곳 있어요.");
        assertThat(AssistantService.mustSay("16분 걸려요.", "adult", transit)).isEqualTo("16분 걸려요.");
        assertThat(AssistantService.mustSay("어디로 가세요?", "senior", null)).isEqualTo("어디로 가세요?");
    }
}
