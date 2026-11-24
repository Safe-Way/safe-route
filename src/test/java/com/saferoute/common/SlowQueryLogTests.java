package com.saferoute.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** 느린 쿼리 로그: 가짜 DataSource에서 일부러 늦은 SQL을 돌려 WARN이 남는지 본다 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("느린 쿼리 로그")
class SlowQueryLogTests {

    /** execute 가 sleepMs 만큼 걸리는 가짜 DataSource */
    static DataSource slowDb(long sleepMs) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(st);
        when(st.execute(anyString())).thenAnswer(inv -> {
            Thread.sleep(sleepMs);
            return false;
        });
        return ds;
    }

    DataSource wrap(long thresholdMs, DataSource ds) {
        return (DataSource) new SlowQueryLog(thresholdMs).postProcessAfterInitialization(ds, "dataSource");
    }

    @Test
    @DisplayName("느린 SQL은 값 없이 로그로 남긴다")
    void slowSqlIsLoggedWithoutValues(CapturedOutput out) throws SQLException {
        new JdbcTemplate(wrap(20, slowDb(60))).execute("SELECT *\n  FROM road_report_active\n WHERE guest_id = 'g-1'");

        assertThat(out.getOut()).contains("느린 쿼리").contains("SELECT * FROM road_report_active WHERE guest_id")
                .doesNotContain("\n  FROM");
    }

    @Test
    @DisplayName("빠른 SQL은 남기지 않는다")
    void fastSqlIsNotLogged(CapturedOutput out) throws SQLException {
        new JdbcTemplate(wrap(1_000, slowDb(0))).execute("SELECT 1");

        assertThat(out.getOut()).doesNotContain("느린 쿼리");
    }

    @Test
    @DisplayName("0이면 끄고, 연결 감싸기는 한 번만 한다")
    void zeroTurnsItOffAndWrapsOnlyOnce() throws SQLException {
        DataSource raw = slowDb(0);
        assertThat(wrap(0, raw)).isSameAs(raw);

        DataSource once = wrap(500, raw);
        assertThat(once).isInstanceOf(ProxyDataSource.class);
        assertThat(wrap(500, once)).isSameAs(once);
        Object notDb = "x";
        assertThat(new SlowQueryLog(500).postProcessAfterInitialization(notDb, "x")).isSameAs(notDb);
    }

    @Test
    @DisplayName("긴 SQL은 잘라서 남긴다")
    void longSqlIsCut() {
        QueryInfo q = new QueryInfo("SELECT " + "a, ".repeat(200) + "b FROM t");
        String s = SlowQueryLog.sql(List.of(q, new QueryInfo("SELECT 2")));
        assertThat(s).hasSize(SlowQueryLog.MAX_SQL + 1).endsWith("…").startsWith("SELECT a, a,");
        assertThat(SlowQueryLog.sql(List.of(new QueryInfo("SELECT 1"), new QueryInfo("SELECT  2"))))
                .isEqualTo("SELECT 1 ; SELECT 2");
    }
}
