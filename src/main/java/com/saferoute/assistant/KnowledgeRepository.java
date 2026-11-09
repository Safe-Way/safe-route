package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 앱에 대한 질문에 붙일 설명 찾기 (비서 토큰 줄이기 R4, 표 assistant_knowledge 031·조각은 A파트 재휘님).
 * MySQL 전문 검색(FULLTEXT ngram)으로 keywords·body 를 찾아 점수가 기준을 넘는 것 3개까지, 합 900자까지만.
 * 찾기는 서버 안에서만 하고 제미나이에는 찾은 설명 글만 간다. 표가 없거나 DB가 안 되면 빈 목록 (비서는 그냥 답함).
 */
@Repository
public class KnowledgeRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRepository.class);
    static final int LIMIT = 3;
    static final int MAX_CHARS = 900;

    /** 조각 하나 */
    public record Piece(int id, String topic, String body, double score) {
    }

    private final JdbcTemplate jdbc;
    private final double minScore;

    public KnowledgeRepository(JdbcTemplate jdbc, @Value("${assistant.knowledge-min-score:1.0}") double minScore) {
        this.jdbc = jdbc;
        this.minScore = minScore;
    }

    /** 점수 높은 차례, 기준 넘는 것 3개까지, body 합 900자까지 */
    public List<Piece> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String q = text.strip();
        List<Piece> rows;
        try {
            rows = jdbc.query("SELECT id, topic, body, MATCH(keywords, body) AGAINST(?) AS s FROM assistant_knowledge"
                    + " WHERE MATCH(keywords, body) AGAINST(?) ORDER BY s DESC LIMIT " + LIMIT,
                    (rs, i) -> new Piece(rs.getInt("id"), rs.getString("topic"), rs.getString("body"),
                            rs.getDouble("s")), q, q);
        } catch (DataAccessException e) {
            log.warn("지식 조각 찾기 실패: {}", e.getMostSpecificCause().getClass().getSimpleName());
            return List.of();
        }
        return pick(rows, minScore);
    }

    static List<Piece> pick(List<Piece> rows, double minScore) {
        List<Piece> out = new ArrayList<>();
        int chars = 0;
        for (Piece p : rows) {
            if (p.score() < minScore || p.body() == null || out.size() == LIMIT) {
                continue;
            }
            if (chars + p.body().length() > MAX_CHARS) {
                break;
            }
            out.add(p);
            chars += p.body().length();
        }
        return List.copyOf(out);
    }
}
