package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.json.JsonMapper;

/** 개인화 시험(진행표 c22): 집을 모르는 사람의 "집에 가자", 장소 후보 순서 */
class PlaceOrderHomeTests {

    static final String NEW = "00000000-0000-4000-8000-00000000c022";
    static final double LAT = 35.1480;
    static final double LON = 126.9260;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final TripRecommender trips = mock(TripRecommender.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    AssistantService service;

    @BeforeEach
    void setUp() {
        AssistantTools tools = new AssistantTools(kakao, trips, mock(FacilityRepository.class), memory,
                mock(ReportService.class), mock(WeatherDustService.class), new NightJudge());
        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)),
                new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json), tools, json, clock);
        Trip walk = new Trip("walk", 12, 700, 0, 0, "걸어서 12분, 700m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
    }

    AssistantReply ask(String text, List<AssistantRequest.Favorite> favorites) {
        return service.answer(new AssistantRequest(NEW, text, LAT, LON, null, null, null, favorites, null, "adult"));
    }

    static String question(AssistantReply r) {
        return r.ask() != null ? r.ask() : r.say();
    }

    @Test
    void homeNotSavedAsksToSaveInsteadOfShowingShops() {
        when(kakao.search(eq("집"), any(), any())).thenReturn(List.of(
                new Place("1", "하남돼지집 충장점", "광주 동구", 35.147, 126.919, 300),
                new Place("2", "해뜨는집", "광주 동구", 35.148, 126.920, 400)));
        AssistantReply r = ask("집에 가자", null);
        assertThat(question(r)).contains("집을 아직 몰라요", "자주 가는 곳에 집을 저장").doesNotContain("돼지집", "해뜨는집");
        assertThat(r.choices() == null ? List.<AssistantReply.Choice>of() : r.choices()).isEmpty();
        verify(kakao, never()).search(eq("집"), any(), any());
        verify(trips, never()).recommend(any());
    }

    @Test
    void savedHomeIsUsedAsBefore() {
        ask("집에 가자", List.of(new AssistantRequest.Favorite("집", 35.160, 126.880)));
        verify(trips).recommend(argThat(a -> a.dest().lat() == 35.160));
        verify(kakao, never()).search(eq("집"), any(), any());
    }

    @Test
    void parkingLotGoesAfterTheRealPlace() {
        when(kakao.search(eq("국립아시아문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당 부설주차장", "광주 동구", 35.1460, 126.9200, 100),
                new Place("2", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, 200),
                new Place("3", "카페 전당앞", "광주 동구", 35.1468, 126.9210, 210)));
        // 11-13: "문화전당"은 국립아시아문화전당으로 바꿔 찾고, 이름이 똑같은 곳이 있어 주차장이 아니라 그곳으로 바로 감
        ask("문화전당 가자", null);
        verify(trips).recommend(argThat(a -> a.dest().lat() == 35.1467));
    }

    @Test
    void sameNameMarketWinsOverChainBranchAndCafe() {
        when(kakao.search(eq("남광주시장"), any(), any())).thenReturn(List.of(
                new Place("1", "세븐일레븐 남광주시장점", "광주 동구", 35.1400, 126.9280, 80),
                new Place("2", "카페 시장골목", "광주 동구", 35.1401, 126.9281, 90),
                new Place("3", "남광주시장", "광주 동구", 35.1402, 126.9283, 120)));
        ask("남광주시장 가야 돼", null);
        verify(trips).recommend(argThat(a -> a.dest().lat() == 35.1402));
    }

    @Test
    void rankOrder() {
        assertThat(AssistantTools.rank("남광주시장", "남광주시장")).isEqualTo(0);
        assertThat(AssistantTools.rank("국립아시아문화전당", "문화전당")).isEqualTo(1);
        assertThat(AssistantTools.rank("국립아시아문화전당 부설주차장", "문화전당")).isEqualTo(2);
        assertThat(AssistantTools.rank("세븐일레븐 남광주시장점", "남광주시장")).isEqualTo(2);
        assertThat(AssistantTools.rank("카페 시장골목", "남광주시장")).isEqualTo(3);
        // 주차장을 찾으면 주차장이 딸린 곳이 아니다
        assertThat(AssistantTools.rank("문화전당 주차장", "문화전당 주차장")).isEqualTo(0);
        assertThat(AssistantTools.rank("조선대학교병원", "조선대")).isEqualTo(1);
    }
}
