package com.saferoute.route.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;

/** 도로망 읽기에서 DB 없이 확인할 수 있는 부분 */
class MysqlRoadGraphSourceTests {

    @Test
    void unpavedOnlyWhenConfirmed() {
        assertThat(MysqlRoadGraphSource.isUnpaved("dirt")).isTrue();
        assertThat(MysqlRoadGraphSource.isUnpaved("Gravel ")).isTrue();
        assertThat(MysqlRoadGraphSource.isUnpaved("cobblestone")).isTrue();
        // 포장, 다진 흙, 모름은 지나간다
        assertThat(MysqlRoadGraphSource.isUnpaved("asphalt")).isFalse();
        assertThat(MysqlRoadGraphSource.isUnpaved("compacted")).isFalse();
        assertThat(MysqlRoadGraphSource.isUnpaved(null)).isFalse();
    }

    @Test
    void surfaceFromRoadTypeWhenUnknown() {
        // 재질이 있으면 재질이 먼저
        assertThat(MysqlRoadGraphSource.classify("dirt", "residential", null, null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
        assertThat(MysqlRoadGraphSource.classify("concrete", "track", "grade3", null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        // 재질 모름: 인도·골목·차도는 포장
        assertThat(MysqlRoadGraphSource.classify(null, "footway", null, null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        assertThat(MysqlRoadGraphSource.classify(null, "residential", null, null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        // 농로: grade1만 포장, grade2~5·없음은 비포장
        assertThat(MysqlRoadGraphSource.classify(null, "track", "grade1", null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        assertThat(MysqlRoadGraphSource.classify(null, "track", "grade4", null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
        assertThat(MysqlRoadGraphSource.classify(null, "track", null, null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
        // 산길: 등산 난이도가 있으면 산행 길(재질과 상관없이), 없으면 확인 안 됨
        assertThat(MysqlRoadGraphSource.classify("asphalt", "path", null, "hiking", null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.TRAIL);
        assertThat(MysqlRoadGraphSource.classify(null, "path", null, null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNCONFIRMED);
        // 등산로 위 계단도 등산로 (2026-11-12). 등산 난이도가 없는 계단은 재질대로
        assertThat(MysqlRoadGraphSource.classify("rock", "steps", null, "mountain_hiking", null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.TRAIL);
        assertThat(MysqlRoadGraphSource.classify("rock", "steps", null, null, null, null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
    }

    @Test
    void fordMeansSteppingStonesOrWaterCrossing() {
        assertThat(MysqlRoadGraphSource.isFord("stepping_stones")).isTrue();
        assertThat(MysqlRoadGraphSource.isFord("yes")).isTrue();
        assertThat(MysqlRoadGraphSource.isFord("no")).isFalse();
        assertThat(MysqlRoadGraphSource.isFord(null)).isFalse();
    }

    @Test
    void lanesOnewayWidthFromTags() {
        // 폭은 단위 표기가 섞여 앞의 숫자만
        assertThat(MysqlRoadGraphSource.widthM("7")).isEqualTo(7.0);
        assertThat(MysqlRoadGraphSource.widthM("7 m")).isEqualTo(7.0);
        assertThat(MysqlRoadGraphSource.widthM("7.5")).isEqualTo(7.5);
        assertThat(MysqlRoadGraphSource.widthM("abc")).isNull();
        assertThat(MysqlRoadGraphSource.widthM(null)).isNull();
        assertThat(MysqlRoadGraphSource.lanes("4")).isEqualTo(4);
        assertThat(MysqlRoadGraphSource.lanes("abc")).isNull();
        // yes·1·true 그린 방향, -1 반대, no 등 양방, 없으면 null
        assertThat(MysqlRoadGraphSource.oneway("yes")).isEqualTo(1);
        assertThat(MysqlRoadGraphSource.oneway("1")).isEqualTo(1);
        assertThat(MysqlRoadGraphSource.oneway("true")).isEqualTo(1);
        assertThat(MysqlRoadGraphSource.oneway("-1")).isEqualTo(-1);
        assertThat(MysqlRoadGraphSource.oneway("no")).isZero();
        assertThat(MysqlRoadGraphSource.oneway(null)).isNull();
    }

    @Test
    void sidewalkFromTags() {
        assertThat(MysqlRoadGraphSource.sidewalk("right", null, null, null)).isEqualTo("right");
        assertThat(MysqlRoadGraphSource.sidewalk("none", null, null, null)).isEqualTo("no");
        // sidewalk가 없으면 sidewalk:both·left·right. separate(따로 그림)도 그쪽에 인도가 있다
        assertThat(MysqlRoadGraphSource.sidewalk(null, "separate", null, null)).isEqualTo("both");
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, "no", "separate")).isEqualTo("right");
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, "no", "no")).isEqualTo("no");
        // 한쪽만 알면 다른 쪽을 몰라 정하지 않는다
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, null, "yes")).isNull();
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, null, null)).isNull();
    }

    @Test
    void parsesLineString() {
        assertThat(MysqlRoadGraphSource.parseLineString("LINESTRING(126.93 35.14,126.931 35.141)"))
                .containsExactly(new double[] {126.93, 35.14}, new double[] {126.931, 35.141});
    }

    @Test
    void badAttachEdgeIsSkippedNotFatal() {
        // 지하 출구 한 줄을 못 읽어도 도로망 전체를 읽는다 (그 출구만 잇지 않음)
        assertThat(MysqlRoadGraphSource.attachEdge("127396")).isEqualTo(127396L);
        assertThat(MysqlRoadGraphSource.attachEdge("12.5")).isNull();
        assertThat(MysqlRoadGraphSource.attachEdge("없음")).isNull();
        assertThat(MysqlRoadGraphSource.attachEdge(null)).isNull();
    }

    @Test
    void plazaRowsAreReadAsPlainWalkways() {
        // 광장 길(w13)은 지하 길과 같은 표에서 읽되 지하 표시를 붙이지 않는다 (늘 요청 도로망에 들어감)
        // wheelchair = yes로 확인된 줄은 광장 표시도 없다(휠체어가 씀). 태그 없음은 광장 그대로 (11-16)
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new MysqlRoadGraphSource(jdbc, com.saferoute.route.RouteTestProps.load()).covers(126.92, 35.14);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues().get(0))
                .contains("x.reason IN ('underground', 'plaza')")
                .contains("x.reason = 'underground' AS underground")
                .contains("x.reason = 'plaza' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(x.tags, '$.wheelchair')), '') <> 'yes'"
                        + " AS plaza");
    }

    /** 도로망 읽기 첫 SQL의 한 줄을 읽은 길. cols에 없는 칸은 NULL (숫자는 0) */
    @SuppressWarnings("unchecked")
    static RoadEdge readRow(Map<String, Object> cols) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new MysqlRoadGraphSource(jdbc, com.saferoute.route.RouteTestProps.load()).covers(126.92, 35.14);
        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc, times(3)).query(anyString(), mapper.capture());
        Map<String, Object> all = new HashMap<>(Map.of("wkt", "LINESTRING(126.93 35.14,126.931 35.14)",
                "highway", "residential"));
        all.putAll(cols);
        ResultSet rs = mock(ResultSet.class, inv -> {
            Object v = inv.getArguments().length == 1 ? all.get((String) inv.getArgument(0)) : null;
            return switch (inv.getMethod().getName()) {
                case "getString" -> v == null ? null : v.toString();
                case "getInt" -> v == null ? 0 : ((Number) v).intValue();
                case "getLong" -> v == null ? 0L : ((Number) v).longValue();
                case "getDouble" -> v == null ? 0.0 : ((Number) v).doubleValue();
                case "getObject" -> v;
                default -> null;
            };
        });
        return (RoadEdge) mapper.getAllValues().get(0).mapRow(rs, 0);
    }

    @Test
    void sidewalkSideAndWidthFromDataWhenNoTag() throws Exception {
        // 태그가 없으면 보도 자료 쪽·폭. 폭 1.2m < 1.5m면 좁은 인도
        RoadEdge e = readRow(Map.of("length_m", 50.0, "w_side", "left", "w_width", 1.2));
        assertThat(e.sidewalk()).isEqualTo("left");
        assertThat(e.sidewalkWidthM()).isEqualTo(1.2);
        assertThat(e.penalty().get(Penalty.NARROW_SIDEWALK)).isEqualTo(1.0);
        // 자료가 no면 인도 없는 골목 (폭 없음)
        RoadEdge no = readRow(Map.of("length_m", 50.0, "w_side", "no", "w_width", 2.0));
        assertThat(no.sidewalk()).isEqualTo("no");
        assertThat(no.sidewalkWidthM()).isNull();
    }

    @Test
    void sidewalkTagWinsOverData() throws Exception {
        RoadEdge e = readRow(Map.of("length_m", 50.0, "sw", "no", "w_side", "both", "w_width", 1.0));
        assertThat(e.sidewalk()).isEqualTo("no");
        assertThat(e.sidewalkWidthM()).isNull();
        assertThat(e.penalty().get(Penalty.NARROW_SIDEWALK)).isZero();
    }

    @Test
    void shortEdgeIgnoresSidewalkData() throws Exception {
        // 20m보다 짧은 길(교차로 조각)은 자료를 쓰지 않아 모름
        RoadEdge e = readRow(Map.of("length_m", 10.0, "w_side", "both", "w_width", 1.0, "w_material", "unpaved"));
        assertThat(e.sidewalk()).isNull();
        assertThat(e.sidewalkWidthM()).isNull();
        assertThat(e.penalty().get(Penalty.UNPAVED)).isZero();
    }

    @Test
    void sidewalkMaterialIsTheWalkingSurfaceOfARoad() throws Exception {
        // 인도가 있는 차도는 인도 재질이 바닥: 자료 unpaved면 차도가 아스팔트여도 비포장
        assertThat(readRow(Map.of("length_m", 50.0, "surface", "asphalt", "w_side", "both", "w_material", "unpaved"))
                .penalty().get(Penalty.UNPAVED)).isEqualTo(1.0);
        // sidewalk:surface 태그가 자료보다 먼저
        assertThat(readRow(Map.of("length_m", 50.0, "surface", "asphalt", "w_side", "both", "w_material", "unpaved",
                "sw_surface", "paving_stones")).penalty().get(Penalty.UNPAVED)).isZero();
        // 인도가 없으면 차도 재질 그대로
        assertThat(readRow(Map.of("length_m", 50.0, "surface", "asphalt", "w_side", "no", "w_material", "unpaved"))
                .penalty().get(Penalty.UNPAVED)).isZero();
        // 보행로는 자기 재질 그대로
        assertThat(MysqlRoadGraphSource.classify("asphalt", "footway", null, null, "both", null, "unpaved"))
                .isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
    }

    @Test
    void sidewalkDataIsJoinedOnRoadEdgesOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new MysqlRoadGraphSource(jdbc, com.saferoute.route.RouteTestProps.load()).covers(126.92, 35.14);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues().get(0))
                .contains("LEFT JOIN edge_sidewalk w ON w.edge_id = e.id")
                .contains("w.side AS w_side, w.width_m AS w_width, w.material AS w_material")
                .contains("NULL AS w_side, NULL AS w_width, NULL AS w_material");
    }

    @Test
    void crosswalkPointsAreCrosswalkPoiOnly() {
        // 응답 crossAt의 큰 길 건너는 자리 (specs/014): 신호등 점(교차로 가운데)은 읽지 않는다
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThat(new MysqlRoadGraphSource(jdbc, com.saferoute.route.RouteTestProps.load()).crosswalkPoints(new BBox(126.9, 35.1, 127.0, 35.2))).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues().get(2)).isEqualTo("SELECT lon, lat FROM poi WHERE kind = 'crosswalk'");
    }
}
