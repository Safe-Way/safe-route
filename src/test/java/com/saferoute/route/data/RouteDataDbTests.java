package com.saferoute.route.data;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
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

/** 도로망·행사·신고·경로 선호를 DB에서 읽는 부분을 가짜 DB로 끝까지 */
class RouteDataDbTests {

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

    @Test
    void readsWholeNetworkOnceAndPicksByBox() {
        FakeDb db = network();
        MysqlRoadGraphSource src = new MysqlRoadGraphSource(db.jdbc(), RouteTestProps.load());

        assertThat(src.covers(126.9205, 35.1400)).isTrue();
        assertThat(src.covers(127.5, 36.0)).isFalse();

        Map<Long, RoadEdge> ground = edges(src.load(AREA));
        assertThat(ground.keySet()).containsExactly(-1000000002L, 1L, 3L, 4L, 5L);
        RoadEdge e1 = ground.get(1L);
        for (Penalty p : List.of(Penalty.STAIRS, Penalty.COVERED, Penalty.CURB, Penalty.FOOTBRIDGE, Penalty.FORD,
                Penalty.CROSSWALK, Penalty.NARROW_SIDEWALK)) {
            assertThat(e1.penalty().get(p)).as(p.name()).isEqualTo(1.0);
        }
        assertThat(e1.sidewalk()).isEqualTo("both");
        assertThat(e1.sidewalkWidthM()).isEqualTo(1.2);
        assertThat(e1.lanes()).isEqualTo(2);
        assertThat(e1.oneway()).isEqualTo(1);
        assertThat(e1.widthM()).isEqualTo(3.0);
        assertThat(e1.penalty().wheelchairRamp()).isTrue();
        assertThat(ground.get(-1000000002L).penalty().get(Penalty.PLAZA)).isEqualTo(1.0);
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

    @Test
    void emptyNetworkIsReadAgainNextTime() {
        FakeDb db = new FakeDb();
        MysqlRoadGraphSource src = new MysqlRoadGraphSource(db.jdbc(), RouteTestProps.load());

        assertThat(src.covers(126.92, 35.14)).isFalse();
        assertThat(src.load(AREA).isEmpty()).isTrue();
        assertThat(db.calls("UNION ALL")).hasSize(2);
    }

    @Test
    void warmUpOnlyForWebServer() {
        FakeDb db = network();
        MysqlRoadGraphSource src = new MysqlRoadGraphSource(db.jdbc(), RouteTestProps.load());

        ApplicationReadyEvent plain = mock(ApplicationReadyEvent.class);
        when(plain.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class));
        src.warmUp(plain);
        assertThat(db.calls("UNION ALL")).isEmpty();

        ApplicationReadyEvent web = mock(ApplicationReadyEvent.class);
        when(web.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class,
                withSettings().extraInterfaces(WebServerApplicationContext.class)));
        src.warmUp(web);
        assertThat(db.calls("UNION ALL")).hasSize(1);

        // DB가 안 되면 로그만 남기고 서버는 뜬다
        FakeDb down = new FakeDb().fails("UNION ALL", "Communications link failure");
        new MysqlRoadGraphSource(down.jdbc(), RouteTestProps.load()).warmUp(web);
        assertThat(down.calls("UNION ALL")).hasSize(1);
    }

    @Test
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
        assertThat(new MysqlPoiSource(db.jdbc()).within(AREA, Set.of())).isEmpty();
    }

    @Test
    void reportsKeepOldListWhenDbDrops() {
        FakeDb db = new FakeDb().rows("COALESCE(SUM(id), 0)", row("s", "2026-12-05 1 7"))
                .rows("FROM road_report_active", row("spot_id", 7L, "report_type", "construction", "lon", 126.92,
                        "lat", 35.14, "ends_on", LocalDate.of(2026, 12, 11), "avoid_modes", "adult,child,senior,wheelchair",
                        "cost_modes", ""));
        MysqlPoiSource src = new MysqlPoiSource(db.jdbc());
        List<RoadReport> first = src.reports(AREA);
        assertThat(first).hasSize(1);

        // 처음부터 못 읽으면 빈 목록
        FakeDb down = new FakeDb().fails("COALESCE(SUM(id), 0)", "Communications link failure");
        assertThat(new MysqlPoiSource(down.jdbc()).reports(AREA)).isEmpty();
    }

    @Test
    void preferences() {
        FakeDb db = new FakeDb().rows("FROM user_profile WHERE guest_id",
                row("stairs_ok", 0, "max_detour", new BigDecimal("1.30"), "rest_every_m", null));
        MysqlPreferenceSource src = new MysqlPreferenceSource(db.jdbc());

        assertThat(src.find(" g1 ")).isEqualTo(new UserPreferences(0, 1.3, null));
        assertThat(db.call("FROM user_profile").params()).containsExactly("g1");
        assertThat(src.find(null)).isEqualTo(UserPreferences.NONE);
        assertThat(src.find(" ")).isEqualTo(UserPreferences.NONE);

        assertThat(new MysqlPreferenceSource(new FakeDb().jdbc()).find("g2")).isEqualTo(UserPreferences.NONE);
        FakeDb down = new FakeDb().fails("FROM user_profile", "Communications link failure");
        assertThat(new MysqlPreferenceSource(down.jdbc()).find("g3")).isEqualTo(UserPreferences.NONE);
    }
}
