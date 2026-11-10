package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.saferoute.assistant.Conversation.Stop;

/** 말에서 들를 곳·도착 읽기 (11-09 결정 ①) */
class StopReaderTests {

    @Test
    void readsErrandsOnTheWayHome() {
        StopReader.Read r = StopReader.read("퇴근길에 다이소 들르고 택배 찾고 집 갈래");
        assertThat(r.stops()).containsExactly(new Stop("다이소", "다이소"), new Stop("편의점", "택배"));
        assertThat(r.ordered()).isFalse();
        assertThat(r.dest()).isEqualTo("집");
    }

    @Test
    void wentThereThenStopThenHome() {
        // 사용성 점검: "병원 갔다가 약국 들러서 집에 갈래" → 병원·약국 들르고 집
        StopReader.Read r = StopReader.read("병원 갔다가 약국 들러서 집에 갈래");
        assertThat(r.stops()).extracting(Stop::query).containsExactly("병원", "약국");
        assertThat(r.dest()).isEqualTo("집");
    }

    @Test
    void keepsSpokenOrder() {
        StopReader.Read r = StopReader.read("먼저 다이소, 그다음 약국 들렀다가 집에 가자");
        assertThat(r.stops()).extracting(Stop::query).containsExactly("다이소", "약국");
        assertThat(r.ordered()).isTrue();
        assertThat(r.dest()).isEqualTo("집");
    }

    @Test
    void oneStopToAPlace() {
        StopReader.Read r = StopReader.read("편의점 들렀다 문화전당 가자");
        assertThat(r.stops()).extracting(Stop::query).containsExactly("편의점");
        assertThat(r.dest()).isEqualTo("문화전당");
    }

    @Test
    void destinationCanBeMissing() {
        StopReader.Read r = StopReader.read("다이소 들르고 약국 들러");
        assertThat(r.stops()).extracting(Stop::query).containsExactly("다이소", "약국");
        assertThat(r.dest()).isNull();
    }

    @Test
    void moreThanThreeIsTooMany() {
        assertThat(StopReader.read("다이소 들르고 약국 들르고 빵집 들르고 꽃집 들렀다 집 가자").tooMany()).isTrue();
    }

    @Test
    void otherSentencesAreNotErrands() {
        assertThat(StopReader.read("문화전당 가자")).isNull();
        assertThat(StopReader.read("화장실 들러야 해")).isNull();
        assertThat(StopReader.read("택배 보내는 곳 어디야")).isNull();
        assertThat(StopReader.read("")).isNull();
    }

    @Test
    void storesJoinedWithRangAreSplit() {
        // 점검 40번 (11-10 재휘): 한 곳으로 읽어 "찾지 못했어요" → 네 곳으로 읽어 "세 곳까지" 되묻기
        StopReader.Read r = StopReader.read("다이소랑 약국이랑 편의점이랑 서점 들렀다가 집 갈래");
        assertThat(r.stops()).extracting(Conversation.Stop::query).containsExactly("다이소", "약국", "편의점", "서점");
        assertThat(r.dest()).isEqualTo("집");
        assertThat(r.tooMany()).isTrue();
    }

    @Test
    void otherJoiningWordsAreSplit() {
        assertThat(StopReader.read("약국하고 편의점 들렀다가 집 가자").stops()).extracting(Conversation.Stop::query)
                .containsExactly("약국", "편의점");
        assertThat(StopReader.read("다이소와 약국 들르고 집 갈래").stops()).extracting(Conversation.Stop::query)
                .containsExactly("다이소", "약국");
        assertThat(StopReader.read("약국과 편의점 들르고 집 갈래").stops()).extracting(Conversation.Stop::query)
                .containsExactly("약국", "편의점");
    }

    @Test
    void namesEndingInGwaStayWhole() {
        // 치과·안과는 이어 주는 말이 아님
        assertThat(StopReader.joined("치과 약국")).containsExactly("치과 약국");
        assertThat(StopReader.joined("안과 들르고")).containsExactly("안과 들르고");
        assertThat(StopReader.joined("사과 사러")).containsExactly("사과 사러");
        assertThat(StopReader.read("치과 들렀다가 집 갈래").stops()).extracting(Conversation.Stop::query)
                .containsExactly("치과");
    }
}
