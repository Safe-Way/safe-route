package com.saferoute.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.saferoute.situation.SituationProperties;

/** 가짜 기상청 담당으로 비·폭염·한파 판단과 실패 처리를 확인 */
class WeatherJudgeTests {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 3, 10, 50, 0, 0, KmaClient.KOREA);

    private final KmaClient kma = mock(KmaClient.class);
    private final SituationProperties props = new SituationProperties(33, -10, 81, 36);
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

    @Test
    void heatAndColdBoundaries() {
        assertThat(judgeWith(new WeatherNow(33.0, 0.0, 0, "t")).heat()).isTrue();
        assertThat(judgeWith(new WeatherNow(32.9, 0.0, 0, "t")).heat()).isFalse();
        assertThat(judgeWith(new WeatherNow(-10.0, 0.0, 0, "t")).cold()).isTrue();
        assertThat(judgeWith(new WeatherNow(-9.9, 0.0, 0, "t")).cold()).isFalse();
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
