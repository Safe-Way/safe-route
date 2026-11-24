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
import java.time.LocalDate;
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
import com.saferoute.subway.SubwayExitQuery;
import com.saferoute.subway.SubwayRepository;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 지하철 "몇 번 출구로 나가요?" 답하기 (11-23) */
class SubwayExitTests {

    static final String A = "00000000-0000-4000-8000-00000000e817";
    static final double LAT = 35.1405;
    static final double LON = 126.9310;
    static final LocalDate Y2021 = LocalDate.of(2021, 3, 9);

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final SubwayRepository subway = mock(SubwayRepository.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantTools tools;
    final AssistantService service;

    SubwayExitTests() {
        tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));
        tools.setSubway(subway);
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-23T04:40:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(kakao.search(any(), isNull(), isNull())).thenReturn(List.of());
    }

    AssistantReply ask(String text, String mode) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, null, null, mode));
    }

    @Test
    void readsTheQuestion() {
        assertThat(SubwayExitQuery.read("문화전당역에서 국립아시아문화전당 가려면 몇 번 출구로 나가요?"))
                .isEqualTo(new SubwayExitQuery.Ask("문화전당", "국립아시아문화전당", null, null));
        assertThat(SubwayExitQuery.read("광주시청 가려면 몇번 출구야"))
                .isEqualTo(new SubwayExitQuery.Ask(null, "광주시청", null, null));
        assertThat(SubwayExitQuery.read("문화전당역 5번 출구에 뭐 있어?"))
                .isEqualTo(new SubwayExitQuery.Ask("문화전당", null, "5", null));
        assertThat(SubwayExitQuery.read("상무역에서 28번 버스 타려면 어느 출구로 나가?"))
                .isEqualTo(new SubwayExitQuery.Ask("상무", null, null, "28"));
        assertThat(SubwayExitQuery.read("충장로로 가려면 문화전당역 몇 번 출구야"))
                .isEqualTo(new SubwayExitQuery.Ask("문화전당", "충장로", null, null));
        assertThat(SubwayExitQuery.read("남광주역 몇 번 출구로 나가야 돼"))
                .isEqualTo(new SubwayExitQuery.Ask("남광주", null, null, null));
        // 출구 질문이 아님
        assertThat(SubwayExitQuery.read("문화전당 가자")).isNull();
        assertThat(SubwayExitQuery.read("비상구 출구 막혔어")).isNull();
    }

    @Test
    void placeFromTheExitTable() {
        when(subway.exitPlaces("국립아시아문화전당", "문화전당", 3)).thenReturn(List.of(new SubwayRepository.ExitPlace(
                "문화전당(구도청)", "국립아시아문화전당", "5", 250, 4, Y2021)));
        AssistantReply r = ask("문화전당역에서 국립아시아문화전당 가려면 몇 번 출구로 나가요?", "adult");
        assertThat(r.say()).isEqualTo("국립아시아문화전당은 문화전당역 5번 출구로 나가시면 돼요(걸어서 약 4분)."
                + " 2021년 자료라 지금과 다를 수 있어요.");
        assertThat(r.tools()).contains("find_nearby");
        assertThat(r.pick()).isNull();
    }

    @Test
    void wheelchairHearsElevatorExits() {
        when(subway.exitPlaces("광주시청", null, 3)).thenReturn(List.of(
                new SubwayRepository.ExitPlace("상무", "광주시청", "3", 600, 9, Y2021),
                new SubwayRepository.ExitPlace("운천", "광주시청", "1", 900, 13, Y2021)));
        when(subway.elevatorExits("상무")).thenReturn(List.of("1", "5"));
        AssistantReply r = ask("광주시청 가려면 몇 번 출구야", "wheelchair");
        assertThat(r.say()).startsWith("광주시청은 상무역 3번 출구로 나가시면 돼요")
                .contains("운천역 1번 출구로도 갈 수 있어요.", "엘리베이터는 1·5번 출구에 있어요.");
    }

    @Test
    void notInTableUsesTheNearestEntrance() {
        when(kakao.search(eq("양림동펭귄마을"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "양림동펭귄마을", "광주 남구 천변좌로446번길", 35.1385, 126.9150, 1500)));
        when(subway.nearestEntrance(35.1385, 126.9150, AssistantTools.EXIT_NEAR_M)).thenReturn(
                new SubwayRepository.Entrance("남광주", "2", 35.1380, 126.9190, 380, null));
        AssistantReply r = ask("양림동펭귄마을 가려면 몇 번 출구로 나가?", "adult");
        assertThat(r.say()).isEqualTo("양림동펭귄마을에서 가장 가까운 지하철 출구는 남광주역 2번 출구예요(약 380m).");
    }

    @Test
    void exitPlacesBusesAndAskBack() {
        when(subway.placesAtExit("문화전당", "5", 3)).thenReturn(List.of(
                new SubwayRepository.ExitPlace("문화전당(구도청)", "국립아시아문화전당", "5", 250, 4, Y2021),
                new SubwayRepository.ExitPlace("문화전당(구도청)", "광주YMCA", "5", 300, 5, Y2021)));
        assertThat(ask("문화전당역 5번 출구에 뭐 있어?", "adult").say())
                .startsWith("문화전당역 5번 출구 근처에는 국립아시아문화전당, 광주YMCA가 있어요.");
        when(subway.exitBuses("상무", "28")).thenReturn(List.of(new SubwayRepository.ExitBus("상무", "4", "28", null),
                new SubwayRepository.ExitBus("상무", "6", "28", null)));
        assertThat(ask("상무역에서 28번 버스 타려면 어느 출구로 나가?", "adult").say())
                .startsWith("상무역에서 28번 버스는 4·6번 출구 쪽에서 타요.");
        AssistantReply back = ask("남광주역 몇 번 출구로 나가야 돼", "adult");
        assertThat(back.say()).contains("남광주역에서 어디 가시는지 함께 말씀해 주세요");
    }

    @Test
    void farPlaceSuggestsBusOrTaxi() {
        // 학교 DB: 녹동 1번 출구 지한초등학교 2000m, 31분
        when(subway.exitPlaces("지한초등학교", "녹동", 3)).thenReturn(List.of(
                new SubwayRepository.ExitPlace("녹동", "지한초등학교", "1", 2000, 31, Y2021)));
        assertThat(ask("녹동역에서 지한초등학교 가려면 몇 번 출구야", "adult").say())
                .isEqualTo("지한초등학교는 녹동역 1번 출구로 나가시면 돼요(걸어서 약 31분)."
                        + " 출구에서 꽤 멀어서 버스나 택시가 나을 수 있어요. 2021년 자료라 지금과 다를 수 있어요.");
    }

    @Test
    void stationName() {
        assertThat(AssistantTools.stationName("문화전당(구도청)")).isEqualTo("문화전당");
        assertThat(AssistantTools.stationName("상무")).isEqualTo("상무");
    }
}
