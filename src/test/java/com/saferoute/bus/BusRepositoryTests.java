package com.saferoute.bus;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 버스 정류장·노선 표 읽기 SQL (가짜 DB).
 * <p>무엇을: 가까운 정류장 찾기, 번호로 정류장·노선 읽기, 노선 번호·종점 이름으로 노선 찾기(검색창 "버스 03").
 * <p>어떻게: FakeDb 에 SQL 조각별로 돌려줄 줄을 정해 두고, 결과가 BusStop·BusLine 으로 맞게 바뀌는지와
 * SQL 에 넘긴 값(반경·개수·검색 패턴)을 본다. 진짜 공간 계산은 하지 않는다.
 * <p>왜: 깨지면 "03번 버스"에 1003번이 섞여 나오거나, 버스 위치에 정류장 이름이 안 붙거나, 없는 칸(null)에서 앱 답이 깨진다.
 */
@DisplayName("버스 정류장·노선 SQL")
class BusRepositoryTests {

    private final FakeDb db = new FakeDb();

    @Test
    @DisplayName("가까운 정류장과 번호로 정류장을 읽는다")
    void stopsNearAndByIds() {
        // 좌표는 시연 지역 문화전당역 정류장 근처. 거리 80.4m 는 반올림해 80 이 되어야 한다
        db.rows("ST_Distance_Sphere", row("busstop_id", 3001, "name", "문화전당역", "next_busstop", "금남로4가",
                        "lat", 35.1465, "lon", 126.9203, "dist", 80.4))
                .rows("WHERE busstop_id IN", row("busstop_id", 3001, "name", "문화전당역", "next_busstop", null,
                        "lat", 35.1465, "lon", 126.9203), row("busstop_id", 3002, "name", "금남로4가",
                        "next_busstop", "문화전당역", "lat", 35.149, "lon", 126.915));
        BusStopRepository repo = new BusStopRepository(db.jdbc());

        assertThat(repo.near(35.147, 126.92, 300, 5))
                .containsExactly(new BusStop(3001, "문화전당역", "금남로4가", 35.1465, 126.9203, 80));
        // 넘긴 값 차례: 경도, 위도, 네모 범위, 반경, 개수 → 반경·개수가 바뀌지 않고 SQL 에 들어갔는지
        assertThat(db.call("ST_Distance_Sphere").params().subList(3, 5)).containsExactly(300, 5);

        Map<Integer, BusStop> got = repo.byIds(List.of(3001, 3002));
        assertThat(got).hasSize(2);
        assertThat(got.get(3002).nextStop()).isEqualTo("문화전당역");
        // 번호 수만큼 물음표를 만든다 (값을 SQL 에 직접 붙이지 않음)
        assertThat(db.call("WHERE busstop_id IN").sql()).endsWith("IN (?,?)");
        // 빈 목록이면 "IN ()" 문법 오류가 나므로 DB 에 가지 않고 빈 결과
        assertThat(repo.byIds(List.of())).isEmpty();
    }

    /** 노선 한 줄. 막차(last_run)·기점 경도(lon)는 일부러 null 로 둬서 빈 칸도 깨지지 않는지 본다 */
    private static Map<String, Object> line(int id, String name) {
        return row("line_id", id, "line_name", name, "dir_up_name", "수완지구", "dir_down_name", "조선대",
                "first_run", LocalTime.of(5, 40), "last_run", null, "run_interval", 15, "lat", 35.19, "lon", null);
    }

    @Test
    @DisplayName("노선을 번호와 종점으로 찾는다")
    void linesFindByNumberAndEnd() {
        // FakeDb 는 SQL 조각으로 답을 고른다: 번호 찾기 → 수완03, 종점 찾기 → 07, 전체 번호 → 10·11
        db.rows("FROM bus_line l WHERE l.line_name = ?", line(10, "수완03"))
                .rows("dir_up_name LIKE ?", line(11, "07"))
                .rows("SELECT line_id FROM bus_line", row("line_id", 10), row("line_id", 11));
        BusLineRepository repo = new BusLineRepository(db.jdbc());

        assertThat(repo.find("03")).containsExactly(new BusLine(10, "수완03", "수완지구", "조선대", LocalTime.of(5, 40),
                null, 15, 35.19, null));
        // "1003"은 "03"이 아니게: 앞이 숫자면 뺌
        // 값 차례: 같은 이름, "~03"으로 끝남, 앞이 숫자인 것 빼기(정규식), 같은 이름을 먼저 늘어놓는 ORDER BY
        assertThat(db.call("l.line_name = ?").params()).containsExactly("03", "%03", "[0-9]03$", "03");
        assertThat(db.call("l.line_name = ?").sql()).endsWith("LIMIT 10");

        assertThat(repo.endingAt("조선대")).extracting(BusLine::id).containsExactly(11);
        // 지나는 정류장 목록이 없어 기점(dir_up)·종점(dir_down) 두 칸에서만 찾는다
        assertThat(db.call("dir_up_name LIKE ?").params()).containsExactly("%조선대%", "%조선대%");
        assertThat(repo.allIds()).containsExactly(10, 11);
    }

    @Test
    @DisplayName("노선을 번호로 읽을 때 요청한 순서를 지킨다")
    void linesByIdsKeepAskedOrder() {
        // DB 는 번호 순(10, 11)으로 주지만 부른 쪽은 11, 99, 10 순서를 원한다. 표에 없는 99 는 빠져야 한다
        db.rows("l.line_id IN", line(10, "수완03"), line(11, "07"));
        BusLineRepository repo = new BusLineRepository(db.jdbc());

        assertThat(repo.byIds(List.of(11, 99, 10))).extracting(BusLine::id).containsExactly(11, 10);
        assertThat(repo.byIds(List.of())).isEmpty();
    }
}
