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
}
