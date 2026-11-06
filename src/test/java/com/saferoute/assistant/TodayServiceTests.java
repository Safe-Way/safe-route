package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

class TodayServiceTests {

    static final String G = "00000000-0000-4000-8000-00000000de07";
    static final double LAT = 35.1468;
    static final double LON = 126.9198;
    /** 2026-11-06 (금) 08:20 */
    final Clock clock = Clock.fixed(Instant.parse("2026-11-05T23:20:00Z"), ZoneId.of("Asia/Seoul"));
    final MemoryRepository memory = mock(MemoryRepository.class);
    final TripRecommender trips = mock(TripRecommender.class);
    final WeatherDustService weather = mock(WeatherDustService.class);
    final TodayService service = new TodayService(memory, trips, weather, clock);

    static final WeatherDust RAIN = new WeatherDust(true, false, false, 12.0, true, false, true);
    static final WeatherDust CLEAR = new WeatherDust(false, false, false, 15.0, true, false, true);
    static final MemoryRepository.Habit MARKET = new MemoryRepository.Habit("남광주시장", 35.139, 126.929,
            LocalTime.of(8, 30), 5, 4, 1);
    static final MemoryRepository.Habit SCHOOL = new MemoryRepository.Habit("복지관", 35.150, 126.910,
            LocalTime.of(9, 0), 3, 0, 3);

    static TripRecommender.Plan plan(String type, String summary) {
        Trip t = new Trip(type, 12, 300, 0, 0, summary, Map.of("mode", "senior"), null, Map.of(), List.of(), true,
                "30번 5분 뒤");
        return new TripRecommender.Plan(new TripScorer.Scored(t, List.of(), List.of("비가 와서 덜 걷는 길")), RAIN,
                false, null);
    }

    @Test
    void rainyHabitRecommendsTransitWithMyRainHabit() {
        when(memory.profile(G)).thenReturn(new Profile("senior", null, false, null, null, "senior", 1, 1, true, true));
        when(memory.habits(eq(G), eq(5), eq(LocalTime.of(7, 50)), eq(LocalTime.of(9, 50))))
                .thenReturn(List.of(MARKET, SCHOOL));
        when(weather.judge(LAT, LON)).thenReturn(RAIN);
        when(memory.weatherChoice(G, "rain")).thenReturn(new MemoryRepository.WeatherChoice(6, 5, 1, 0));
        when(trips.recommend(any())).thenReturn(plan("transit", "버스 30번, 12분"));

        TodaySuggestion s = service.today(G.toUpperCase(), LAT, LON, null);
        assertThat(s.say()).startsWith("8시 반에 남광주시장 가시죠?").contains("비가 와서 대중교통을 추천해요",
                "버스 30번, 12분이에요", "비 오는 날엔 보통 대중교통을 타셨어요");
        assertThat(s.habit().usual()).isEqualTo("transit");
        assertThat(s.habit().slot()).isEqualTo("08:30");
        assertThat(s.others()).extracting(TodaySuggestion.Habit::toName).containsExactly("복지관");
        assertThat(s.weather().word()).isEqualTo("rain");
        assertThat(s.pick().type()).isEqualTo("transit");
        assertThat(s.because()).containsExactly("비가 와서 덜 걷는 길");
        ArgumentCaptor<TripRecommender.Ask> ask = ArgumentCaptor.forClass(TripRecommender.Ask.class);
        verify(trips).recommend(ask.capture());
        assertThat(ask.getValue().mode()).isEqualTo("senior");
        assertThat(ask.getValue().weather()).isEqualTo(RAIN);
        assertThat(ask.getValue().dest().name()).isEqualTo("남광주시장");
    }

    @Test
    void noHabitJustAsksWhereAndNeverAsksSetup() {
        when(memory.habits(anyString(), any(Integer.class), any(), any())).thenReturn(List.of());
        when(weather.judge(LAT, LON)).thenReturn(CLEAR);
        TodaySuggestion s = service.today(G, LAT, LON, "child");
        assertThat(s.say()).isEqualTo("어디 가실 곳이 있으세요? 말씀해 주시면 길을 찾아 드릴게요.");
        assertThat(s.habit()).isNull();
        assertThat(s.pick()).isNull();
        assertThat(s.weatherHint()).isNull();
        verify(trips, never()).recommend(any());
    }

    @Test
    void withoutLocationGivesHabitOnly() {
        when(memory.habits(anyString(), any(Integer.class), any(), any())).thenReturn(List.of(SCHOOL));
        TodaySuggestion s = service.today(G, null, null, "adult");
        assertThat(s.say()).contains("9시에 복지관 가시죠?", "위치를 켜 주시면");
        assertThat(s.habit().usual()).isEqualTo("walk");
        assertThat(s.weather().word()).isNull();
        verify(weather, never()).judge(any(Double.class), any(Double.class));
        verify(trips, never()).recommend(any());
    }

    @Test
    void appModeUsedWhenServerDoesNotKnowMe() {
        when(memory.profile(G)).thenReturn(null);
        when(memory.habits(anyString(), any(Integer.class), any(), any())).thenReturn(List.of(SCHOOL));
        when(weather.judge(LAT, LON)).thenReturn(CLEAR);
        when(trips.recommend(any())).thenReturn(plan("walk", "걸어서 12분, 300m"));
        TodaySuggestion s = service.today(G, LAT, LON, "wheelchair");
        assertThat(s.say()).contains("걸어서 12분, 300m이에요").doesNotContain("대중교통을 추천");
        ArgumentCaptor<TripRecommender.Ask> ask = ArgumentCaptor.forClass(TripRecommender.Ask.class);
        verify(trips).recommend(ask.capture());
        assertThat(ask.getValue().mode()).isEqualTo("wheelchair");
    }

    @Test
    void dbDownStillAnswers() {
        when(memory.profile(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        when(weather.judge(LAT, LON)).thenReturn(CLEAR);
        assertThat(service.today(G, LAT, LON, null).say()).startsWith("어디 가실 곳");
    }

    @Test
    void lateNightWindowStopsAtMidnight() {
        // 2026-11-06 (금) 23:40 → 23:10 ~ 23:59 (다음 날로 넘어가지 않음)
        TodayService late = new TodayService(memory, trips, weather,
                Clock.fixed(Instant.parse("2026-11-06T14:40:00Z"), ZoneId.of("Asia/Seoul")));
        when(memory.habits(anyString(), any(Integer.class), any(), any())).thenReturn(List.of());
        late.today(G, null, null, null);
        verify(memory).habits(G, 5, LocalTime.of(23, 10), LocalTime.of(23, 59));
    }

    @Test
    void weatherHintNeedsEnoughTrips() {
        assertThat(TodayService.hint("rain", new MemoryRepository.WeatherChoice(2, 2, 0, 0))).isNull();
        assertThat(TodayService.hint("heat", new MemoryRepository.WeatherChoice(4, 1, 3, 0)))
                .isEqualTo("더운 날엔 보통 걸어가셨어요");
        assertThat(TodayService.hint("cold", null)).isNull();
    }

    @Test
    void endpointRejectsBadGuestAndMode() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TodayController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
        mvc.perform(get("/api/suggestions/today").param("guestId", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_PARAM"));
        mvc.perform(get("/api/suggestions/today").param("guestId", G).param("mode", "flying"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/suggestions/today"))
                .andExpect(status().isBadRequest());
        when(memory.habits(anyString(), any(Integer.class), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/suggestions/today").param("guestId", G))
                .andExpect(status().isOk()).andExpect(jsonPath("$.say").exists());
        assertThatThrownBy(() -> service.today(G, 10.0, 126.9, null)).hasMessageContaining("우리나라");
    }
}
