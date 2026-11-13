package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.Facility;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.place.PlaceAlias;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 11-13 현장 시험: 문화전당 앞 엘리베이터, 금남지하상가가 가게들에 밀림, 문화전당 → 문화전당오피스텔 */
class FieldTest1113Tests {

    static final String A = "00000000-0000-4000-8000-00000000de18";
    // 조선대 (금남지하상가까지 약 1.3km)
    static final double LAT = 35.1405;
    static final double LON = 126.9310;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final FacilityRepository facilities = mock(FacilityRepository.class);
    final TripRecommender trips = mock(TripRecommender.class);
    final AssistantService service;

    FieldTest1113Tests() {
        MemoryRepository memory = mock(MemoryRepository.class);
        AssistantTools tools = new AssistantTools(kakao, trips, facilities,
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-13T04:40:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(kakao.search(any(), isNull(), isNull())).thenReturn(List.of());
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, null, null, "adult"));
    }

    @Test
    void elevatorNearANamedPlaceIsSearchedThere() {
        when(kakao.search(eq("국립아시아문화전당"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구 문화전당로 38", 35.1467, 126.9200, 1200)));
        when(facilities.near(eq(35.1467), eq(126.9200), eq("elevator"), eq(false), any(Integer.class)))
                .thenReturn(List.of(new Facility(7, "subway_entrance", null, "문화전당역 4번 출구", null, 35.1470, 126.9205,
                        153, null, null, true, 1, List.of(), null)));
        AssistantReply r = ask("문화전당 앞에 엘리베이터 어디야");
        assertThat(r.say()).contains("국립아시아문화전당 근처에서 찾았어요", "문화전당역 4번 출구", "153m");
        assertThat(r.place().lat()).isEqualTo(35.1470);
    }

    @Test
    void anchorWords() {
        assertThat(RuleBrain.anchorBefore("문화전당 앞에 엘리베이터 어디야")).isEqualTo("문화전당");
        assertThat(RuleBrain.anchorBefore("금남지하상가 근처에 있는 엘베")).isEqualTo("금남지하상가");
        assertThat(RuleBrain.anchorBefore("근처 엘리베이터")).isNull();
        assertThat(RuleBrain.anchorBefore("내 근처 엘리베이터")).isNull();
        assertThat(RuleBrain.anchorBefore("여기 주변 엘베")).isNull();
    }

    @Test
    void exactNamePushedOutByShopsComesFirst() {
        // 가까운 순 10곳은 '금남지하상가'가 든 가게들뿐, 진짜 금남지하상가는 정확도 순에서만 나옴
        when(kakao.search(eq("금남지하상가"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("2", "올포유 광주금남지하점", "광주 동구 금남로 지하", 35.1480, 126.9180, 1300),
                new Place("3", "지센 광주금남로점", "광주 동구 금남로", 35.1481, 126.9181, 1310)));
        when(kakao.search(eq("금남지하상가"), isNull(), isNull())).thenReturn(List.of(
                new Place("9", "금남지하상가", "광주 동구 금남로 186", 35.1490, 126.9160, null)));
        // 한 곳으로 정해져 길찾기까지 감 (길찾기는 가짜라 비어 있음)
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> ask("금남 지하상가 가자"));
        org.mockito.ArgumentCaptor<TripRecommender.Ask> asked = org.mockito.ArgumentCaptor.forClass(TripRecommender.Ask.class);
        org.mockito.Mockito.verify(trips).recommend(asked.capture());
        assertThat(asked.getValue().toString()).contains("금남지하상가", "35.149");
    }

    @Test
    void aliases() {
        assertThat(PlaceAlias.of("문화전당")).isEqualTo("국립아시아문화전당");
        assertThat(PlaceAlias.of("금남 지하상가")).isEqualTo("금남지하상가");
        assertThat(PlaceAlias.of("금남지하도상가")).isEqualTo("금남지하상가");
        assertThat(PlaceAlias.of("충금지하상가")).isEqualTo("충금지하상가");
    }
}
