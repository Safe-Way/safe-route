package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteResult;

/** 비서가 엔진 알림(explanation.notes)을 걷는 길 답에 붙이기 (11-23) */
class EngineNotesTests {

    @Test
    void picksByOrderAtMostTwo() {
        String say = Sentences.withNotes("걸어서 9분이에요.", List.of(
                RouteResult.Note.info("alley", "골목길 약 120m를 지납니다"),
                RouteResult.Note.warn("narrow", "폭 1.5m 안 되는 좁은 인도 약 40m를 지납니다"),
                RouteResult.Note.warn("crossing", "횡단보도 없이 길을 건너요")));
        assertThat(say).isEqualTo("걸어서 9분이에요. 횡단보도 없이 길을 건너요. 폭 1.5m 안 되는 좁은 인도 약 40m를 지나요.");
    }

    @Test
    void skipsStairsEventsAndWhatWasSaid() {
        String say = Sentences.withNotes("걸어서 9분이에요. 횡단보도 없이 길을 건너요.", List.of(
                RouteResult.Note.warn("stairs", "계단 1곳을 지납니다. 계단 없는 길이 없습니다"),
                RouteResult.Note.warn("event", "공사 구간을 지납니다"),
                RouteResult.Note.warn("crossing", "횡단보도 없이 길을 건너요")));
        assertThat(say).isEqualTo("걸어서 9분이에요. 횡단보도 없이 길을 건너요.");
    }

    @Test
    void engineSentencesBecomePoliteTalk() {
        assertThat(Sentences.noteLine("등산로 약 300m를 지납니다. 등산로를 피하는 길은 없습니다"))
                .isEqualTo("등산로 약 300m를 지나요. 등산로를 피하는 길은 없어요.");
        assertThat(Sentences.noteLine("도착지 근처 약 30m는 휠체어로 이어진 길이 확인되지 않았습니다"))
                .isEqualTo("도착지 근처 약 30m는 휠체어로 이어진 길이 확인되지 않았어요.");
        assertThat(Sentences.withNotes(null, List.of(RouteResult.Note.warn("crossing", "x")))).isNull();
        assertThat(Sentences.withNotes("그대로", List.of())).isEqualTo("그대로");
    }

    @Test
    void onlyForTheSafeWalk() {
        List<RouteResult.Note> notes = List.of(RouteResult.Note.warn("crossing", "횡단보도 없이 길을 건너요"));
        RouteResult r = new RouteResult(null, List.of(),
                RouteResult.Explanation.of("인도가 있는 길로 갑니다", notes, 0, 0, Map.of()), null, null);
        assertThat(TripRecommender.engineNotes(r, route("safe"))).isEqualTo(notes);
        assertThat(TripRecommender.engineNotes(r, route("shortest"))).isEmpty();
        assertThat(TripRecommender.engineNotes(r, null)).isEmpty();
    }

    private static RouteResult.Route route(String kind) {
        return new RouteResult.Route(kind, 600, 9, List.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
