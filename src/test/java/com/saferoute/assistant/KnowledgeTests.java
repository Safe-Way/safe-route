package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.assistant.KnowledgeRepository.Piece;

/** 지식 조각 고르기·찾는 때 (비서 토큰 줄이기 R4, DB 없이) */
class KnowledgeTests {

    static String body(int n) {
        return "가".repeat(n);
    }

    @Test
    void keepsOnlyAboveMinScoreUpToThree() {
        List<Piece> rows = List.of(new Piece(1, "a", body(100), 9), new Piece(2, "b", body(100), 3),
                new Piece(3, "c", body(100), 0.5), new Piece(4, "d", body(100), 2), new Piece(5, "e", body(100), 1.5));
        assertThat(KnowledgeRepository.pick(rows, 1.0)).extracting(Piece::id).containsExactly(1, 2, 4);
    }

    @Test
    void stopsAtNineHundredChars() {
        List<Piece> rows = List.of(new Piece(1, "a", body(300), 9), new Piece(2, "b", body(300), 8),
                new Piece(3, "c", body(301), 7));
        assertThat(KnowledgeRepository.pick(rows, 1.0)).extracting(Piece::id).containsExactly(1, 2);
    }

    @Test
    void searchesOnlyForQuestionsAboutTheApp() {
        assertThat(GeminiBrain.wantsKnowledge("AI가 내 위치 알아?", RulePiecesTests.groups("AI가 내 위치 알아?"))).isTrue();
        assertThat(GeminiBrain.wantsKnowledge("내 기록 어디에 저장돼?", RulePiecesTests.groups("내 기록 어디에 저장돼?"))).isTrue();
        assertThat(GeminiBrain.wantsKnowledge("휠체어 모드는 뭐가 달라?", RulePiecesTests.groups("휠체어 모드는 뭐가 달라?")))
                .isTrue();
        // 도구로 끝나는 말에는 안 붙임
        assertThat(GeminiBrain.wantsKnowledge("화장실 어디야", RulePiecesTests.groups("화장실 어디야"))).isFalse();
        assertThat(GeminiBrain.wantsKnowledge("문화전당 어떻게 가", RulePiecesTests.groups("문화전당 어떻게 가"))).isFalse();
    }
}
