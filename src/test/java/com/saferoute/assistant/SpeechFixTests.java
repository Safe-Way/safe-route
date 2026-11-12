package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;


/** 11-12 음성 인식 ③ 받아쓰기 실수 고치기, ④ 장소 이름 느슨하게 찾기 */
class SpeechFixTests {

    @Test
    void commonTyposAreFixed() {
        assertThat(SpeechFix.fix("다이소 갓다가 집 갈래")).isEqualTo("다이소 갔다가 집 갈래");
        assertThat(SpeechFix.fix("약국 들럿다가 집 갈래")).isEqualTo("약국 들렀다가 집 갈래");
        assertThat(SpeechFix.fix("문화전당 갈께")).isEqualTo("문화전당 갈게");
        assertThat(StopReader.read(SpeechFix.fix("다이소 갓다가 집 갈래")).dest()).isEqualTo("집");
    }

    @Test
    void spokenHouseNumbersBecomeDigits() {
        assertThat(SpeechFix.fix("서석동 삼 번지로 가자")).isEqualTo("서석동 3로 가자");
        assertThat(SpeechFix.fix("서석동 삼번지로 가자")).isEqualTo("서석동 3로 가자");
        assertThat(SpeechFix.fix("충장로 사십오 가자")).isEqualTo("충장로 45 가자");
        assertThat(SpeechFix.fix("서석동 3번지로 가자")).isEqualTo("서석동 3로 가자");
        assertThat(RuleBrain.destination(SpeechFix.fix("서석동 삼 번지로 가자"))).isEqualTo("서석동 3");
        assertThat(SpeechFix.number("천이백삼십사")).isEqualTo(1234);
        assertThat(SpeechFix.number("백")).isEqualTo(100);
    }

    @Test
    void wordsThatOnlyLookLikeNumbersStay() {
        assertThat(SpeechFix.fix("다이소 갔다가 사거리 가자")).isEqualTo("다이소 갔다가 사거리 가자");
        assertThat(SpeechFix.fix("다이소 갔다가 이마트 가자")).isEqualTo("다이소 갔다가 이마트 가자");
        assertThat(SpeechFix.fix("충장로 이 가자")).isEqualTo("충장로 이 가자"); // "이" 하나는 조사일 수 있음
    }

    // ───────── ④ 장소 이름 ─────────

    static final String A = "00000000-0000-4000-8000-00000000de13";
    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
            memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
            new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));

    Turn turn(String text, List<AssistantRequest.Favorite> favs) {
        AssistantRequest req = new AssistantRequest(A, text, 35.1468, 126.9196, null, null, null, favs, null, "adult");
        return new Turn(req, Conversation.start(), java.time.LocalDateTime.of(2026, 11, 12, 17, 0), false, null);
    }

    @Test
    void spacedNameIsSearchedAgainWithoutSpaces() {
        when(kakao.search(eq("남광주 시장"), anyDouble(), anyDouble())).thenReturn(List.of());
        when(kakao.search(eq("남광주시장"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("20", "남광주시장", "광주 동구 학동", 35.1385, 126.9245, 1000)));
        AssistantTools.Found f = tools.searchPlace(turn("남광주 시장 가자", null), "남광주 시장");
        assertThat(f.status()).isEqualTo("found");
        assertThat(f.places().get(0).name()).isEqualTo("남광주시장");
    }

    @Test
    void nameOneLetterOffFromAFavoriteUsesTheFavorite() {
        when(kakao.search(eq("문화전딩"), anyDouble(), anyDouble())).thenReturn(List.of());
        List<AssistantRequest.Favorite> favs = List.of(new AssistantRequest.Favorite("문화전당", 35.1469, 126.9198));
        AssistantTools.Found f = tools.searchPlace(turn("문화전딩 가자", favs), "문화전딩");
        assertThat(f.status()).isEqualTo("found");
        assertThat(f.places().get(0).name()).isEqualTo("문화전당");
        assertThat(AssistantTools.editDistance("문화전딩", "문화전당")).isEqualTo(1);
    }

    @Test
    void favoriteMatchesEvenWithDifferentSpacing() {
        List<AssistantRequest.Favorite> favs = List.of(new AssistantRequest.Favorite("남광주 시장", 35.1385, 126.9245));
        AssistantTools.Found f = tools.searchPlace(turn("남광주시장 가자", favs), "남광주시장");
        assertThat(f.places().get(0).name()).isEqualTo("남광주 시장");
    }

    @Test
    void farOffNamesAreNotGuessed() {
        when(kakao.search(eq("없는가게"), anyDouble(), anyDouble())).thenReturn(List.of());
        List<AssistantRequest.Favorite> favs = List.of(new AssistantRequest.Favorite("문화전당", 35.1469, 126.9198));
        assertThat(tools.searchPlace(turn("없는가게 가자", favs), "없는가게").status()).isEqualTo("none");
    }
}
