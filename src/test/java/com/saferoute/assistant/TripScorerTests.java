package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 추천 규칙. 비서 점검 1·30번 (비 오는 아침 휠체어에게 22분 걷기를 고르던 문제) */
class TripScorerTests {

    final TripScorer scorer = new TripScorer();

    static Trip walk(int min, int m) {
        return new Trip("walk", min, m, 0, 0, "걸어서 " + min + "분", Map.of(), "safe", Map.of(), List.of(), false, null);
    }

    static Trip bus(int min, int walkM, boolean lowFloor) {
        return new Trip("transit", min, walkM, 0, 0, "버스 30번, " + min + "분", Map.of(), null, Map.of(), List.of(),
                lowFloor, "30번 5분 뒤");
    }

    static TripScorer.Context rain(String mode, String habit) {
        return new TripScorer.Context(mode, true, false, false, false, false, false, null, habit, null);
    }

    @Test
    void rainyWheelchairPicksLowFloorBusOverLongerRoll() {
        TripScorer.Scored s = scorer.score(List.of(walk(22, 1300), bus(36, 300, true)), rain("wheelchair", "transit"));
        assertThat(s.best().type()).isEqualTo("transit");
        assertThat(s.because()).contains("지난번 비 올 때도 대중교통을 타셨어요", "곧 오는 저상버스가 있어요");
    }

    @Test
    void sameRainAdultStillWalksWhenWalkIsMuchShorter() {
        TripScorer.Scored s = scorer.score(List.of(walk(22, 1300), bus(36, 300, true)), rain("adult", null));
        assertThat(s.best().type()).isEqualTo("walk");
    }

    @Test
    void clearDayWheelchairWalksWhenFaster() {
        TripScorer.Context clear = new TripScorer.Context("wheelchair", false, false, false, false, false, false, null,
                null, null);
        TripScorer.Scored s = scorer.score(List.of(walk(22, 1300), bus(36, 300, true)), clear);
        assertThat(s.best().type()).isEqualTo("walk");
        assertThat(s.because()).contains("휠체어로 22분으로 14분 더 빨라요");
    }
}
