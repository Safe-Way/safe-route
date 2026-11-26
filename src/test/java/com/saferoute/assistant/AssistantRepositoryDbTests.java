package com.saferoute.assistant;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 비서 지식 조각 찾기·못 한 요청 남기기 SQL (가짜 DB)
 * <p>무엇을: {@link KnowledgeRepository}(앱 설명 조각 찾기)와 {@link UnmetRepository}(비서가 못 한 요청 남기기)가 맞는 SQL·값을 쓰는지.
 * <p>어떻게: 이름에 Db가 붙었지만 진짜 MySQL은 안 쓴다. FakeDb가 SQL에 든 글자로 정해 둔 줄을 돌려주고 넣은 값을 남긴다.
 * 그래서 CI·로컬 어디서나 DB 없이 돈다 (FULLTEXT 점수 계산 자체는 시험하지 못함).
 * <p>왜: 표가 없을 때 오류가 새면 비서 답이 통째로 실패하고, 못 한 요청에 이름·전화번호가 그대로 남으면 개인정보가 DB에 쌓인다.
 */
@DisplayName("비서 지식 조각·못 한 요청 SQL")
class AssistantRepositoryDbTests {

    // FakeDb는 ORDER BY를 흉내 내지 않고 정해 둔 줄을 다 준다. 그래서 점수 기준(1.0)으로 거르는 건 자바 쪽 일임을 본다.
    // 앞뒤 공백은 지우고 같은 글을 MATCH 두 곳(SELECT 점수, WHERE)에 넣는지, 빈 글·null은 DB에 가지도 않는지도 본다
    @Test
    @DisplayName("지식 조각을 찾아 점수순으로 고른다")
    void knowledgeFindsAndPicksByScore() {
        // 3.2 는 기준을 넘고 0.4 는 못 넘는다
        FakeDb db = new FakeDb().rows("FROM assistant_knowledge",
                row("id", 1, "topic", "휠체어", "body", "휠체어 모드는 계단을 피해요.", "s", 3.2),
                row("id", 2, "topic", "낮은 점수", "body", "점수가 낮아요.", "s", 0.4));
        KnowledgeRepository repo = new KnowledgeRepository(db.jdbc(), 1.0);

        assertThat(repo.find("  휠체어 왜 돌아가  ")).extracting(KnowledgeRepository.Piece::id).containsExactly(1);
        assertThat(db.call("FROM assistant_knowledge").params()).containsExactly("휠체어 왜 돌아가", "휠체어 왜 돌아가");
        // 빈 글·null 은 SQL 없이 바로 빈 목록 (여기서는 결과만 보고, SQL이 안 나갔는지는 따로 세지 않는다)
        assertThat(repo.find(" ")).isEmpty();
        assertThat(repo.find(null)).isEmpty();
    }

    // assistant_knowledge 표(031)가 아직 없는 DB여도 비서는 설명 없이 그냥 답해야 한다
    @Test
    @DisplayName("지식 표가 없으면 지식 없이 답한다")
    void knowledgeTableMissingAnswersWithoutIt() {
        FakeDb db = new FakeDb().fails("FROM assistant_knowledge", "Table 'assistant_knowledge' doesn't exist");

        assertThat(new KnowledgeRepository(db.jdbc(), 1.0).find("저장은 어떻게 돼")).isEmpty();
    }

    // 사용자가 한 말을 그대로 남기면 이름·전화번호가 DB에 쌓인다. 넣기 전에 (이름)·(번호)로 바꾸는지 본다.
    // "김철수님에게"는 "님"까지 이름으로 먹고 "에게"만 남는다 (UnmetRepository.NAME 정규식)
    @Test
    @DisplayName("못 한 요청은 다듬어서 저장한다")
    void unmetRequestIsCleanedBeforeSaving() {
        FakeDb db = new FakeDb();
        // 인자: 날짜, 모드, 한 말, kind, topic, offered
        new UnmetRepository(db.jdbc()).add(LocalDate.of(2026, 11, 16), "senior", "김철수님에게 010-1234-5678 전화해 줘",
                "call", "phone", "no");

        assertThat(db.call("INSERT INTO assistant_unmet").params()).containsExactly(Date.valueOf("2026-11-16"),
                "senior", "(이름)에게 (번호) 전화해 줘", "call", "phone", "no");
    }

    // 남기기는 덤이라 표(030)가 없어도 예외를 던지면 안 된다 (던지면 비서 답이 안 나감).
    // 한 말이 null 이어도 터지지 않는지 같이 본다. 예외 없이 여기까지 오고, 시도는 한 번 했어야 한다
    @Test
    @DisplayName("못 한 요청 저장이 실패해도 로그만 남긴다")
    void unmetFailureIsOnlyLogged() {
        FakeDb db = new FakeDb().fails("INSERT INTO assistant_unmet", "Table 'assistant_unmet' doesn't exist");

        new UnmetRepository(db.jdbc()).add(LocalDate.of(2026, 11, 16), "adult", null, "x", null, null);

        assertThat(db.calls("INSERT INTO assistant_unmet")).hasSize(1);
    }
}
