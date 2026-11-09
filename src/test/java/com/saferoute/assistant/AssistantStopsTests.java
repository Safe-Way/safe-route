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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 비서에게 "다이소 들르고 택배 찾고 집 갈래" (규칙만, 제미나이 키 없음) */
class AssistantStopsTests {

    static final String A = "00000000-0000-4000-8000-00000000de02";
    static final double LAT = 35.1480;
    static final double LON = 126.9260;
    static final List<AssistantRequest.Favorite> HOME = List.of(new AssistantRequest.Favorite("집", 35.1480, 126.9100));

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final RouteService routes = mock(RouteService.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantService service;

    AssistantStopsTests() {
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, routes, mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(eq("다이소"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "다이소 충장로점", "광주 동구 충장로", 35.1481, 126.9150, 100)));
        when(kakao.search(eq("편의점"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("3", "GS25 충장점", "광주 동구", 35.1479, 126.9200, 80)));
        when(kakao.search(eq("약국"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("5", "온누리약국", "광주 동구", 35.1482, 126.9180, 80)));
        when(routes.route(any())).thenAnswer(inv -> {
            RouteRequest r = inv.getArgument(0);
            int m = AssistantTools.straightM(r.origin().lat(), r.origin().lon(), r.destination().lat(),
                    r.destination().lon());
            RouteResult.Route safe = new RouteResult.Route("safe", m, (int) Math.ceil(m / 50.0), List.of(), Map.of(),
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            return new RouteResult(null, List.of(safe), null, List.of(), null);
        });
    }

    AssistantReply ask(String text, String convo) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, convo, null, HOME, null, "adult"));
    }

    @Test
    void errandsOnTheWayHome() {
        AssistantReply r = ask("퇴근길에 다이소 들르고 택배 찾고 집 갈래", null);
        assertThat(r.tools()).contains("plan_stops");
        assertThat(r.usage()).isEqualTo(AssistantReply.Usage.NONE); // 규칙만 썼으면 제미나이 토큰 0
        assertThat(r.say()).contains("들렀다 집까지", "분 걸려요", "바로 가는 것보다", "더 걸려요", "GS25 충장점(택배)");
        assertThat(r.stops()).extracting(AssistantReply.Stop::name).containsExactly("GS25 충장점", "다이소 충장로점");
        assertThat(r.say()).contains("순서를 바꾸면", "GS25 충장점부터");
        assertThat(r.pick().type()).isEqualTo("walk");
        assertThat(r.pick().request()).containsKey("stops");
        assertThat(r.pick().summary()).contains("GS25 충장점(택배) → 다이소 충장로점");
    }

    @Test
    void asksWhereToAfterTheStops() {
        AssistantReply first = ask("다이소 들르고 약국 들러", null);
        assertThat(first.say()).contains("어디로 가세요");
        assertThat(first.pick()).isNull();
        AssistantReply second = ask("집", first.conversationId());
        assertThat(second.tools()).contains("plan_stops");
        assertThat(second.stops()).hasSize(2);
        assertThat(second.say()).contains("들렀다 집까지");
    }

    @Test
    void fourPlacesAreTooMany() {
        AssistantReply r = ask("다이소 들르고 약국 들르고 빵집 들르고 꽃집 들렀다 집 가자", null);
        assertThat(r.say()).contains("세 곳까지");
        assertThat(r.tools()).doesNotContain("plan_stops");
    }
}
