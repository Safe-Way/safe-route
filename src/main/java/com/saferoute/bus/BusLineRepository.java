package com.saferoute.bus;

import java.sql.Time;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** bus_line 표에서 노선 번호로 찾기 (11-13 검색창 "버스 03", "03번 버스") */
@Repository
public class BusLineRepository {

    static final int MAX = 10;

    private final JdbcTemplate jdbc;

    public BusLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 노선 이름이 number 와 같거나 number 로 끝나는 노선 ("03" → 03, 수완03, 운림03 …). 같은 이름 먼저, 그다음 이름 순.
     * 기점 좌표는 정류장 표에서 이름이 같은 첫 정류장 (없으면 null)
     */
    public List<BusLine> find(String number) {
        return query("l.line_name = ? OR l.line_name LIKE ?", "(l.line_name = ?) DESC, l.line_name",
                number, "%" + number, number);
    }

    /**
     * 기점이나 종점 이름에 place 가 든 노선 ("수완" → 수완지구·수완동 종점 노선). 지나는 정류장 목록은 표에 없어서
     * 처음·끝만 본다 (11-13 "수완동으로 가는 버스")
     */
    public List<BusLine> endingAt(String place) {
        String like = "%" + place + "%";
        return query("l.dir_up_name LIKE ? OR l.dir_down_name LIKE ?", "l.line_name", like, like);
    }

    /** 모든 노선 번호 (정류장 순서를 받아 올 때) */
    public List<Integer> allIds() {
        return jdbc.queryForList("SELECT line_id FROM bus_line ORDER BY line_id", Integer.class);
    }

    /** 번호들의 노선, 넘긴 순서대로 (없는 번호는 빠짐) */
    public List<BusLine> byIds(List<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<BusLine> found = query("l.line_id IN (" + marks + ")", "l.line_id", ids.toArray());
        java.util.Map<Integer, BusLine> byId = new java.util.HashMap<>();
        found.forEach(l -> byId.put(l.id(), l));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private List<BusLine> query(String where, String order, Object... args) {
        String sql = "SELECT l.line_id, l.line_name, l.dir_up_name, l.dir_down_name, l.first_run, l.last_run,"
                + " l.run_interval,"
                + " (SELECT s.lat FROM bus_stop s WHERE s.name = l.dir_up_name ORDER BY s.busstop_id LIMIT 1) AS lat,"
                + " (SELECT s.lon FROM bus_stop s WHERE s.name = l.dir_up_name ORDER BY s.busstop_id LIMIT 1) AS lon"
                + " FROM bus_line l WHERE " + where + " ORDER BY " + order + " LIMIT " + MAX;
        return jdbc.query(sql, (rs, n) -> new BusLine(rs.getInt("line_id"), rs.getString("line_name"),
                        rs.getString("dir_up_name"), rs.getString("dir_down_name"), time(rs.getTime("first_run")),
                        time(rs.getTime("last_run")), (Integer) rs.getObject("run_interval", Integer.class),
                        (Double) rs.getObject("lat", Double.class), (Double) rs.getObject("lon", Double.class)),
                args);
    }

    private static java.time.LocalTime time(Time t) {
        return t == null ? null : t.toLocalTime();
    }
}
