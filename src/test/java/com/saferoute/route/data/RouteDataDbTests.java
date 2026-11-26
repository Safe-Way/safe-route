package com.saferoute.route.data;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.saferoute.route.RouteTestProps;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.testsupport.FakeDb;

/**
 * 도로망·행사·신고·경로 선호를 DB에서 읽는 부분을 가짜 DB로 끝까지
 * <p>무엇을: 길찾기 엔진에 데이터를 대는 {@link MysqlRoadGraphSource}(도로망), {@link MysqlPoiSource}(행사·신고),
 * {@link MysqlPreferenceSource}(경로 선호)가 DB 줄을 엔진의 길·감점·목록으로 바르게 바꾸고, DB가 안 될 때 버티는지.
 * <p>어떻게: 이름에 Db가 붙었지만 진짜 MySQL은 안 쓴다. FakeDb(가짜 JDBC)가 SQL 글자로 정해 둔 줄을 돌려준다.
 * 서버 시작 이벤트는 Mockito mock, DB가 도중에 끊기는 것은 JdbcTemplate spy 로 흉내 낸다. 그래서 CI·로컬 어디서나 DB 없이 돈다.
 * <p>왜: 칸 하나를 잘못 읽으면 휠체어가 계단·턱으로 안내되고, DB가 잠깐 끊길 때 오류가 새면 길찾기가 멈추거나 피하던 신고가 사라진다.
 */
@DisplayName("길찾기 데이터: 도로망·행사·신고·선호 읽기")
class RouteDataDbTests {

    /** 아래 network()의 길이 거의 다 들어가는 범위 {서, 남, 동, 북} */
    private static final BBox AREA = new BBox(126.91, 35.13, 126.93, 35.15);

    /** 도로망 SELECT 한 줄. 모든 칸을 비워 두고 바꿀 칸만 넣는다 */
    private static Map<String, Object> edge(long id, long from, long to, String wkt, Object... over) {
        Map<String, Object> m = row("id", id, "from_node", from, "to_node", to, "length_m", 50.0, "highway", "footway",
                "footway", null, "steps", 0, "wheelchair_ramp", null, "surface", "asphalt", "covered", 0, "name", null,
                "underground", 0, "plaza", 0, "tracktype", null, "sac_scale", null, "ford", null, "sw", null,
                "sw_both", null, "sw_left", null, "sw_right", null, "lanes", null, "oneway", null, "width", null,
                "sw_surface", null, "sw_both_surface", null, "w_side", null, "w_width", null, "w_material", null,
                "wkt", wkt, "curb", null, "footbridge_ok", null);
        Map<String, Object> o = new HashMap<>(row(over));
        o.forEach(m::put);
        return m;
    }

    /** 그래프의 길을 번호로 */
    private static Map<Long, RoadEdge> edges(RoadGraph g) {
        Map<Long, RoadEdge> m = new java.util.TreeMap<>();
        g.graph().edgeSet().forEach(e -> m.put(e.id(), e));
        return m;
    }

    /**
     * 시험용 작은 도로망. 줄마다 감점 하나씩을 켜 둔다:
     * 1번 = 계단·지붕·턱·육교·징검다리·횡단보도·좁은 인도를 모두 켠 길, -1000000001 = 지하 길, -1000000002 = 광장 길,
     * 3번 = 산길(sac_scale), 4번 = 재질 모르는 path, 5번 = 자갈길. 지하·광장 번호는 -(10억 + id) 규칙.
     * 출구 -3001 은 1번 길에 붙고, -3002 는 attach_edge 가 숫자가 아니라 건너뛰어야 한다.
     * 횡단보도 점 하나는 일부러 광주 밖(127.5, 36.0)에 둔다
     */
    private static FakeDb network() {
        return new FakeDb()
                .rows("UNION ALL",
                        edge(1, 10, 11, "LINESTRING(126.9200 35.1400,126.9220 35.1400)", "steps", 1, "covered", 1,
                                "curb", 1, "footbridge_ok", 0, "ford", "yes", "footway", "crossing", "wheelchair_ramp", 1,
                                "w_side", "both", "w_width", 1.2, "lanes", "2", "oneway", "yes", "width", "3 m",
                                "name", "금남로"),
                        edge(-1000000001L, 20, 21, "LINESTRING(126.9210 35.1395,126.9215 35.1390)", "underground", 1,
                                "highway", "corridor"),
                        edge(-1000000002L, 11, 12, "LINESTRING(126.9220 35.1400,126.9230 35.1410)", "plaza", 1,
                                "highway", "pedestrian"),
                        edge(3, 12, 13, "LINESTRING(126.9230 35.1410,126.9240 35.1420)", "highway", "path",
                                "surface", null, "sac_scale", "hiking"),
                        edge(4, 13, 14, "LINESTRING(126.9240 35.1420,126.9250 35.1430)", "highway", "path",
                                "surface", null),
                        edge(5, 14, 15, "LINESTRING(126.9250 35.1430,126.9260 35.1440)", "surface", "gravel"))
                .rows("attach_edge') IS NOT NULL", row("id", -3001L, "lon", 126.9210, "lat", 35.1400, "attach_edge", "1"),
                        row("id", -3002L, "lon", 126.9211, "lat", 35.1401, "attach_edge", "abc"))
                .rows("attach_edge') IS NULL", row("id", -3100L, "name", "금남지하상가"))
                .rows("FROM poi WHERE kind = 'crosswalk'", row("lon", 126.9201, "lat", 35.1401),
                        row("lon", 127.5, "lat", 36.0));
    }

    // 요청마다 광주 전체(2만 줄 넘음)를 다시 읽으면 길찾기가 느려지므로 한 번만 읽고 기억해야 한다.
    // 칸마다 감점으로 바르게 바뀌는지도 같이 본다: 하나라도 빠지면 휠체어·노인 경로가 위험한 길로 간다
    @Test
    @DisplayName("도로망을 한 번만 통째로 읽고 범위로 골라 쓴다")
    void readsWholeNetworkOnceAndPicksByBox() {
        FakeDb db = network();
        MysqlRoadGraphSource src = new MysqlRoadGraphSource(db.jdbc(), RouteTestProps.load());

        assertThat(src.covers(126.9205, 35.1400)).isTrue();
        // 도로망 지역은 길 전체를 감싼 사각형이다. 광주 밖 점(127.5, 36.0)은 그 밖이다
        assertThat(src.covers(127.5, 36.0)).isFalse();

        // 지상만 읽으면 지하 길(-1000000001)은 빠지고 광장 길(-1000000002)은 늘 들어간다
        Map<Long, RoadEdge> ground = edges(src.load(AREA));
        assertThat(ground.keySet()).containsExactly(-1000000002L, 1L, 3L, 4L, 5L);
        RoadEdge e1 = ground.get(1L);
        for (Penalty p : List.of(Penalty.STAIRS, Penalty.COVERED, Penalty.CURB, Penalty.FOOTBRIDGE, Penalty.FORD,
                Penalty.CROSSWALK, Penalty.NARROW_SIDEWALK)) {
            assertThat(e1.penalty().get(p)).as(p.name()).isEqualTo(1.0);
        }
        // 인도 태그가 없어 보도 자료(w_side·w_width)로 정한다. 길이 50m 가 sidewalk-data-min-edge-m(20m)보다 길어서 쓴다.
        // 폭 1.2m 는 narrow-sidewalk-m(1.5m)보다 좁아 위에서 NARROW_SIDEWALK 가 켜졌다
        assertThat(e1.sidewalk()).isEqualTo("both");
        assertThat(e1.sidewalkWidthM()).isEqualTo(1.2);
        assertThat(e1.lanes()).isEqualTo(2);
        assertThat(e1.oneway()).isEqualTo(1);
        // "3 m" 처럼 단위가 붙은 값도 앞 숫자만 읽는다
        assertThat(e1.widthM()).isEqualTo(3.0);
        assertThat(e1.penalty().wheelchairRamp()).isTrue();
        assertThat(ground.get(-1000000002L).penalty().get(Penalty.PLAZA)).isEqualTo(1.0);
        // 광장 길 표에는 경사로 칸이 없어 모름(null)
        assertThat(ground.get(-1000000002L).penalty().wheelchairRamp()).isNull();
        assertThat(ground.get(3L).penalty().get(Penalty.TRAIL)).isEqualTo(1.0);
        assertThat(ground.get(4L).penalty().get(Penalty.SURFACE_UNKNOWN)).isEqualTo(1.0);
        assertThat(ground.get(5L).penalty().get(Penalty.UNPAVED)).isEqualTo(1.0);

        // 지하 길을 넣으면 지하 길과, 출구(-3001)를 1번 길에 잇는 조각이 더해진다. attach_edge 를 못 읽은 출구는 건너뜀
        Map<Long, RoadEdge> all = edges(src.load(AREA, true));
        assertThat(all).containsKey(-1000000001L);
        assertThat(all.size()).isGreaterThan(ground.size() + 1);
        assertThat(all).doesNotContainKey(1L); // 1번 길은 출구 자리에서 나뉨

        // 작은 범위는 걸치는 길만
        assertThat(edges(src.load(new BBox(126.9255, 35.1435, 126.9270, 35.1450))).keySet()).containsExactly(5L);

        assertThat(src.undergroundPlaces()).containsExactly(Map.entry(-3100L, "금남지하상가"));
        assertThat(src.crosswalkPoints(AREA)).hasSize(1);

        // 한 번 읽은 도로망을 기억한다
        assertThat(db.calls("UNION ALL")).hasSize(1);
    }

    // A파트가 도로망을 적재하는 중이라 비어 있을 때 빈 것을 기억해 버리면, 적재가 끝나도 서버를 다시 켤 때까지
    // 모든 요청이 지역 밖이 된다. covers 와 load 가 각각 다시 읽어 UNION ALL 이 두 번 나가야 한다
    @Test
    @DisplayName("도로망이 비어 있으면 다음에 다시 읽는다")
    void emptyNetworkIsReadAgainNextTime() {
        FakeDb db = new FakeDb();
        MysqlRoadGraphSource src = new MysqlRoadGraphSource(db.jdbc(), RouteTestProps.load());

        assertThat(src.covers(126.92, 35.14)).isFalse();
        assertThat(src.load(AREA).isEmpty()).isTrue();
        assertThat(db.calls("UNION ALL")).hasSize(2);
    }

    // 배포 뒤 첫 요청이 광주 전체를 읽느라 기다리지 않게 서버가 뜰 때 미리 읽는다.
    // 하지만 테스트·적재 전용 실행(웹 서버 없음)까지 읽으면 쓸데없이 느려지고, DB가 없을 때 시작이 실패하면 안 된다
    @Test
    @DisplayName("미리 읽기는 웹 서버로 뜰 때만 한다")
    void warmUpOnlyForWebServer() {
        FakeDb db = network();
        MysqlRoadGraphSource src = new MysqlRoadGraphSource(db.jdbc(), RouteTestProps.load());

        // 웹 서버가 없는 실행: 보통 ApplicationContext mock
        ApplicationReadyEvent plain = mock(ApplicationReadyEvent.class);
        when(plain.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class));
        src.warmUp(plain);
        assertThat(db.calls("UNION ALL")).isEmpty();

        // 웹 서버로 뜬 실행: WebServerApplicationContext 도 구현한 mock 이어야 instanceof 검사를 지난다
        ApplicationReadyEvent web = mock(ApplicationReadyEvent.class);
        when(web.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class,
                withSettings().extraInterfaces(WebServerApplicationContext.class)));
        src.warmUp(web);
        assertThat(db.calls("UNION ALL")).hasSize(1);

        // DB가 안 되면 로그만 남기고 서버는 뜬다
        FakeDb down = new FakeDb().fails("UNION ALL", "Communications link failure");
        // 예외가 밖으로 나오지 않아야 한다 (나오면 이 줄에서 시험이 실패)
        new MysqlRoadGraphSource(down.jdbc(), RouteTestProps.load()).warmUp(web);
        assertThat(down.calls("UNION ALL")).hasSize(1);
    }

    // 행사·돌발은 경로에 덧붙이는 정보라, road_event 표가 없거나 오류여도 경로는 나가야 한다.
    // 줄은 RoadEvent 로 칸 그대로 바뀌어야 한다 (radius_m 정수 → 실수)
    @Test
    @DisplayName("행사를 읽고, 표가 없거나 오류면 빈 목록")
    void eventsAndFailures() {
        LocalDateTime at = LocalDateTime.of(2026, 12, 5, 18, 0);
        FakeDb db = new FakeDb().rows("FROM road_event", row("id", 5L, "kind", "festival", "title", "축제",
                "place", "양림", "message", "붐벼요", "lon", 126.915, "lat", 35.14, "radius_m", 300,
                "ends_at", at.plusDays(3)));
        List<RoadEvent> got = new MysqlPoiSource(db.jdbc()).events(AREA, at);
        assertThat(got).containsExactly(new RoadEvent(5L, "festival", "축제", "양림", "붐벼요", 126.915, 35.14, 300.0,
                at.plusDays(3)));

        FakeDb down = new FakeDb().fails("FROM road_event", "Table 'road_event' doesn't exist");
        assertThat(new MysqlPoiSource(down.jdbc()).events(AREA, at)).isEmpty();
        // 시설 종류를 하나도 안 고르면 SQL 없이 빈 목록 ("IN ()" 같은 틀린 SQL을 만들지 않게)
        assertThat(new MysqlPoiSource(db.jdbc()).within(AREA, Set.of())).isEmpty();
    }

    // 신고 목록은 바뀐 표시(REPORTS_STAMP: 날짜·개수·번호 합)를 요청마다 먼저 읽고, 같으면 기억한 목록을 쓴다.
    // 표시 읽기가 실패해도 이미 읽은 목록이 있으면 그대로 써야, DB가 잠깐 끊긴 사이 공사 구간 같은 신고를 다시 지나가지 않는다
    @Test
    @DisplayName("신고: DB가 끊기면 이전 목록을 그대로 쓴다")
    void reportsKeepOldListWhenDbDrops() {
        // 표시 값은 "오늘 날짜 개수 번호합" 모양의 아무 글이면 된다
        FakeDb db = new FakeDb().rows("COALESCE(SUM(id), 0)", row("s", "2026-12-05 1 7"))
                .rows("FROM road_report_active", row("spot_id", 7L, "report_type", "construction", "lon", 126.92,
                        "lat", 35.14, "ends_on", LocalDate.of(2026, 12, 11), "avoid_modes", "adult,child,senior,wheelchair",
                        "cost_modes", ""));
        // FakeDb 규칙은 먼저 정한 것이 이겨서 나중에 fails 를 더해도 안 먹는다.
        // 그래서 같은 src(같은 기억)를 쓴 채 도중에 끊기게 하려고 spy 로 감싼다
        JdbcTemplate jdbc = spy(db.jdbc());
        MysqlPoiSource src = new MysqlPoiSource(jdbc);
        List<RoadReport> first = src.reports(AREA);
        assertThat(first).hasSize(1);

        // 한 번 읽은 뒤 DB가 끊기면 낡았어도 이전 목록을 그대로 준다 (피하던 신고가 사라지지 않게)
        // 표시 읽기(queryForObject …, String.class)만 끊긴 것처럼 던지게 한다
        doThrow(new DataAccessResourceFailureException("Communications link failure"))
                .when(jdbc).queryForObject(anyString(), eq(String.class));
        assertThat(src.reports(AREA)).isEqualTo(first);

        // 처음부터 못 읽으면 빈 목록
        FakeDb down = new FakeDb().fails("COALESCE(SUM(id), 0)", "Communications link failure");
        assertThat(new MysqlPoiSource(down.jdbc()).reports(AREA)).isEmpty();
    }

    // 선호는 모드 위에 덧붙이는 것이라 없거나 DB 오류여도 NONE(기본값)으로 경로를 줘야 한다.
    // 게스트 번호는 앞뒤 공백을 지우고 소문자로 찾는다 (게스트 칸이 대소문자를 구별함, V38)
    @Test
    @DisplayName("경로 선호를 읽고, 없거나 오류면 기본값")
    void preferences() {
        // max_detour 칸은 DECIMAL 이라 BigDecimal 로 온다. rest_every_m 은 NULL(설정 안 함)
        FakeDb db = new FakeDb().rows("FROM user_profile WHERE guest_id",
                row("stairs_ok", 0, "max_detour", new BigDecimal("1.30"), "rest_every_m", null));
        MysqlPreferenceSource src = new MysqlPreferenceSource(db.jdbc());

        assertThat(src.find(" g1 ")).isEqualTo(new UserPreferences(0, 1.3, null));
        assertThat(db.call("FROM user_profile").params()).containsExactly("g1");
        assertThat(src.find(null)).isEqualTo(UserPreferences.NONE);
        assertThat(src.find(" ")).isEqualTo(UserPreferences.NONE);

        // 줄이 없는 사람(처음 쓰는 사람)
        assertThat(new MysqlPreferenceSource(new FakeDb().jdbc()).find("g2")).isEqualTo(UserPreferences.NONE);
        FakeDb down = new FakeDb().fails("FROM user_profile", "Communications link failure");
        assertThat(new MysqlPreferenceSource(down.jdbc()).find("g3")).isEqualTo(UserPreferences.NONE);
    }
}
