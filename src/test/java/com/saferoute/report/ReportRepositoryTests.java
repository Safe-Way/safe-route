package com.saferoute.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 033(reporter_hash·reporter_count)이 아직 DB에 없을 때도 신고 창구가 멈추지 않는지 */
class ReportRepositoryTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReportRepository repo = new ReportRepository(jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void activeFallsBackWhenViewHasNoReporterCount() {
        List<String> sqls = new ArrayList<>();
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqls.add(sql);
            if (!sql.contains("AS reporter_count")) {
                throw new BadSqlGrammarException("active", sql, new SQLException("Unknown column 'reporter_count'"));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThat(repo.active(126.92, 35.13, 126.94, 35.15, 10)).isEmpty();
        assertThat(repo.active(126.92, 35.13, 126.94, 35.15, 10)).isEmpty();

        assertThat(sqls).hasSize(3);
        assertThat(sqls.get(1)).contains("report_count AS reporter_count");
        assertThat(sqls.get(2)).contains("report_count AS reporter_count");
    }

    @Test
    void retentionSqlIsSameAsDataScript() {
        // scripts/review_reports.py 의 PHOTO_DUE·ROW_DUE 를 찍은 값 그대로 (재휘님 check_report_retention.py 12/12)
        assertThat(ReportRepository.PHOTO_DUE).isEqualTo("SELECT r.id FROM road_report r WHERE EXISTS (SELECT 1 FROM road_report_photo p WHERE p.report_id = r.id) AND (r.status = 'hidden' OR NOT EXISTS (SELECT 1 FROM road_report n WHERE (n.status <> 'hidden' OR n.id = r.id) AND n.report_type = r.report_type AND ST_Distance_Sphere(n.geom, r.geom) <= 30 AND DATE_ADD(DATE(n.created_at), INTERVAL (CASE n.report_type WHEN 'obstacle' THEN 1 WHEN 'accident' THEN 1 WHEN 'construction' THEN 7 WHEN 'curb' THEN 30 WHEN 'steep' THEN 30 WHEN 'broken' THEN 30 ELSE 7 END) - 1 + 7 DAY) >= CURDATE()))");
        assertThat(ReportRepository.ROW_DUE).isEqualTo("SELECT r.id FROM road_report r WHERE NOT EXISTS (SELECT 1 FROM road_report n WHERE (n.status <> 'hidden' OR n.id = r.id) AND n.report_type = r.report_type AND ST_Distance_Sphere(n.geom, r.geom) <= 30 AND DATE_ADD(DATE(n.created_at), INTERVAL (CASE n.report_type WHEN 'obstacle' THEN 1 WHEN 'accident' THEN 1 WHEN 'construction' THEN 7 WHEN 'curb' THEN 30 WHEN 'steep' THEN 30 WHEN 'broken' THEN 30 ELSE 7 END) - 1 + 30 DAY) >= CURDATE())");
    }

    @Test
    void cleanUpReadsIdsThenDeletesByIds() {
        when(jdbc.queryForList(ReportRepository.PHOTO_DUE, Long.class)).thenReturn(List.of(3L, 4L));
        when(jdbc.queryForList(ReportRepository.ROW_DUE, Long.class)).thenReturn(List.of(9L));
        when(jdbc.update(eq("DELETE FROM road_report_photo WHERE report_id IN (?, ?)"), any(Object[].class)))
                .thenReturn(5);
        when(jdbc.update(eq("DELETE FROM road_report WHERE id IN (?)"), any(Object[].class))).thenReturn(1);

        assertThat(repo.cleanUp()).containsExactly(5, 1);
    }

    @Test
    void nothingToCleanDeletesNothing() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of());
        assertThat(repo.cleanUp()).containsExactly(0, 0);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapListAndPhotoSkipEndedReports() {
        List<String> sqls = new ArrayList<>();
        doAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            return null;
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class));
        doAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        repo.within(126.92, 35.13, 126.94, 35.15, 10);
        assertThat(repo.photo(1, 1)).isNull();

        assertThat(sqls).hasSize(2).allSatisfy(sql -> assertThat(sql).contains(ReportRepository.stillKept(0)));
    }
}
