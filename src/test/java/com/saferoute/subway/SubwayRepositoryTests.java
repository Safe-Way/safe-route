package com.saferoute.subway;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 지하철 표 읽기 SQL (가짜 DB).
 * <p>무엇을: 가까운 역(편의시설·엘리베이터 출구 수 포함), 공휴일 이름, 요일별 열차 시간표 읽기.
 * <p>어떻게: FakeDb 에 SQL 조각별로 돌려줄 줄을 정해 두고, SubwayStation·TimetableRow 로 맞게 바뀌는지와
 * SQL 에 넘긴 값을 본다. 진짜 DB·공간 계산은 하지 않는다.
 * <p>왜: 깨지면 휠체어 이용자에게 엘리베이터 출구 수를 틀리게 알려 주거나, 공휴일에 평일 시간표로 다음 열차를 잘못 알려 준다.
 */
@DisplayName("지하철 표 읽기 SQL")
class SubwayRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final SubwayRepository repo = new SubwayRepository(db.jdbc());

    @Test
    @DisplayName("가까운 역을 출구 수와 함께 읽는다")
    void nearMapsStationWithExitCounts() {
        // 칸이 16개라 한 칸만 밀려도 엘리베이터 수와 리프트 수가 뒤바뀐다. 값을 서로 다르게 넣어 자리를 확인한다.
        // ev_count·ev_nos 는 subway_entrance 를 역마다 묶어 센 값(엘리베이터 출구 2개 = 3·4번)
        db.rows("FROM subway_station s", row("station_id", 105, "name", "문화전당", "lat", 35.1465, "lon", 126.9203,
                "elevator_total", 4, "elevator_available", 3, "elevator_disabled_gates", "2", "wheelchair_lift_total", 0,
                "wheelchair_lift_available", 0, "accessible_toilet_total", 1, "accessible_toilet_available", 1,
                "exit_total", 5, "ev_count", 2, "ev_unknown", 0, "ev_nos", "3,4", "dist", 120.6));

        List<SubwayStation> got = repo.near(35.147, 126.92, 500, 3);

        // 거리 120.6m 는 반올림해 121
        assertThat(got).containsExactly(new SubwayStation(105, "문화전당", 35.1465, 126.9203, 121, 4, 3, "2", 0, 0, 1,
                1, 5, 2, 0, "3,4"));
        List<Object> p = db.call("FROM subway_station s").params();
        // POINT 는 (경도, 위도) 차례라 위도부터 넣으면 거리가 엉뚱해진다
        assertThat(p.subList(0, 2)).containsExactly(126.92, 35.147);
        // 공간 인덱스를 타려고 먼저 반경 500m 네모(서쪽 경도 약 126.9145부터)로 줄인다
        assertThat((String) p.get(2)).startsWith("POLYGON((126.91").endsWith("))");
        assertThat(p.subList(3, 5)).containsExactly(500, 3);
    }

    @Test
    @DisplayName("공휴일 이름을 읽는다")
    void holidayName() {
        // 정해 둔 줄이 없으면 FakeDb 는 빈 결과를 주므로 첫 줄은 "공휴일 아님 = null" 을 본다.
        // 공휴일이면 평일이어도 휴일 시간표를 써야 해서 이름(있음/없음)이 중요하다
        assertThat(repo.holidayName(LocalDate.of(2026, 11, 16))).isNull();
        db.rows("FROM holiday", row("name", "성탄절"));
        assertThat(repo.holidayName(LocalDate.of(2026, 12, 25))).isEqualTo("성탄절");
    }

    @Test
    @DisplayName("시간표를 분 단위로 읽는다")
    void timetableReadsMinutes() {
        // 시각은 자정부터 몇 분째인지로 받는다. 1450분 = 24:10 (자정 넘은 막차), 330분 = 05:30 (첫차).
        // TIME 으로 읽으면 24:10 이 깨질 수 있어 SQL 에서 분으로 바꿔 읽는다
        db.rows("FROM subway_timetable", row("station_code", 105, "direction", 1, "end_station_name", "평동", "m", 1450),
                row("station_code", 105, "direction", 2, "end_station_name", "녹동", "m", 330));

        assertThat(repo.timetable("weekday")).containsExactly(
                new SubwayRepository.TimetableRow(105, 1, "평동", 1450),
                new SubwayRepository.TimetableRow(105, 2, "녹동", 330));
        assertThat(db.call("FROM subway_timetable").params()).containsExactly("weekday");
    }
}
