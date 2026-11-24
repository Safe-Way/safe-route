package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** "3시까지 ○○" → 도착 시각 떼기, 출발 시각 문장 (2026-11-23) */
class ArriveByTests {

    /** 2026-11-06(금) 오전 11시 */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 11, 0);

    @Test
    void untilClockIsTakenOutOfTheSentence() {
        ArriveBy.Split s = ArriveBy.split("3시까지 문화전당 가야 해", NOW);
        assertThat(s.at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 15, 0)); // 오전 11시의 "3시" = 오후 3시
        assertThat(s.rest()).isEqualTo("문화전당 가야 해");
    }

    @Test
    void halfAndMinutesAndAmPm() {
        assertThat(ArriveBy.split("오후 2시 반까지 광주역 가자", NOW).at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 14, 30));
        assertThat(ArriveBy.split("문화전당에 1시 20분까지 가야 돼", NOW).at())
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 13, 20));
        assertThat(ArriveBy.split("저녁 7시 전까지 집 갈래", NOW).at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 19, 0));
        assertThat(ArriveBy.split("세 시까지 문화전당 가자", NOW).at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 15, 0));
    }

    @Test
    void laterTodayFirstThenTomorrow() {
        // 오전 11시에 "11시 반까지" = 오늘 11시 반, "10시까지" = 오늘 밤 10시
        assertThat(ArriveBy.split("11시 반까지 광주역", NOW).at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 11, 30));
        assertThat(ArriveBy.split("10시까지 광주역", NOW).at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 22, 0));
        // 밤 11시에 "9시까지" = 내일 아침 9시, "오전 8시까지" = 내일
        LocalDateTime night = LocalDateTime.of(2026, 11, 6, 23, 0);
        assertThat(ArriveBy.split("9시까지 학교 가야 해", night).at()).isEqualTo(LocalDateTime.of(2026, 11, 7, 9, 0));
        assertThat(ArriveBy.split("오전 8시까지 학교", night).at()).isEqualTo(LocalDateTime.of(2026, 11, 7, 8, 0));
    }

    @Test
    void arriveWordsBecomeGoWords() {
        ArriveBy.Split s = ArriveBy.split("2시에 광주역 도착해야 돼", NOW);
        assertThat(s.at()).isEqualTo(LocalDateTime.of(2026, 11, 6, 14, 0));
        assertThat(s.rest()).isEqualTo("광주역 가야 해");
        assertThat(ArriveBy.split("4시에 문화전당에서 약속 있어", NOW).rest()).isEqualTo("문화전당 가야 해");
    }

    @Test
    void startTimeOrNoTimeIsNotArrival() {
        assertThat(ArriveBy.split("3시에 나갈래", NOW)).isNull(); // 출발 시각일 수 있음
        assertThat(ArriveBy.split("문화전당 가자", NOW)).isNull();
        assertThat(ArriveBy.split("3시간 걸려?", NOW)).isNull();
        assertThat(ArriveBy.split("3시까지", NOW)).isNull(); // 갈 곳이 없음
    }

    @Test
    void leaveTimeWithBufferByMode() {
        LocalDateTime three = LocalDateTime.of(2026, 11, 6, 15, 0);
        assertThat(ArriveBy.sentence(three, NOW, 30, "adult"))
                .isEqualTo("3시까지 가시려면 2시 25분쯤 나가시면 돼요 (가는 데 30분, 여유 5분).");
        assertThat(ArriveBy.sentence(three, NOW, 30, "wheelchair"))
                .isEqualTo("3시까지 가시려면 2시 20분쯤 나가시면 돼요 (가는 데 30분, 여유 10분).");
    }

    @Test
    void tightOrLate() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 6, 11, 33);
        assertThat(ArriveBy.sentence(at, NOW, 30, "adult")).isEqualTo("지금 바로 나가시면 11시 33분 전에 도착해요.");
        assertThat(ArriveBy.sentence(LocalDateTime.of(2026, 11, 6, 11, 20), NOW, 30, "adult"))
                .startsWith("지금 나가셔도 11시 20분보다 10분쯤 늦어요.");
        assertThat(ArriveBy.sentence(LocalDateTime.of(2026, 11, 7, 9, 0), LocalDateTime.of(2026, 11, 6, 23, 0), 20,
                "adult")).isEqualTo("내일 9시까지 가시려면 내일 8시 35분쯤 나가시면 돼요 (가는 데 20분, 여유 5분).");
    }
}
