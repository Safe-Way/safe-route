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
    final AssistantTools tools;
    final AssistantService service;

    FieldTest1113Tests() {
        MemoryRepository memory = mock(MemoryRepository.class);
        tools = new AssistantTools(kakao, trips, facilities,
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

    @Test
    void busStopNearANamedPlace() {
        com.saferoute.bus.BusStopRepository stops = mock(com.saferoute.bus.BusStopRepository.class);
        tools.setBusStops(stops);
        when(kakao.search(eq("광주송정역"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "광주송정역", "광주 광산구 상무대로 201", 35.1378, 126.7934, 12000)));
        when(stops.near(35.1378, 126.7934, 1000, 1)).thenReturn(List.of(
                new com.saferoute.bus.BusStop(6001, "광주송정역", "송정공원", 35.1381, 126.7930, 40)));
        AssistantReply r = ask("송정역 근처 정류장 어디야");
        assertThat(r.say()).contains("광주송정역 근처에서 찾았어요", "가장 가까운 버스 정류장은 광주송정역 정류장으로 40m예요");
    }

    @Test
    void busLineAndBusToAPlace() {
        com.saferoute.bus.BusLineRepository lines = mock(com.saferoute.bus.BusLineRepository.class);
        tools.setBusLines(lines);
        when(lines.find("03")).thenReturn(List.of(new com.saferoute.bus.BusLine(103, "03", "수완지구", "조선대",
                java.time.LocalTime.of(5, 40), java.time.LocalTime.of(22, 20), 15, 35.19, 126.82)));
        assertThat(ask("03번 버스 어디로 가?").say())
                .isEqualTo("03번은 수완지구 ↔ 조선대 · 첫차 05:40 막차 22:20 · 15분마다예요.");
        when(lines.endingAt("수완")).thenReturn(List.of(new com.saferoute.bus.BusLine(103, "03", "수완지구", "조선대",
                null, null, null, null, null)));
        assertThat(ask("수완동으로 가는 버스 알려줘").say()).startsWith("수완동으로 가는 버스는 03번(수완지구 ↔ 조선대)이 있어요.")
                .contains("'수완동 가자'");
        // 종점인 노선이 없으면 택시 권하기
        when(lines.endingAt("양림")).thenReturn(List.of());
        when(kakao.search(eq("택시승강장"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("5", "조선대 택시승강장", "광주 동구", 35.141, 126.932, 150)));
        assertThat(ask("양림동 가는 버스").say()).contains("양림동이 처음이나 끝 정류장인 버스는 없어요", "아직 준비 중", "택시를 추천해요",
                "조선대 택시승강장");
    }

    @Test
    void busPassingAPlace() {
        com.saferoute.bus.BusLineRepository lines = mock(com.saferoute.bus.BusLineRepository.class);
        com.saferoute.bus.BusLineStops passing = mock(com.saferoute.bus.BusLineStops.class);
        tools.setBusLines(lines);
        tools.setLineStops(passing);
        when(kakao.search(eq("수완동"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("9", "수완동", "광주 광산구 수완동", 35.19, 126.82, 9000)));
        com.saferoute.bus.BusLine l = new com.saferoute.bus.BusLine(28, "03", "첨단공영차고지", "조선대", null, null,
                null, null, null);
        when(passing.to("수완동", 35.19, 126.82)).thenReturn(List.of(new com.saferoute.bus.BusLineStops.Hit(l,
                new com.saferoute.bus.LineStop(20, 501, "수완지구", 35.191, 126.821))));
        assertThat(ask("수완동으로 가는 버스").say())
                .startsWith("수완동으로 가는 버스는 03번(수완지구를 지나요, 첨단공영차고지 ↔ 조선대)이 있어요.");
    }
}
