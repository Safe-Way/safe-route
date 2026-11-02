package com.saferoute.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/** 조선대 위치로 밤 판단을 확인 (DB·인터넷 없이 돈다) */
class NightJudgeTests {

    private static final double LAT = 35.1408;
    private static final double LON = 126.9300;

    private final NightJudge judge = new NightJudge();

    private static ZonedDateTime oct30(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 30, hour, minute, 0, 0, NightJudge.KOREA);
    }

    @Test
    void sunTimesAreAboutRight() {
        // 2026-10-30 광주: 해 뜸 06:51, 해 짐 17:40 쯤
        NightResult r = judge.judge(LAT, LON, oct30(12, 0), NightOption.AUTO);
        assertThat(r.sunrise()).isBetween(LocalTime.of(6, 45), LocalTime.of(6, 57));
        assertThat(r.sunset()).isBetween(LocalTime.of(17, 35), LocalTime.of(17, 46));
    }

    @Test
    void autoFollowsTheSun() {
        assertThat(judge.judge(LAT, LON, oct30(12, 0), NightOption.AUTO).night()).isFalse();
        assertThat(judge.judge(LAT, LON, oct30(18, 30), NightOption.AUTO).night()).isTrue();
        assertThat(judge.judge(LAT, LON, oct30(23, 0), NightOption.AUTO).night()).isTrue();
        assertThat(judge.judge(LAT, LON, oct30(5, 0), NightOption.AUTO).night()).isTrue();
        assertThat(judge.judge(LAT, LON, oct30(12, 0), NightOption.AUTO).nightSource()).isEqualTo("auto");
    }

    @Test
    void manualOverridesTheSun() {
        // 낮 시연: 낮 12시라도 ON 이면 밤
        NightResult on = judge.judge(LAT, LON, oct30(12, 0), NightOption.ON);
        assertThat(on.night()).isTrue();
        assertThat(on.nightSource()).isEqualTo("manual");
        assertThat(judge.judge(LAT, LON, oct30(23, 0), NightOption.OFF).night()).isFalse();
    }

    @Test
    void readsOptionText() {
        assertThat(NightOption.from(null)).isEqualTo(NightOption.AUTO);
        assertThat(NightOption.from("on")).isEqualTo(NightOption.ON);
        assertThat(NightOption.from(" OFF ")).isEqualTo(NightOption.OFF);
        assertThatThrownBy(() -> NightOption.from("maybe")).isInstanceOf(IllegalArgumentException.class);
    }
}
