package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.saferoute.situation.WeatherDust;

/** "오늘 저녁에 걸어도 괜찮을까" (폰 시험 11/10: 식당 질문으로 알아들음) */
class WalkOkTests {

    @Test
    void walkQuestionsAreRecognized() {
        assertThat(RuleBrain.asksWalkOk("오늘저녁에걸어도괜찮을까")).isTrue();
        assertThat(RuleBrain.asksWalkOk("지금산책해도돼?")).isTrue();
        assertThat(RuleBrain.asksWalkOk("밤에나가도안전할까")).isTrue();
        assertThat(RuleBrain.asksWalkOk("저녁먹을데추천해줘")).isFalse();
        assertThat(RuleBrain.asksWalkOk("걷기좋은공원찾아줘")).isFalse();
    }

    @Test
    void eveningAnswerSaysWeatherNowAndSunset() {
        WeatherDust clear = new WeatherDust(false, false, false, 14.4, true, false, true);
        String s = Sentences.walkOk(clear, LocalTime.of(17, 32), true, LocalTime.of(15, 0));
        assertThat(s).contains("지금 날씨는 걷기 괜찮아요", "14도", "미세먼지는 괜찮아요", "17시 32분에 져요", "가로등",
                "가실 곳을 말씀해");
    }

    @Test
    void rainAndBadDustAreSaid() {
        WeatherDust rain = new WeatherDust(true, false, false, 9.0, true, true, true);
        String s = Sentences.walkOk(rain, LocalTime.of(17, 32), false, LocalTime.of(12, 0));
        assertThat(s).contains("비가 와요", "지붕", "마스크").doesNotContain("해는");
    }

    @Test
    void noWeatherIsSaidHonestly() {
        String s = Sentences.walkOk(WeatherDust.unknown(), null, true, LocalTime.of(19, 0));
        assertThat(s).contains("받아 오지 못했어요").doesNotContain("괜찮아요.", "해는");
    }
}
