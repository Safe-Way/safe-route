package com.saferoute.route.data;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
 * DB의 road_edge·edge_stat 표(A파트)에서 광주 전체 도로망을 처음 한 번 읽어 두고, 요청마다 범위 안 길만 고른다
 * (요구사항 8.1 3단계).
 * edge_stat은 원값만 두고 감점은 서버가 계산한다. 여기서 읽는 것은 지날 수 있는지에 쓰는 성질뿐이다:
 * <ul>
 *   <li>계단: road_edge.steps. 휠체어 경사로가 있는 계단(road_edge.wheelchair_ramp = 1)은 휠체어로 지날 수 있다
 *       (2026-11-03 결정). 0이나 모르면(NULL) 못 지난다</li>
 *   <li>보도턱: edge_stat.curb = 1(안 낮춤)이면 확인된 턱. 모르면(NULL) 턱 없음으로 본다 (2026-11-02 결정)</li>
 *   <li>육교: edge_stat.footbridge_ok = 0이면 휠체어로 못 지나는 육교. 모르면 지나갈 수 있다고 본다</li>
 *   <li>징검다리: tags.ford가 있으면(stepping_stones 등, no 제외) 휠체어로 못 건넌다 (2026-11-04)</li>
 *   <li>바닥 ({@link #classify}, 2026-11-04 결정): 재질(surface)이 있으면 그것으로, 없으면 길 종류로 정한다.
 *       비포장 값은 {@link #UNPAVED_SURFACES}. 다진 흙(compacted)은 휠체어로 대개 지날 수 있어 넣지 않는다.
 *       재질을 모르면 인도·골목·차도·일반 길은 포장, 농로는 tracktype grade1만 포장(grade2~5·없음은 비포장),
 *       산길은 등산 난이도(sac_scale)가 있으면 산행 길(휠체어 제외), 없으면 포장으로 보되 "확인 안 됨"</li>
 *   <li>인도 쪽 ({@link #sidewalk}): tags.sidewalk(both·left·right·no), 없으면 sidewalk:both·left·right.
 *       경로 응답의 안내용이고 비용에는 쓰지 않는다</li>
 * </ul>
 * route.yml의 route.road-source가 mysql일 때 쓴다 (기본값). none이면 {@link NoRoadGraphSource}.
 */
@Component
@ConditionalOnProperty(name = "route.road-source", havingValue = "mysql")
public class MysqlRoadGraphSource implements RoadGraphSource {

    /** 휠체어가 지나기 어려운 바닥 (OSM surface 값). 흙·자갈·모래·바위와 울퉁불퉁한 돌길 */
    static final Set<String> UNPAVED_SURFACES = Set.of("unpaved", "dirt", "earth", "ground", "mud", "grass", "sand",
            "gravel", "fine_gravel", "pebblestone", "rock", "woodchips", "cobblestone");

    private final JdbcTemplate jdbc;
    /** 광주 전체 길. 처음 쓸 때 한 번 읽는다 (2026-11-09, 요청마다 DB에서 2만 개씩 읽던 것을 바꿈) */
    private volatile Network network;

    /**
     * 읽어 둔 도로망.
     *
     * @param boxes 길마다 선을 감싼 사각형 {서, 남, 동, 북}. 범위로 고를 때 쓴다
     * @param area  모든 길을 감싼 사각형 (도로망을 넣은 지역)
     */
    private record Network(List<RoadEdge> edges, double[][] boxes, BBox area) {
    }

    public MysqlRoadGraphSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 도로망을 넣은 지역(길 전체를 감싼 사각형) 안인지 */
    @Override
    public boolean covers(double lon, double lat) {
        BBox a = network().area();
        return a != null && a.contains(lon, lat);
    }

    /** 범위에 걸치는 길. 길 양 끝 노드 좌표는 길 선의 첫 점·끝 점이다 (road_node와 81,332개 모두 같음, 2026-11-09 확인) */
    @Override
    public RoadGraph load(BBox box) {
        Network n = network();
        List<RoadEdge> edges = new ArrayList<>();
        Map<Long, double[]> nodes = new HashMap<>();
        for (int i = 0; i < n.edges().size(); i++) {
            double[] b = n.boxes()[i];
            if (b[0] > box.maxLon() || b[2] < box.minLon() || b[1] > box.maxLat() || b[3] < box.minLat()) {
                continue;
            }
            RoadEdge e = n.edges().get(i);
            edges.add(e);
            nodes.putIfAbsent(e.from(), e.geometry().get(0));
            nodes.putIfAbsent(e.to(), e.geometry().get(e.geometry().size() - 1));
        }
        return new RoadGraph(nodes, edges);
    }

    /**
     * 처음 한 번 전체를 읽는다. 비어 있으면(적재 중) 기억하지 않고 다음 요청에 다시 읽는다.
     * shortcut: A가 도로망을 다시 넣으면 서버를 다시 켜야 반영된다 (공휴일·데이터 기준일과 같음). 자주 바뀌게 되면 주기적으로 다시 읽기
     */
    private Network network() {
        Network n = network;
        if (n == null) {
            synchronized (this) {
                n = network;
                if (n == null) {
                    n = read();
                    if (!n.edges().isEmpty()) {
                        network = n;
                    }
                }
            }
        }
        return n;
    }

    private Network read() {
        String sql = "SELECT e.id, e.from_node, e.to_node, e.length_m, e.highway, e.footway, e.steps, e.wheelchair_ramp, e.surface,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.tracktype')) AS tracktype,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.sac_scale')) AS sac_scale,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.ford')) AS ford,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.sidewalk')) AS sw,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.\"sidewalk:both\"')) AS sw_both,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.\"sidewalk:left\"')) AS sw_left,"
                + " JSON_UNQUOTE(JSON_EXTRACT(e.tags, '$.\"sidewalk:right\"')) AS sw_right,"
                + " ST_AsText(e.geom) AS wkt,"
                + " s.curb, s.footbridge_ok"
                + " FROM road_edge e LEFT JOIN edge_stat s ON s.edge_id = e.id";
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
            if (isFord(rs.getString("ford"))) {
                values.put(Penalty.FORD, 1.0);
            }
            if ("crossing".equals(rs.getString("footway"))) {
                values.put(Penalty.CROSSWALK, 1.0);
            }
            switch (classify(rs.getString("surface"), rs.getString("highway"), rs.getString("tracktype"),
                    rs.getString("sac_scale"))) {
                case UNPAVED -> values.put(Penalty.UNPAVED, 1.0);
                case TRAIL -> values.put(Penalty.TRAIL, 1.0);
                case UNCONFIRMED -> values.put(Penalty.SURFACE_UNKNOWN, 1.0);
                case PAVED -> { }
            }
            return new RoadEdge(rs.getLong("id"), rs.getLong("from_node"), rs.getLong("to_node"),
                    rs.getDouble("length_m"), rs.getString("highway"), parseLineString(rs.getString("wkt")),
                    new EdgePenalty(values,
                            rs.getObject("wheelchair_ramp") == null ? null : rs.getInt("wheelchair_ramp") == 1),
                    sidewalk(rs.getString("sw"), rs.getString("sw_both"), rs.getString("sw_left"),
                            rs.getString("sw_right")));
        });
        double[][] boxes = new double[edges.size()][];
        double w = Double.MAX_VALUE, so = Double.MAX_VALUE, e = -Double.MAX_VALUE, no = -Double.MAX_VALUE;
        for (int i = 0; i < edges.size(); i++) {
            double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (double[] p : edges.get(i).geometry()) {
                b[0] = Math.min(b[0], p[0]);
                b[1] = Math.min(b[1], p[1]);
                b[2] = Math.max(b[2], p[0]);
                b[3] = Math.max(b[3], p[1]);
            }
            boxes[i] = b;
            w = Math.min(w, b[0]);
            so = Math.min(so, b[1]);
            e = Math.max(e, b[2]);
            no = Math.max(no, b[3]);
        }
        return new Network(edges, boxes, edges.isEmpty() ? null : new BBox(w, so, e, no));
    }

    /** 길 바닥 구분 */
    enum Surface { PAVED, UNPAVED, TRAIL, UNCONFIRMED }

    /**
     * 바닥 구분. 산길의 등산 난이도가 가장 먼저, 그다음 재질, 재질이 없으면 길 종류로 정한다.
     *
     * @param tracktype 농로 단단함 (OSM tracktype: grade1 포장 ~ grade5 흙)
     * @param sacScale  산길 등산 난이도 (OSM sac_scale)
     */
    static Surface classify(String surface, String highway, String tracktype, String sacScale) {
        if ("path".equals(highway) && sacScale != null && !sacScale.isBlank()) {
            return Surface.TRAIL;
        }
        if (surface != null && !surface.isBlank()) {
            return isUnpaved(surface) ? Surface.UNPAVED : Surface.PAVED;
        }
        if ("track".equals(highway)) {
            return "grade1".equals(tracktype) ? Surface.PAVED : Surface.UNPAVED;
        }
        return "path".equals(highway) ? Surface.UNCONFIRMED : Surface.PAVED;
    }

    /** 징검다리·물을 건너는 길인지 (OSM ford). no면 아님 */
    static boolean isFord(String ford) {
        return ford != null && !ford.isBlank() && !"no".equalsIgnoreCase(ford.trim());
    }

    /**
     * 인도가 길의 어느 쪽에 있는지 (길을 그린 방향 기준): left, right, both, no. 모르면 null.
     * sidewalk 값이 먼저이고, 없으면 sidewalk:both·left·right(yes·separate = 있음, no = 없음)로 정한다.
     * separate는 인도를 따로 그렸다는 뜻이라 그쪽에 인도가 있다고 본다.
     */
    static String sidewalk(String sidewalk, String both, String left, String right) {
        String v = sidewalk == null ? "" : sidewalk.trim().toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "both", "left", "right", "no" -> {
                return v;
            }
            case "none" -> {
                return "no";
            }
            default -> { }
        }
        Boolean b = present(both);
        Boolean l = b != null ? b : present(left);
        Boolean r = b != null ? b : present(right);
        if (Boolean.TRUE.equals(l)) {
            return Boolean.TRUE.equals(r) ? "both" : Boolean.FALSE.equals(r) ? "left" : null;
        }
        if (Boolean.TRUE.equals(r)) {
            return Boolean.FALSE.equals(l) ? "right" : null;
        }
        return Boolean.FALSE.equals(l) && Boolean.FALSE.equals(r) ? "no" : null;
    }

    /** sidewalk:* 값 하나: yes·separate → 있음, no → 없음, 그 밖·없음 → 모름(null) */
    private static Boolean present(String v) {
        if (v == null) {
            return null;
        }
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "yes", "separate" -> true;
            case "no" -> false;
            default -> null;
        };
    }

    /** 확인된 비포장 재질인지. 값이 없으면 false */
    static boolean isUnpaved(String surface) {
        return surface != null && UNPAVED_SURFACES.contains(surface.trim().toLowerCase(java.util.Locale.ROOT));
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
