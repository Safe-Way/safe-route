package com.saferoute.subway;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 지하철 표 읽기 SQL (가짜 DB) */
class SubwayRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final SubwayRepository repo = new SubwayRepository(db.jdbc());

    @Test
    void nearMapsStationWithExitCounts() {
        db.rows("FROM subway_station s", row("station_id", 105, "name", "문화전당", "lat", 35.1465, "lon", 126.9203,
                "elevator_total", 4, "elevator_available", 3, "elevator_disabled_gates", "2", "wheelchair_lift_total", 0,
                "wheelchair_lift_available", 0, "accessible_toilet_total", 1, "accessible_toilet_available", 1,
                "exit_total", 5, "ev_count", 2, "ev_unknown", 0, "ev_nos", "3,4", "dist", 120.6));

        List<SubwayStation> got = repo.near(35.147, 126.92, 500, 3);

        assertThat(got).containsExactly(new SubwayStation(105, "문화전당", 35.1465, 126.9203, 121, 4, 3, "2", 0, 0, 1,
                1, 5, 2, 0, "3,4"));
        List<Object> p = db.call("FROM subway_station s").params();
        assertThat(p.subList(0, 2)).containsExactly(126.92, 35.147);
        assertThat((String) p.get(2)).startsWith("POLYGON((126.91").endsWith("))");
        assertThat(p.subList(3, 5)).containsExactly(500, 3);
    }

    @Test
    void holidayName() {
        assertThat(repo.holidayName(LocalDate.of(2026, 11, 16))).isNull();
        db.rows("FROM holiday", row("name", "성탄절"));
        assertThat(repo.holidayName(LocalDate.of(2026, 12, 25))).isEqualTo("성탄절");
    }

    @Test
    void timetableReadsMinutes() {
        db.rows("FROM subway_timetable", row("station_code", 105, "direction", 1, "end_station_name", "평동", "m", 1450),
                row("station_code", 105, "direction", 2, "end_station_name", "녹동", "m", 330));

        assertThat(repo.timetable("weekday")).containsExactly(
                new SubwayRepository.TimetableRow(105, 1, "평동", 1450),
                new SubwayRepository.TimetableRow(105, 2, "녹동", 330));
        assertThat(db.call("FROM subway_timetable").params()).containsExactly("weekday");
    }
}
