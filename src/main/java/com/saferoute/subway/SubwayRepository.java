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

    /**
     * 출구별 주변 장소 (subway_exit_place, 공공데이터 2021-03-09). 이름에 place가 든 곳을 가까운 순으로.
     * station이 있으면 그 역만 (역 이름이 "문화전당(구도청)"처럼 길어도 앞이 같으면)
     */
    public List<ExitPlace> exitPlaces(String place, String station, int limit) {
        String like = "%" + place.replace(" ", "%") + "%";
        String sql = "SELECT station_name, place_name, exit_no, distance_m, walk_min, base_date FROM subway_exit_place"
                + " WHERE REPLACE(place_name, ' ', '') LIKE REPLACE(?, ' ', '')"
                + (station == null ? "" : " AND " + STATION_LIKE)
                + " ORDER BY CHAR_LENGTH(place_name), distance_m IS NULL, distance_m LIMIT ?";
        Object[] args = station == null ? new Object[] {like, limit} : new Object[] {like, flat(station) + "%", limit};
        return jdbc.query(sql, (rs, n) -> exitPlace(rs), args);
    }

    /** 한 역의 한 출구 주변 장소 ("문화전당역 5번 출구에 뭐 있어?"), 가까운 순 */
    public List<ExitPlace> placesAtExit(String station, String exitNo, int limit) {
        return jdbc.query("SELECT station_name, place_name, exit_no, distance_m, walk_min, base_date FROM subway_exit_place"
                        + " WHERE " + STATION_LIKE + " AND exit_no = ? ORDER BY distance_m IS NULL, distance_m LIMIT ?",
                (rs, n) -> exitPlace(rs), flat(station) + "%", exitNo, limit);
    }

    private static ExitPlace exitPlace(java.sql.ResultSet rs) throws java.sql.SQLException {
        int d = rs.getInt("distance_m");
        Integer dist = rs.wasNull() ? null : d;
        int w = rs.getInt("walk_min");
        Integer walk = rs.wasNull() ? null : w;
        java.sql.Date base = rs.getDate("base_date");
        return new ExitPlace(rs.getString("station_name"), rs.getString("place_name"), rs.getString("exit_no"), dist,
                walk, base == null ? null : base.toLocalDate());
    }

    /**
     * 역 출구별 연계 버스 (subway_exit_bus, 2021-03-09). 노선 이름은 "지원151(화순사평)", "첨단09(저)", "운림51" 꼴이라
     * 앞 한글(지원·첨단)은 있어도 없어도 되고, 뒤 괄호는 떼고 번호가 같은 것. "51"은 운림51은 맞고 지원151은 아님
     */
    public List<ExitBus> exitBuses(String station, String bus) {
        return jdbc.query("SELECT station_name, exit_no, bus_route, destination FROM subway_exit_bus"
                        + " WHERE " + STATION_LIKE + " AND REPLACE(bus_route, ' ', '') REGEXP ? AND exit_no IS NOT NULL"
                        + " ORDER BY CAST(exit_no AS UNSIGNED), exit_no",
                (rs, n) -> new ExitBus(rs.getString("station_name"), rs.getString("exit_no"), rs.getString("bus_route"),
                        rs.getString("destination")),
                flat(station) + "%", busRegex(bus));
    }

    /** 노선 이름 정규식 (MySQL REGEXP). "151" → ^[가-힣]*151(\(|$) */
    static String busRegex(String bus) {
        String no = bus.replaceAll("[^가-힣0-9-]", "");
        return (no.matches("^[가-힣].*") ? "^" : "^[가-힣]*") + no + "(\\(|$)";
    }

    /**
     * 역 이름 비교: 표마다 "학동증심사입구", "학동·증심사입구", "문화전당(구도청)"처럼 달라서 가운뎃점·띄어쓰기를 빼고 앞이 같으면 같은 역
     */
    private static final String STATION_LIKE = "REPLACE(REPLACE(station_name, '·', ''), ' ', '') LIKE ?";

    static String flat(String station) {
        return station == null ? null : station.replace("·", "").replace(" ", "");
    }

    /** 한 점에서 radiusM 안의 가장 가까운 지하철 출입구 (OSM subway_entrance, 출구 번호 있는 것만). 없으면 null */
    public Entrance nearestEntrance(double lat, double lon, int radiusM) {
        double dLat = radiusM / M_PER_DEG;
        double dLon = radiusM / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        String box = String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                lon - dLon, lat - dLat, lon + dLon, lat - dLat, lon + dLon, lat + dLat, lon - dLon, lat + dLat,
                lon - dLon, lat - dLat);
        List<Entrance> found = jdbc.query("SELECT s.name, e.exit_no, e.lat, e.lon, e.elevator,"
                        + " ST_Distance_Sphere(e.geom, ST_SRID(POINT(?, ?), 0)) AS dist"
                        + " FROM subway_entrance e JOIN subway_station s ON s.station_id = e.station_id"
                        + " WHERE e.exit_no IS NOT NULL AND MBRContains(ST_GeomFromText(?, 0), e.geom)"
                        + " HAVING dist <= ? ORDER BY dist LIMIT 1",
                (rs, n) -> {
                    int ev = rs.getInt("elevator");
                    Boolean elevator = rs.wasNull() ? null : ev == 1;
                    return new Entrance(rs.getString("name"), rs.getString("exit_no"), rs.getDouble("lat"),
                            rs.getDouble("lon"), (int) Math.round(rs.getDouble("dist")), elevator);
                },
                lon, lat, box, radiusM);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 한 역에서 엘리베이터로 드나드는 출구 번호 (subway_entrance elevator = 1), 작은 번호부터 */
    public List<String> elevatorExits(String station) {
        return jdbc.queryForList("SELECT e.exit_no FROM subway_entrance e JOIN subway_station s ON s.station_id = e.station_id"
                + " WHERE REPLACE(REPLACE(s.name, '·', ''), ' ', '') LIKE ? AND e.elevator = 1 AND e.exit_no IS NOT NULL"
                + " ORDER BY CAST(e.exit_no AS UNSIGNED), e.exit_no", String.class, flat(station) + "%");
    }

    /** 출구 주변 장소 한 줄. 거리·걷는 분은 없을 수 있다 */
    public record ExitPlace(String station, String place, String exitNo, Integer distanceM, Integer walkMin,
            LocalDate baseDate) {
    }

    /** 출구 연계 버스 한 줄 */
    public record ExitBus(String station, String exitNo, String route, String destination) {
    }

    /** 지하철 출입구 하나. elevator는 엘리베이터로 드나들면 true, 없으면 false, 확인 안 됐으면 null */
    public record Entrance(String station, String exitNo, double lat, double lon, int distanceM, Boolean elevator) {
    }

    /** 시간표 한 줄: 역 코드, 방향(1 평동행, 2 녹동행), 종착역, 자정부터 몇 분째 도착 */
    public record TimetableRow(int stationCode, int direction, String endStation, int minute) {
    }
}
