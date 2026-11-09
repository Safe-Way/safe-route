package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 지시문 조각 고르기 (비서 토큰 줄이기 R3) */
class RulePiecesTests {

    static List<ToolPicker.Group> groups(String text) {
        AssistantRequest req = new AssistantRequest(null, text, 35.148, 126.926, null, null, null, null, null, null);
        return ToolPicker.groups(new Turn(req, Conversation.start(), LocalDateTime.of(2026, 11, 9, 12, 0), false, null));
    }

    @Test
    void sixPiecesAreLoaded() {
        assertThat(RulePieces.TEXT.keySet()).containsExactlyInAnyOrderElementsOf(RulePieces.ORDER);
        assertThat(RulePieces.TEXT.values()).allSatisfy(v -> assertThat(v).isNotBlank());
    }

    @Test
    void allHasEveryPieceAndReferenceLine() {
        String all = RulePieces.all();
        for (String v : RulePieces.TEXT.values()) {
            assertThat(all).contains(v);
        }
        assertThat(all).contains("[참고]에 없는 것은 모른다고 말한다");
    }

    @Test
    void nearbySendsCommonAndNearbyOnly() {
        String s = RulePieces.system(groups("화장실 어디야"));
        assertThat(s).contains(RulePieces.TEXT.get("공통"), RulePieces.TEXT.get("가까운 곳"))
                .doesNotContain("recommend_trip 을 한 번만").doesNotContain("[도구: 저장]");
        assertThat(s.length()).isLessThan(RulePieces.all().length());
    }

    @Test
    void myInfoAddsSaveAndSamePieceOnlyOnce() {
        String s = RulePieces.system(groups("휠체어 타고 있는데 지도에 CCTV 빼 줘"));
        assertThat(s).contains("[도구: 내 정보]", "[도구: 저장]");
        assertThat(s.indexOf("[도구: 저장]")).isEqualTo(s.lastIndexOf("[도구: 저장]"));
    }

    @Test
    void noGroupSendsAll() {
        assertThat(RulePieces.system(List.of())).isEqualTo(RulePieces.all());
    }
}
