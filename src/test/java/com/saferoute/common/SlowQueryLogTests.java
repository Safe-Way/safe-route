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

/**
 * 느린 쿼리 로그: 가짜 DataSource에서 일부러 늦은 SQL을 돌려 WARN이 남는지 본다
 * <p>무엇을: {@link SlowQueryLog}가 DataSource를 감싸 기준(db.slow-query-ms)보다 오래 걸린 SQL만 로그로 남기는지.
 * <p>어떻게: DataSource·Connection·Statement를 Mockito mock으로 만들고 execute 안에서 Thread.sleep 으로 늦춘다.
 * 로그는 OutputCaptureExtension 으로 콘솔 출력을 잡아 확인한다. 진짜 DB는 안 쓴다.
 * <p>왜: 학교 MySQL의 slow log를 우리가 못 읽어서 이 로그가 인덱스를 더할 곳을 찾는 유일한 단서다.
 * 값(게스트 번호·위치)이 로그에 새면 개인정보가 남고, 감싸기를 두 번 하면 같은 SQL이 두 번 찍힌다.
 */
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

    /** 스프링이 dataSource 빈을 만든 뒤 하는 일(BeanPostProcessor)을 손으로 흉내 낸다 */
    DataSource wrap(long thresholdMs, DataSource ds) {
        return (DataSource) new SlowQueryLog(thresholdMs).postProcessAfterInitialization(ds, "dataSource");
    }

    // 기준 20ms, 실제 60ms 로 넉넉히 차이를 둬 CI가 느려도 흔들리지 않게 한다.
    // SQL에 'g-1' 같은 값을 넣어도, 로그는 줄바꿈을 한 칸으로 줄인 SQL 글자만 남기는지 본다
    @Test
    @DisplayName("느린 SQL은 값 없이 로그로 남긴다")
    void slowSqlIsLoggedWithoutValues(CapturedOutput out) throws SQLException {
        new JdbcTemplate(wrap(20, slowDb(60))).execute("SELECT *\n  FROM road_report_active\n WHERE guest_id = 'g-1'");

        assertThat(out.getOut()).contains("느린 쿼리").contains("SELECT * FROM road_report_active WHERE guest_id")
                .doesNotContain("\n  FROM");
    }

    // 모든 SQL을 남기면 로그가 넘쳐 진짜 느린 것을 못 찾는다. 기준 1초, 실제 0ms 로 확실히 빠르게 둔다
    @Test
    @DisplayName("빠른 SQL은 남기지 않는다")
    void fastSqlIsNotLogged(CapturedOutput out) throws SQLException {
        new JdbcTemplate(wrap(1_000, slowDb(0))).execute("SELECT 1");

        assertThat(out.getOut()).doesNotContain("느린 쿼리");
    }

    // 기준 0 은 "끄기" 설정이라 원래 DataSource를 그대로 돌려줘야 한다.
    // 이미 감싼 것을 또 감싸면 SQL마다 로그가 두 번 남고, DataSource가 아닌 빈은 건드리면 안 된다
    @Test
    @DisplayName("0이면 끄고, 연결 감싸기는 한 번만 한다")
    void zeroTurnsItOffAndWrapsOnlyOnce() throws SQLException {
        DataSource raw = slowDb(0);
        assertThat(wrap(0, raw)).isSameAs(raw);

        DataSource once = wrap(500, raw);
        assertThat(once).isInstanceOf(ProxyDataSource.class);
        assertThat(wrap(500, once)).isSameAs(once);
        // DataSource가 아닌 아무 빈
        Object notDb = "x";
        assertThat(new SlowQueryLog(500).postProcessAfterInitialization(notDb, "x")).isSameAs(notDb);
    }

    // 큰 IN 목록·묶음 INSERT가 한 줄 로그를 수천 자로 만들지 않게 MAX_SQL 자에서 자르고 "…"를 붙인다.
    // 묶음 실행처럼 SQL이 여럿이면 " ; " 로 잇고, 겹친 공백("SELECT  2")은 하나로 줄인다
    @Test
    @DisplayName("긴 SQL은 잘라서 남긴다")
    void longSqlIsCut() {
        // 600자가 넘는 SQL
        QueryInfo q = new QueryInfo("SELECT " + "a, ".repeat(200) + "b FROM t");
        String s = SlowQueryLog.sql(List.of(q, new QueryInfo("SELECT 2")));
        // MAX_SQL 자 + "…" 한 자
        assertThat(s).hasSize(SlowQueryLog.MAX_SQL + 1).endsWith("…").startsWith("SELECT a, a,");
        assertThat(SlowQueryLog.sql(List.of(new QueryInfo("SELECT 1"), new QueryInfo("SELECT  2"))))
                .isEqualTo("SELECT 1 ; SELECT 2");
    }
}
