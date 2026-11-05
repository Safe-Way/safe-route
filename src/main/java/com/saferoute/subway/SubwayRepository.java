package com.saferoute.subway;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A파트의 지하철 표(019_transit.sql)를 읽는다: subway_station(역 좌표·편의시설), subway_timetable(열차 시간표),
 * holiday(공휴일, 015_holiday.sql).
 */
@Repository
public class SubwayRepository {

    private static final double M_PER_DEG = 111_320.0;

    private final JdbcTemplate jdbc;

    public SubwayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 점에서 radiusM 안의 역을 가까운 순으로 limit개까지 (버스 정류장 찾기와 같은 방식) */
    public List<SubwayStation> near(double lat, double lon, int radiusM, int limit) {
        double dLat = radiusM / M_PER_DEG;
        double dLon = radiusM / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        String box = String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                lon - dLon, lat - dLat, lon + dLon, lat - dLat, lon + dLon, lat + dLat, lon - dLon, lat + dLat,
                lon - dLon, lat - dLat);
        // 출입구(subway_entrance, A파트 022)는 역마다 묶어서 센다: elevator 1 있음, 0 없음, NULL 확인 안 됨
        String sql = "SELECT s.station_id, s.name, s.lat, s.lon, s.elevator_total, s.elevator_available,"
                + " s.elevator_disabled_gates, s.wheelchair_lift_total, s.wheelchair_lift_available,"
                + " s.accessible_toilet_total, s.accessible_toilet_available,"
                + " COALESCE(x.exit_total, 0) AS exit_total, COALESCE(x.ev_count, 0) AS ev_count,"
                + " COALESCE(x.ev_unknown, 0) AS ev_unknown, x.ev_nos,"
                + " ST_Distance_Sphere(s.geom, ST_SRID(POINT(?, ?), 0)) AS dist"
                + " FROM subway_station s LEFT JOIN ("
                + "   SELECT station_id, COUNT(*) AS exit_total, SUM(elevator = 1) AS ev_count,"
                + "   SUM(elevator IS NULL) AS ev_unknown,"
                + "   GROUP_CONCAT(CASE WHEN elevator = 1 THEN exit_no END ORDER BY CAST(exit_no AS UNSIGNED), exit_no"
                + "     SEPARATOR ',') AS ev_nos"
                + "   FROM subway_entrance WHERE station_id IS NOT NULL GROUP BY station_id"
                + " ) x ON x.station_id = s.station_id"
                + " WHERE MBRContains(ST_GeomFromText(?, 0), s.geom)"
                + " HAVING dist <= ? ORDER BY dist LIMIT ?";
        return jdbc.query(sql, (rs, n) -> new SubwayStation(rs.getInt("station_id"), rs.getString("name"),
                        rs.getDouble("lat"), rs.getDouble("lon"), (int) Math.round(rs.getDouble("dist")),
                        rs.getInt("elevator_total"), rs.getInt("elevator_available"),
                        rs.getString("elevator_disabled_gates"),
                        rs.getInt("wheelchair_lift_total"), rs.getInt("wheelchair_lift_available"),
                        rs.getInt("accessible_toilet_total"), rs.getInt("accessible_toilet_available"),
                        rs.getInt("exit_total"), rs.getInt("ev_count"), rs.getInt("ev_unknown"),
                        rs.getString("ev_nos")),
                lon, lat, box, radiusM, limit);
    }

    /** 그 날짜의 공휴일 이름, 공휴일이 아니면 null */
    public String holidayName(LocalDate day) {
        List<String> names = jdbc.queryForList("SELECT name FROM holiday WHERE day = ?", String.class, day);
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * 한 요일 종류의 시간표 전체 (약 수천 줄). 시각은 자정부터 몇 분째인지로 받는다.
     * TIME 칸에 24:10 같은 값이 있어도 깨지지 않게 TIME_TO_SEC로 숫자로 바꿔 읽는다.
     */
    public List<TimetableRow> timetable(String dayType) {
        List<TimetableRow> out = new ArrayList<>();
        jdbc.query("SELECT station_code, direction, end_station_name, TIME_TO_SEC(arrive_time) DIV 60 AS m"
                        + " FROM subway_timetable WHERE day_type = ?",
                rs -> {
                    out.add(new TimetableRow(rs.getInt("station_code"), rs.getInt("direction"),
                            rs.getString("end_station_name"), rs.getInt("m")));
                }, dayType);
        return out;
    }

    /** 시간표 한 줄: 역 코드, 방향(1 평동행, 2 녹동행), 종착역, 자정부터 몇 분째 도착 */
    public record TimetableRow(int stationCode, int direction, String endStation, int minute) {
    }
}
