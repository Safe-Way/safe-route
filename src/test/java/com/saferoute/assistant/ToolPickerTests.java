package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 말에 맞는 도구만 고르기 (비서 토큰 줄이기 R2) */
class ToolPickerTests {

    static Turn turn(String text, Conversation c) {
        AssistantRequest req = new AssistantRequest(null, text, 35.148, 126.926, null, null, null, null, null, null);
        return new Turn(req, c, LocalDateTime.of(2026, 11, 9, 12, 0), false, null);
    }

    static List<String> pick(String text) {
        return ToolPicker.pick(turn(text, Conversation.start()));
    }

    @Test
    void goingSomewhereSendsRouteTools() {
        assertThat(pick("문화전당 가자")).containsExactly("search_place", "recommend_trip", "get_my_info");
    }

    @Test
    void nearbySendsNearbyTools() {
        assertThat(pick("화장실 어디야")).containsExactly("search_place", "find_nearby");
    }

    @Test
    void reportAndMapAndMe() {
        assertThat(pick("여기 공사 중이야")).containsExactly("recommend_trip", "report_problem");
        assertThat(pick("지도에 CCTV 빼줘")).containsExactly("set_map_layers");
        assertThat(pick("다리를 다쳐서 휠체어 타")).containsExactly("recommend_trip", "get_my_info", "update_my_info");
    }

    @Test
    void severalGroupsAreJoined() {
        assertThat(pick("휠체어로 문화전당 가자"))
                .containsExactly("search_place", "recommend_trip", "get_my_info", "update_my_info");
    }

    @Test
    void unknownSentenceSendsAllSeven() {
        assertThat(pick("음 그거 있잖아")).isEqualTo(ToolPicker.ALL);
    }

    @Test
    void choosingFromCandidatesKeepsRouteTools() {
        Conversation c = Conversation.start().withCandidates(List.of(new Conversation.Place("A", null, 35.1, 126.9)));
        assertThat(ToolPicker.pick(turn("두 번째 거", c))).contains("search_place", "recommend_trip");
    }

    @Test
    void storeNameSendsSearchPlace() {
        assertThat(pick("약국 어디야")).containsExactly("search_place", "find_nearby");
    }

    @Test
    void followUpKeepsLastTools() {
        // "거기 몇 층이야"처럼 키워드가 없어도 바로 전에 쓴 도구 묶음을 보낸다
        Conversation c = Conversation.start().withLastTools(List.of("find_nearby"));
        assertThat(ToolPicker.pick(turn("거기 몇 층이야", c))).containsExactly("search_place", "find_nearby");
    }

    @Test
    void waitingConfirmKeepsThatTool() {
        Conversation c = Conversation.start()
                .withPending(new Conversation.Pending("set_map_layers", java.util.Map.of(), "맞으세요?", null));
        assertThat(ToolPicker.pick(turn("응 그렇게 해", c))).contains("set_map_layers");
    }

    @Test
    void lastToolsSurviveConversationId() {
        Conversation.Codec codec = new Conversation.Codec(tools.jackson.databind.json.JsonMapper.builder().build());
        Conversation back = codec.decode(codec.encode(Conversation.start().withLastTools(List.of("find_nearby"))));
        assertThat(back.lastTools()).containsExactly("find_nearby");
        assertThat(codec.decode(null).lastTools()).isEmpty();
    }

    @Test
    void everyGroupInFileHasWordsAndKnownTools() {
        assertThat(ToolPicker.GROUPS).hasSize(6).allSatisfy(g -> {
            assertThat(g.words()).isNotEmpty();
            assertThat(ToolPicker.ALL).containsAll(g.tools());
        });
    }

    @Test
    void declarationsAreFiltered() {
        assertThat(ToolPicker.declarations(GeminiBrain.DECLARATIONS, List.of("report_problem")))
                .extracting(d -> d.get("name")).containsExactly("report_problem");
    }
}
