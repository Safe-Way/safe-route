package com.saferoute.assistant;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 비서 지식 조각 찾기·못 한 요청 남기기 SQL (가짜 DB) */
class AssistantRepositoryDbTests {

    @Test
    void knowledgeFindsAndPicksByScore() {
        FakeDb db = new FakeDb().rows("FROM assistant_knowledge",
                row("id", 1, "topic", "휠체어", "body", "휠체어 모드는 계단을 피해요.", "s", 3.2),
                row("id", 2, "topic", "낮은 점수", "body", "점수가 낮아요.", "s", 0.4));
        KnowledgeRepository repo = new KnowledgeRepository(db.jdbc(), 1.0);

        assertThat(repo.find("  휠체어 왜 돌아가  ")).extracting(KnowledgeRepository.Piece::id).containsExactly(1);
        assertThat(db.call("FROM assistant_knowledge").params()).containsExactly("휠체어 왜 돌아가", "휠체어 왜 돌아가");
        assertThat(repo.find(" ")).isEmpty();
        assertThat(repo.find(null)).isEmpty();
    }

    @Test
    void knowledgeTableMissingAnswersWithoutIt() {
        FakeDb db = new FakeDb().fails("FROM assistant_knowledge", "Table 'assistant_knowledge' doesn't exist");

        assertThat(new KnowledgeRepository(db.jdbc(), 1.0).find("저장은 어떻게 돼")).isEmpty();
    }

    @Test
    void unmetRequestIsCleanedBeforeSaving() {
        FakeDb db = new FakeDb();
        new UnmetRepository(db.jdbc()).add(LocalDate.of(2026, 11, 16), "senior", "김철수님에게 010-1234-5678 전화해 줘",
                "call", "phone", "no");

        assertThat(db.call("INSERT INTO assistant_unmet").params()).containsExactly(Date.valueOf("2026-11-16"),
                "senior", "(이름)에게 (번호) 전화해 줘", "call", "phone", "no");
    }

    @Test
    void unmetFailureIsOnlyLogged() {
        FakeDb db = new FakeDb().fails("INSERT INTO assistant_unmet", "Table 'assistant_unmet' doesn't exist");

        new UnmetRepository(db.jdbc()).add(LocalDate.of(2026, 11, 16), "adult", null, "x", null, null);

        assertThat(db.calls("INSERT INTO assistant_unmet")).hasSize(1);
    }
}
