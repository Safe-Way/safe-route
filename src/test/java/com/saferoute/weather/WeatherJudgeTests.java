package com.saferoute.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.saferoute.situation.SituationProperties;

/** 가짜 기상청 담당으로 비·폭염·한파 판단과 실패 처리를 확인 */
class WeatherJudgeTests {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 3, 10, 50, 0, 0, KmaClient.KOREA);

    private final KmaClient kma = mock(KmaClient.class);
    private static final double HEAT_LIMIT_C = 33;
    private static final double COLD_LIMIT_C = -10;
    private static final int PM10_BAD = 81;
    private static final int PM25_BAD = 36;

    private final SituationProperties props = new SituationProperties(HEAT_LIMIT_C, COLD_LIMIT_C, PM10_BAD, PM25_BAD);
    private final WeatherJudge judge = new WeatherJudge(kma, props);

    private WeatherResult judgeWith(WeatherNow w) {
        when(kma.now(any(), any())).thenReturn(w);
        return judge.judgeAt(35.1401, 126.9336, NOW);
    }

    @Test
    void rainByPrecipitationType() {
        WeatherResult r = judgeWith(new WeatherNow(12.3, 1.5, 1, "202611031000"));

        assertThat(r).isEqualTo(new WeatherResult(true, false, false, 12.3, 1.5, "202611031000", true));
    }

    @Test
    void snowIsNotRain() {
        assertThat(judgeWith(new WeatherNow(-2.0, 0.5, 3, "t")).rain()).isFalse();
    }

    @Test
    void rainByAmountWhenTypeUnknown() {
        assertThat(judgeWith(new WeatherNow(15.0, 0.2, null, "t")).rain()).isTrue();
    }

    @ParameterizedTest(name = "기온 {0}도 → 더위 {1}")
    @CsvSource({"33.0, true", "32.9, false"})
    @DisplayName("더위 기준 33도: 딱 33도부터 더위, 바로 아래(32.9도)는 더위 아님")
    void heatStartsExactlyAtLimit(double airTempC, boolean expectedHeat) {
        WeatherResult result = judgeWith(onlyAirTemp(airTempC));

        assertThat(result.heat()).isEqualTo(expectedHeat);
    }

    @ParameterizedTest(name = "기온 {0}도 → 추위 {1}")
    @CsvSource({"-10.0, true", "-9.9, false"})
    @DisplayName("추위 기준 -10도: 딱 -10도부터 추위, 바로 위(-9.9도)는 추위 아님")
    void coldStartsExactlyAtLimit(double airTempC, boolean expectedCold) {
        WeatherResult result = judgeWith(onlyAirTemp(airTempC));

        assertThat(result.cold()).isEqualTo(expectedCold);
    }

    /** 기온만 바꾸고 비·눈은 없는 날씨 (다른 값이 결과에 끼어들지 않게) */
    private static WeatherNow onlyAirTemp(double airTempC) {
        double noRainMm = 0.0;
        int noPrecipitation = 0;
        return new WeatherNow(airTempC, noRainMm, noPrecipitation, "시험 시각");
    }

    @Test
    void kmaFailureGivesUnavailable() {
        when(kma.now(any(), any())).thenThrow(new WeatherUnavailableException("시간 초과", null));

        assertThat(judge.judgeAt(35.1401, 126.9336, NOW)).isEqualTo(WeatherResult.unavailable());
    }

    @Test
    void noValuesGivesUnavailable() {
        assertThat(judgeWith(new WeatherNow(null, null, null, "t")).available()).isFalse();
    }
}
