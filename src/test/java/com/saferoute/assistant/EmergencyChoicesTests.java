package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 급한 말 버튼 4개 (진행표 #68: 112 · 보호자 · 파출소 · 괜찮아요) */
class EmergencyChoicesTests {

    @Test
    void emergencyOffersFourButtonsInOrder() {
        Outcome o = RuleBrain.emergency();
        assertThat(o.choices()).extracting(AssistantReply.Choice::value)
                .containsExactly("call_112", "call_guardian", "go_police", "no");
        assertThat(o.choices()).extracting(AssistantReply.Choice::label)
                .containsExactly("112에 전화", "보호자에게 알리기", "가까운 파출소로 안내", "괜찮아요");
        assertThat(o.ask()).contains("112", "보호자", "파출소");
    }
}
