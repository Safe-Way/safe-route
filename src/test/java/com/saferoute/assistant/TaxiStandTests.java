package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 11-13 "택시 타는 곳": 카카오에 등록된 택시 승강장 중 가장 가까운 곳 (맥 터미널 확인: 광주송정역 근처 2곳) */
class TaxiStandTests {

    static final String A = "00000000-0000-4000-8000-00000000de16";
    // 광주송정역 앞
    static final double LAT = 35.1378;
    static final double LON = 126.7934;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantService service;

    TaxiStandTests() {
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-13T01:15:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(kakao.search(eq("택시승강장"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "광주송정역 택시승강장", "광주 광산구 송정동", 35.1379, 126.7900, 325)));
        when(kakao.search(eq("택시"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("2", "광주송정역 호출택시승차장", "광주 광산구 송정동", 35.1385, 126.7920, 204),
                new Place("3", "○○운수 택시회사", "광주 광산구", 35.14, 126.80, 150)));
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, null, null, "senior"));
    }

    @Test
    void nearestTaxiStandIsShown() {
        for (String said : List.of("택시 타는 곳 어디야", "근처 택시 승강장", "가까운 택시 승강장 알려줘")) {
            AssistantReply r = ask(said);
            assertThat(r.say()).as(said).contains("가장 가까운 택시 승강장은 광주송정역 택시승강장으로 325m예요.");
            assertThat(r.place().kind()).isEqualTo("taxi");
            assertThat(r.place().lat()).isEqualTo(35.1379);
        }
    }

    @Test
    void plainTaxiWordIsUsedWhenNoStandIsNamedThatWay() {
        when(kakao.search(eq("택시승강장"), anyDouble(), anyDouble())).thenReturn(List.of());
        AssistantReply r = ask("택시 타는 곳");
        assertThat(r.say()).contains("광주송정역 호출택시승차장으로 204m예요."); // 택시 회사는 빼고
    }

    @Test
    void noStandNearbySaysSoHonestly() {
        when(kakao.search(eq("택시승강장"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("9", "먼 택시승강장", "광주", 35.20, 126.90, 9000)));
        when(kakao.search(eq("택시"), anyDouble(), anyDouble())).thenReturn(List.of());
        AssistantReply r = ask("택시 타는 곳");
        assertThat(r.say()).contains("2km 안에 등록된 택시 승강장을 찾지 못했어요");
        assertThat(r.place()).isNull();
    }

    @Test
    void callingATaxiIsStillSomethingWeCannotDo() {
        // 재휘 점검 32번: "택시 불러 줘"는 못 하는 일 + 버스 안내 그대로
        AssistantReply r = ask("택시 불러 줘");
        assertThat(r.say()).contains("못 해요", "버스");
        assertThat(RuleBrain.taxiStandAsked("택시불러줘")).isFalse();
        assertThat(AssistantTools.taxiStand("○○운수 택시회사")).isFalse();
    }
}
