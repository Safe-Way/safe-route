package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** 날짜 말 알아듣기 (오늘 = 2026-11-06 금) */
class KoreanTimeTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void untilWords() {
        assertThat(KoreanTime.until("이번 달 말까지", TODAY)).contains(LocalDate.of(2026, 11, 30));
        assertThat(KoreanTime.until("이번 달까지만", TODAY)).contains(LocalDate.of(2026, 11, 30));
        assertThat(KoreanTime.until("12월 3일까지", TODAY)).contains(LocalDate.of(2026, 12, 3));
        assertThat(KoreanTime.until("20일까지", TODAY)).contains(LocalDate.of(2026, 11, 20));
        assertThat(KoreanTime.until("일주일 정도", TODAY)).contains(LocalDate.of(2026, 11, 13));
        assertThat(KoreanTime.until("두 주", TODAY)).contains(LocalDate.of(2026, 11, 20));
        assertThat(KoreanTime.until("한 달", TODAY)).contains(LocalDate.of(2026, 12, 6));
        assertThat(KoreanTime.until("오늘만", TODAY)).contains(TODAY);
        assertThat(KoreanTime.until("잘 모르겠어", TODAY)).isEmpty();
        assertThat(KoreanTime.until("문화전당 가자", TODAY)).isEmpty();
    }

    @Test
    void testTimeAndSpeech() {
        assertThat(KoreanTime.testTime("화 08:25", TODAY)).contains(LocalDateTime.of(2026, 11, 3, 8, 25));
        assertThat(KoreanTime.testTime("11-30 09:00", TODAY)).contains(LocalDateTime.of(2026, 11, 30, 9, 0));
        assertThat(KoreanTime.testTime("아무거나", TODAY)).isEmpty();
        assertThat(KoreanTime.clock(LocalTime.of(8, 30))).isEqualTo("8시 반");
        assertThat(KoreanTime.clock(LocalTime.of(13, 15))).isEqualTo("1시 15분");
        assertThat(KoreanTime.day(LocalDate.of(2026, 11, 30))).isEqualTo("11월 30일");
    }
}
