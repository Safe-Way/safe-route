package com.saferoute.route.data;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * DB의 road_node·road_edge·edge_stat 표(A파트)에서 범위 안 도로망을 읽는다 (요구사항 8.1 3단계).
 * edge_stat은 원값만 두고 감점은 서버가 계산한다. 여기서 읽는 것은 지날 수 있는지에 쓰는 성질뿐이다:
 * <ul>
 *   <li>계단: road_edge.steps. 휠체어 경사로가 있는 계단(road_edge.wheelchair_ramp = 1)은 휠체어로 지날 수 있다
 *       (2026-11-03 결정). 0이나 모르면(NULL) 못 지난다</li>
 *   <li>보도턱: edge_stat.curb = 1(안 낮춤)이면 확인된 턱. 모르면(NULL) 턱 없음으로 본다 (2026-11-02 결정)</li>
 *   <li>육교: edge_stat.footbridge_ok = 0이면 휠체어로 못 지나는 육교. 모르면 지나갈 수 있다고 본다</li>
 *   <li>데이터 부족: edge_stat.data_gap 비트 중 시설 비트(1 밝기, 2 CCTV, 4 도움 지점, 8 지킴이집, 16 쉼 곳).
 *       감점에는 쓰지 않고 "확인 안 됨" 표시에만 쓴다</li>
 * </ul>
 * route.yml의 route.road-source가 mysql일 때 쓴다 (기본값). none이면 {@link NoRoadGraphSource}.
 */
@Component
@ConditionalOnProperty(name = "route.road-source", havingValue = "mysql")
public class MysqlRoadGraphSource implements RoadGraphSource {

    /** data_gap 중 시설 데이터 비트 (32 횡단 신호, 64 보도턱은 길 성질이라 뺀다) */
    static final int FACILITY_GAP_BITS = 1 | 2 | 4 | 8 | 16;
    private static final int IN_CHUNK = 1000;

    private final JdbcTemplate jdbc;
    private volatile BBox area;

    public MysqlRoadGraphSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 도로망을 넣은 지역(노드 전체를 감싼 사각형) 안인지. 처음 한 번 계산해 둔다 */
    @Override
    public boolean covers(double lon, double lat) {
        BBox a = area;
        if (a == null) {
            a = jdbc.queryForObject("SELECT MIN(lon), MIN(lat), MAX(lon), MAX(lat) FROM road_node",
                    (rs, i) -> rs.getObject(1) == null ? new BBox(0, 0, -1, -1)
                            : new BBox(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)));
            area = a;
        }
        return a.contains(lon, lat);
    }

    @Override
    public RoadGraph load(BBox box) {
        // 1. 범위에 걸치는 길과 성질. edge_stat 줄이 없는 길은 아직 계산 안 된 것이라 데이터 부족으로 본다
        String sql = "SELECT e.id, e.from_node, e.to_node, e.length_m, e.highway, e.steps, e.wheelchair_ramp, ST_AsText(e.geom) AS wkt,"
                + " s.edge_id AS has_stat, s.curb, s.footbridge_ok, s.data_gap"
                + " FROM road_edge e LEFT JOIN edge_stat s ON s.edge_id = e.id"
                + " WHERE MBRIntersects(ST_GeomFromText(?, 0), e.geom)";
        List<RoadEdge> edges = jdbc.query(sql, (rs, i) -> {
            Map<Penalty, Double> values = new EnumMap<>(Penalty.class);
            if (rs.getInt("steps") == 1) {
                values.put(Penalty.STAIRS, 1.0);
            }
            if (rs.getObject("curb") != null && rs.getInt("curb") == 1) {
                values.put(Penalty.CURB, 1.0);
            }
            if (rs.getObject("footbridge_ok") != null && rs.getInt("footbridge_ok") == 0) {
                values.put(Penalty.FOOTBRIDGE, 1.0);
            }
            boolean gap = rs.getObject("has_stat") == null || (rs.getInt("data_gap") & FACILITY_GAP_BITS) != 0;
            return new RoadEdge(rs.getLong("id"), rs.getLong("from_node"), rs.getLong("to_node"),
                    rs.getDouble("length_m"), rs.getString("highway"), parseLineString(rs.getString("wkt")),
                    new EdgePenalty(values, gap, rs.getInt("wheelchair_ramp") == 1));
        }, box.toWkt());

        // 2. 그 길들의 양 끝 노드
        Set<Long> ids = new LinkedHashSet<>();
        for (RoadEdge e : edges) {
            ids.add(e.from());
            ids.add(e.to());
        }
        Map<Long, double[]> nodes = new HashMap<>();
        List<Long> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            List<Long> chunk = all.subList(i, Math.min(all.size(), i + IN_CHUNK));
            String in = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            jdbc.query("SELECT id, lon, lat FROM road_node WHERE id IN (" + in + ")",
                    rs -> {
                        nodes.put(rs.getLong("id"), new double[] {rs.getDouble("lon"), rs.getDouble("lat")});
                    }, chunk.toArray());
        }
        return new RoadGraph(nodes, edges);
    }

    /** "LINESTRING(126.93 35.14,126.931 35.14)" → [{경도, 위도}, ...] */
    static List<double[]> parseLineString(String wkt) {
        String inside = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
        List<double[]> points = new ArrayList<>();
        for (String pair : inside.split(",")) {
            String[] xy = pair.trim().split("\\s+");
            points.add(new double[] {Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
        }
        return points;
    }
}
