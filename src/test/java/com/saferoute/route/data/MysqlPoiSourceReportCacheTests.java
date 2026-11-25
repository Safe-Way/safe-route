package com.saferoute.route.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.geo.BBox;

/** 신고 기억: 표시가 같으면 뷰를 다시 읽지 않고, 표시가 바뀌면 60초를 기다리지 않고 바로 다시 읽는다 (DB 없이 돈다) */
@DisplayName("신고 뷰 기억")
class MysqlPoiSourceReportCacheTests {

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("신고 뷰는 표시(stamp)가 같으면 기억한 것을 쓰고 바뀌면 바로 다시 읽는다")
    void rereadsViewAsSoonAsStampChanges() {
        RoadReport old = report(1, 126.92, 35.15);
        RoadReport ramp = report(2, 126.921, 35.151);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 두 번째 요청까지 표시가 같고, 세 번째 요청 전에 다른 서버가 신고를 받아 표시가 바뀐다
        when(jdbc.queryForObject(MysqlPoiSource.REPORTS_STAMP, String.class))
                .thenReturn("2026-11-16 1 1", "2026-11-16 1 1", "2026-11-16 2 3");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(old), List.of(old, ramp));
        MysqlPoiSource source = new MysqlPoiSource(jdbc);
        BBox box = new BBox(126.9, 35.1, 127.0, 35.2);

        assertThat(source.reports(box)).containsExactly(old);
        assertThat(source.reports(box)).containsExactly(old);
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));

        assertThat(source.reports(box)).containsExactly(old, ramp);
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class));
    }

    private static RoadReport report(long id, double lon, double lat) {
        return new RoadReport(id, "curb", lon, lat, LocalDate.of(2026, 12, 15), List.of("senior", "wheelchair"),
                List.of("adult", "child"));
    }
}
