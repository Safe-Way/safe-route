package com.saferoute.bus;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 버스 정류장·노선 표 읽기 SQL (가짜 DB) */
@DisplayName("버스 정류장·노선 SQL")
class BusRepositoryTests {

    private final FakeDb db = new FakeDb();

    @Test
    @DisplayName("가까운 정류장과 번호로 정류장을 읽는다")
    void stopsNearAndByIds() {
        db.rows("ST_Distance_Sphere", row("busstop_id", 3001, "name", "문화전당역", "next_busstop", "금남로4가",
                        "lat", 35.1465, "lon", 126.9203, "dist", 80.4))
                .rows("WHERE busstop_id IN", row("busstop_id", 3001, "name", "문화전당역", "next_busstop", null,
                        "lat", 35.1465, "lon", 126.9203), row("busstop_id", 3002, "name", "금남로4가",
                        "next_busstop", "문화전당역", "lat", 35.149, "lon", 126.915));
        BusStopRepository repo = new BusStopRepository(db.jdbc());

        assertThat(repo.near(35.147, 126.92, 300, 5))
                .containsExactly(new BusStop(3001, "문화전당역", "금남로4가", 35.1465, 126.9203, 80));
        assertThat(db.call("ST_Distance_Sphere").params().subList(3, 5)).containsExactly(300, 5);

        Map<Integer, BusStop> got = repo.byIds(List.of(3001, 3002));
        assertThat(got).hasSize(2);
        assertThat(got.get(3002).nextStop()).isEqualTo("문화전당역");
        assertThat(db.call("WHERE busstop_id IN").sql()).endsWith("IN (?,?)");
        assertThat(repo.byIds(List.of())).isEmpty();
    }

    private static Map<String, Object> line(int id, String name) {
        return row("line_id", id, "line_name", name, "dir_up_name", "수완지구", "dir_down_name", "조선대",
                "first_run", LocalTime.of(5, 40), "last_run", null, "run_interval", 15, "lat", 35.19, "lon", null);
    }

    @Test
    @DisplayName("노선을 번호와 종점으로 찾는다")
    void linesFindByNumberAndEnd() {
        db.rows("FROM bus_line l WHERE l.line_name = ?", line(10, "수완03"))
                .rows("dir_up_name LIKE ?", line(11, "07"))
                .rows("SELECT line_id FROM bus_line", row("line_id", 10), row("line_id", 11));
        BusLineRepository repo = new BusLineRepository(db.jdbc());

        assertThat(repo.find("03")).containsExactly(new BusLine(10, "수완03", "수완지구", "조선대", LocalTime.of(5, 40),
                null, 15, 35.19, null));
        // "1003"은 "03"이 아니게: 앞이 숫자면 뺌
        assertThat(db.call("l.line_name = ?").params()).containsExactly("03", "%03", "[0-9]03$", "03");
        assertThat(db.call("l.line_name = ?").sql()).endsWith("LIMIT 10");

        assertThat(repo.endingAt("조선대")).extracting(BusLine::id).containsExactly(11);
        assertThat(db.call("dir_up_name LIKE ?").params()).containsExactly("%조선대%", "%조선대%");
        assertThat(repo.allIds()).containsExactly(10, 11);
    }

    @Test
    @DisplayName("노선을 번호로 읽을 때 요청한 순서를 지킨다")
    void linesByIdsKeepAskedOrder() {
        db.rows("l.line_id IN", line(10, "수완03"), line(11, "07"));
        BusLineRepository repo = new BusLineRepository(db.jdbc());

        assertThat(repo.byIds(List.of(11, 99, 10))).extracting(BusLine::id).containsExactly(11, 10);
        assertThat(repo.byIds(List.of())).isEmpty();
    }
}
