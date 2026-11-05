package com.saferoute.air;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.situation.SituationProperties;

/** 가짜 에어코리아 담당으로 평균·나쁨 판단·실패 처리를 확인 */
class DustJudgeTests {

    private final AirKoreaClient air = mock(AirKoreaClient.class);
    private final SituationProperties props = new SituationProperties(33, -10, 81, 36);
    private final SidoFinder sidoFinder = mock(SidoFinder.class);
    private final DustJudge judge = new DustJudge(air, sidoFinder, props);

    @Test
    void averagesStationsAndJudgesBad() {
        when(air.sido("광주")).thenReturn(List.of(
                new StationAir("서석동", 40, 20, "2026-11-02 14:00"),
                new StationAir("농성동", 90, null, "2026-11-02 14:00"),
                new StationAir("두암동", null, 30, "2026-11-02 14:00")));

        DustResult r = judge.judge("광주");

        // 미세먼지 (40+90)/2 = 65, 초미세먼지 (20+30)/2 = 25 → 둘 다 기준 아래라 나쁨 아님
        assertThat(r).isEqualTo(new DustResult(false, 65, 25, "광주", "2026-11-02 14:00", true));
    }

    @Test
    void badWhenAverageReachesLimit() {
        when(air.sido("광주")).thenReturn(List.of(
                new StationAir("서석동", 80, 36, "2026-11-02 14:00"),
                new StationAir("농성동", 82, 36, "2026-11-02 14:00")));

        DustResult r = judge.judge("광주");

        assertThat(r.pm10()).isEqualTo(81);
        assertThat(r.dustBad()).isTrue();
    }

    @Test
    void airKoreaFailureGivesUnavailable() {
        when(air.sido("광주")).thenThrow(new AirUnavailableException("시간 초과", null));

        assertThat(judge.judge("광주")).isEqualTo(DustResult.unavailable("광주"));
    }

    @Test
    void noValuesGivesUnavailable() {
        when(air.sido("광주")).thenReturn(List.of(new StationAir("서석동", null, null, "")));

        DustResult r = judge.judge("광주");

        assertThat(r.available()).isFalse();
        assertThat(r.dustBad()).isFalse();
    }

    @Test
    void judgeAtFindsSidoFirst() {
        when(sidoFinder.sidoAt(35.1401, 126.9336)).thenReturn("광주");
        when(air.sido("광주")).thenReturn(List.of(new StationAir("서석동", 40, 20, "2026-11-02 14:00")));

        DustResult r = judge.judgeAt(35.1401, 126.9336);

        assertThat(r.sido()).isEqualTo("광주");
        assertThat(r.pm10()).isEqualTo(40);
    }

    @Test
    void judgeAtUnavailableWhenSidoUnknown() {
        when(sidoFinder.sidoAt(0, 0)).thenThrow(new AirUnavailableException("시도를 알 수 없어요", null));

        DustResult r = judge.judgeAt(0, 0);

        assertThat(r.available()).isFalse();
        verifyNoInteractions(air);
    }
}
