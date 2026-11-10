package com.saferoute.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
}
