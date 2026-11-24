package com.saferoute.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.saferoute.air.DustJudge;
import com.saferoute.air.DustResult;
import com.saferoute.weather.WeatherJudge;
import com.saferoute.weather.WeatherResult;

/** 가짜 날씨·미세먼지 판단으로, 묶기·실패·늦음·기억해 두기를 확인 (인터넷 없이 돈다) */
class WeatherDustServiceTests {

    private static final double LAT = 35.1401;
    private static final double LON = 126.9336;
    private static final WeatherResult RAINY = new WeatherResult(true, false, false, 12.0, 1.5, "202611050900", true);
    private static final WeatherResult NO_WEATHER = new WeatherResult(false, false, false, null, null, null, false);
    private static final DustResult DUSTY = new DustResult(true, 90, 40, "광주", "2026-11-05 09:00", true);
    private static final DustResult NO_DUST = new DustResult(false, null, null, "광주", null, false);

    private final WeatherJudge weather = mock(WeatherJudge.class);
    private final DustJudge dust = mock(DustJudge.class);
    private final MovableClock clock = new MovableClock();
    private final WeatherDustService service =
            new WeatherDustService(weather, dust, clock, Executors.newFixedThreadPool(2));

    @Test
    void combinesWeatherAndDust() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);

        assertThat(service.judge(LAT, LON))
                .isEqualTo(new WeatherDust(true, false, false, 12.0, true, true, true));
    }

    @Test
    void weatherDownKeepsDust() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(NO_WEATHER);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);

        assertThat(service.judge(LAT, LON))
                .isEqualTo(new WeatherDust(false, false, false, null, false, true, true));
    }

    @Test
    void dustDownKeepsWeather() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(NO_DUST);

        assertThat(service.judge(LAT, LON))
                .isEqualTo(new WeatherDust(true, false, false, 12.0, true, false, false));
    }

    @Test
    void unexpectedErrorBecomesUnknownForThatSide() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenThrow(new IllegalStateException("고장"));
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);

        WeatherDust r = service.judge(LAT, LON);

        assertThat(r.weatherAvailable()).isFalse();
        assertThat(r.rain()).isFalse();
        assertThat(r.dustAvailable()).isTrue();
    }

    @Test
    void slowAnswerDoesNotHoldTheRoute() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenAnswer(i -> {
            Thread.sleep(3_000);
            return RAINY;
        });
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);

        long started = System.nanoTime();
        WeatherDust r = service.judge(LAT, LON);
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(r).isEqualTo(WeatherDust.unknown());
        assertThat(waitedMs).isLessThan(2_500);
    }

    @Test
    void sameAreaWithinTenMinutesAsksOnce() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);

        service.judge(LAT, LON);
        clock.move(Duration.ofMinutes(9));
        WeatherDust again = service.judge(LAT + 0.001, LON - 0.001); // 약 100m 옆, 같은 칸

        assertThat(again.rain()).isTrue();
        verify(weather, times(1)).judgeNow(anyDouble(), anyDouble());
        verify(dust, times(1)).judgeAt(anyDouble(), anyDouble());
    }

    @Test
    void asksAgainAfterTenMinutes() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);

        service.judge(LAT, LON);
        clock.move(Duration.ofMinutes(11));
        service.judge(LAT, LON);

        verify(weather, times(2)).judgeNow(anyDouble(), anyDouble());
    }

    @Test
    void failedAnswerIsNotKept() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(NO_WEATHER, RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(NO_DUST, DUSTY);

        service.judge(LAT, LON);
        WeatherDust second = service.judge(LAT, LON);

        assertThat(second.weatherAvailable()).isTrue();
    }

    @Test
    void halfFailedAnswerIsNotKept() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(NO_DUST, DUSTY);

        service.judge(LAT, LON);
        WeatherDust second = service.judge(LAT, LON);

        assertThat(second.dustAvailable()).isTrue();
        verify(dust, times(2)).judgeAt(anyDouble(), anyDouble());
    }

    @Test
    void oldAnswersAreForgottenWhenManyPile() {
        when(weather.judgeNow(anyDouble(), anyDouble())).thenReturn(RAINY);
        when(dust.judgeAt(anyDouble(), anyDouble())).thenReturn(DUSTY);
        for (int i = 0; i < WeatherDustService.MAX_SAVED; i++) {
            service.judge(LAT + i * 0.01, LON); // 칸마다 한 줄
        }
        assertThat(service.savedCount()).isEqualTo(WeatherDustService.MAX_SAVED);

        clock.move(Duration.ofMinutes(11));
        service.judge(LAT - 1, LON);

        assertThat(service.savedCount()).isEqualTo(1); // 시간이 지난 500줄은 지우고 새 한 줄만
    }

    @Test
    void awaitNeverThrows() {
        assertThat(service.await(null)).isEqualTo(WeatherDust.unknown());
    }

    /** 시간을 마음대로 옮길 수 있는 시계 */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T00:00:00Z");

        void move(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
