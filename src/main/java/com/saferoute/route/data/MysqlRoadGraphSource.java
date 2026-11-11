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
import com.saferoute.route.engine.graph.UndergroundLinks;

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
 *   <li>차로 수·일방통행·폭 ({@link #lanes}, {@link #oneway}, {@link #widthM}, 2026-11-11): tags.lanes·oneway·width.
 *       앱이 경로 선을 인도 쪽으로 옮겨 그리는 거리 계산용이고 비용에는 쓰지 않는다</li>
 *   <li>지붕: road_edge.covered = 1이면 지붕 있는 길 (비 오는 날 기준, 2026-11-09)</li>
 *   <li>지하 길 (2026-11-09): road_edge_excluded의 reason = underground 줄(A파트가 그린 역 출구 계단·엘리베이터, 대합실,
 *       금남지하상가 통로). 같이 읽어 두되 비 올 때 운행 시간 안에만 요청 도로망에 넣고, 출구 점(road_node_excluded tags.attach_edge)을
 *       그 지상 길 위에 잇는다 ({@link UndergroundLinks}). 그 표의 id는 road_edge.id와 겹칠 수 있어 -(10억 + id)로 바꿔 쓴다</li>
 *   <li>보행자 광장 (2026-11-10, w13): 같은 표의 reason = plaza 줄(A파트가 OSM 광장 면에서 입구끼리 그은 곧은 길, 양 끝은 road_node).
 *       5·18 민주광장처럼 면으로만 있는 광장을 가로지르지 못해 돌아가던 것을 막는다. 실제로 늘 걷는 땅이라 날씨·시간·모드
 *       조건 없이 보통 평지 보도처럼 늘 넣는다(노인도 계단 없는 길로 봄, 사용자 결정 11-10). 휠체어는 쓰지 않는다
 *       ({@link Penalty#PLAZA}, route.yml wheelchair exclude): 높낮이를 모르는 선이라 높낮이가 다른 광장에서는 계단 양 끝을
 *       곧게 잇는 선이 있어 휠체어가 그 계단을 건너뛰게 된다 (11-10 확인, 광주 44줄, 문화전당 광장 포함. A파트 원래 메모대로).
 *       번호는 지하 길과 같은 -(10억 + id)</li>
 *   <li>건널 수 있는 곳 점 (2026-11-11, specs/011): poi kind = crosswalk ∪ road_node highway = traffic_signals.
 *       신호등 점을 빼면 신호 교차로 대부분이 "건널 수 없음"이 되어 시연 지역 경로 62%가 바뀐다(측정)</li>
 * </ul>
 * route.yml의 route.road-source가 mysql일 때 쓴다 (기본값). none이면 {@link NoRoadGraphSource}.
 */
@Component
@ConditionalOnProperty(name = "route.road-source", havingValue = "mysql")
public class MysqlRoadGraphSource implements RoadGraphSource {

    /** 휠체어가 지나기 어려운 바닥 (OSM surface 값). 흙·자갈·모래·바위와 울퉁불퉁한 돌길 */
    static final Set<String> UNPAVED_SURFACES = Set.of("unpaved", "dirt", "earth", "ground", "mud", "grass", "sand",
            "gravel", "fine_gravel", "pebblestone", "rock", "woodchips", "cobblestone");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MysqlRoadGraphSource.class);

    private final JdbcTemplate jdbc;
    /** 광주 전체 길. 처음 쓸 때 한 번 읽는다 (2026-11-09, 요청마다 DB에서 2만 개씩 읽던 것을 바꿈) */
    private volatile Network network;

    /**
     * 읽어 둔 도로망.
     *
     * @param edges 지상 길과 지하 길
     * @param boxes 길마다 선을 감싼 사각형 {서, 남, 동, 북}. 범위로 고를 때 쓴다
     * @param area  모든 길을 감싼 사각형 (도로망을 넣은 지역)
     * @param exits 지하 길의 역 출구 점과 붙일 지상 길
     * @param crossings 건널 수 있는 곳 점 {경도, 위도}
     */
    private record Network(List<RoadEdge> edges, double[][] boxes, BBox area, List<UndergroundLinks.Exit> exits,
            List<double[]> crossings) {
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

    /** 범위에 걸치는 지상 길 */
    @Override
    public RoadGraph load(BBox box) {
        return load(box, false);
    }

    /**
     * 범위에 걸치는 길. underground면 지하 길도 넣고 출구를 지상 길에 잇는다.
     * 길 양 끝 노드 좌표는 길 선의 첫 점·끝 점이다 (road_node와 81,332개 모두 같음, 2026-11-09 확인)
     */
    @Override
    public RoadGraph load(BBox box, boolean underground) {
        Network n = network();
        List<RoadEdge> edges = new ArrayList<>();
        for (int i = 0; i < n.edges().size(); i++) {
            double[] b = n.boxes()[i];
            if (b[0] > box.maxLon() || b[2] < box.minLon() || b[1] > box.maxLat() || b[3] < box.minLat()) {
                continue;
            }
            RoadEdge e = n.edges().get(i);
            if (underground || e.penalty().get(Penalty.UNDERGROUND) < 1) {
                edges.add(e);
            }
        }
        if (underground) {
            edges = UndergroundLinks.attach(edges, n.exits());
        }
        Map<Long, double[]> nodes = new HashMap<>();
        for (RoadEdge e : edges) {
            nodes.putIfAbsent(e.from(), e.geometry().get(0));
            nodes.putIfAbsent(e.to(), e.geometry().get(e.geometry().size() - 1));
        }
        return new RoadGraph(nodes, edges);
    }

    @Override
    public List<double[]> crossingPoints(BBox box) {
        return network().crossings().stream().filter(p -> box.contains(p[0], p[1])).toList();
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
        // 지하 길·광장 길 표에는 wheelchair_ramp(계단은 휠체어로 못 지남)·edge_stat(턱·육교 모름)이 없어 NULL로 읽는다.
        // 광장 길(plaza)은 지하 표시 0이라 늘 요청 도로망에 들어간다 (2026-11-10)
        String sql = select("e", "e.id", "e.wheelchair_ramp", "0", "0", "s.curb", "s.footbridge_ok")
                + " FROM road_edge e LEFT JOIN edge_stat s ON s.edge_id = e.id"
                + " UNION ALL "
                + select("x", "-(1000000000 + x.id)", "NULL", "x.reason = 'underground'", "x.reason = 'plaza'", "NULL", "NULL")
                + " FROM road_edge_excluded x WHERE x.reason IN ('underground', 'plaza')";
        List<RoadEdge> edges = jdbc.query(sql, (rs, i) -> {
            Map<Penalty, Double> values = new EnumMap<>(Penalty.class);
            if (rs.getInt("steps") == 1) {
                values.put(Penalty.STAIRS, 1.0);
            }
            if (rs.getInt("covered") == 1) {
                values.put(Penalty.COVERED, 1.0);
            }
            if (rs.getInt("underground") == 1) {
                values.put(Penalty.UNDERGROUND, 1.0);
            }
            if (rs.getInt("plaza") == 1) {
                values.put(Penalty.PLAZA, 1.0);
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
                            rs.getString("sw_right")), rs.getString("name"), lanes(rs.getString("lanes")),
                    oneway(rs.getString("oneway")), widthM(rs.getString("width")));
        });
        List<UndergroundLinks.Exit> exits = jdbc.query("SELECT id, lon, lat,"
                + " JSON_UNQUOTE(JSON_EXTRACT(tags, '$.attach_edge')) AS attach_edge FROM road_node_excluded"
                + " WHERE reason = 'underground' AND JSON_EXTRACT(tags, '$.attach_edge') IS NOT NULL",
                (rs, i) -> {
                    // 지하 데이터 한 줄이 맑은 날 엔진을 멈추게 하면 안 된다 (D4): 읽지 못한 출구만 건너뛴다. 좌표는 로그에 쓰지 않는다
                    Long attach = attachEdge(rs.getString("attach_edge"));
                    if (attach == null) {
                        log.warn("지하 출구 {}의 attach_edge를 읽지 못해 잇지 않음", rs.getLong("id"));
                        return null;
                    }
                    return new UndergroundLinks.Exit(rs.getLong("id"), new double[] {rs.getDouble("lon"), rs.getDouble("lat")},
                            attach);
                }).stream().filter(java.util.Objects::nonNull).toList();
        List<double[]> crossings = jdbc.query("SELECT lon, lat FROM poi WHERE kind = 'crosswalk'"
                + " UNION ALL SELECT lon, lat FROM road_node WHERE highway = 'traffic_signals'",
                (rs, i) -> new double[] {rs.getDouble("lon"), rs.getDouble("lat")});
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
        return new Network(edges, boxes, edges.isEmpty() ? null : new BBox(w, so, e, no), exits, crossings);
    }

    /** 지상(road_edge)·지하(road_edge_excluded) 두 표에서 같은 칸을 같은 이름으로 읽는 SELECT 부분. t는 표 별칭 */
    private static String select(String t, String id, String ramp, String underground, String plaza, String curb,
            String footbridge) {
        return "SELECT " + id + " AS id, " + t + ".from_node, " + t + ".to_node, " + t + ".length_m, " + t + ".highway, "
                + t + ".footway, " + t + ".steps, " + ramp + " AS wheelchair_ramp, " + t + ".surface, " + t + ".covered, "
                + t + ".name, " + underground + " AS underground, " + plaza + " AS plaza,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.tracktype')) AS tracktype,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.sac_scale')) AS sac_scale,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.ford')) AS ford,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.sidewalk')) AS sw,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.\"sidewalk:both\"')) AS sw_both,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.\"sidewalk:left\"')) AS sw_left,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.\"sidewalk:right\"')) AS sw_right,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.lanes')) AS lanes,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.oneway')) AS oneway,"
                + " JSON_UNQUOTE(JSON_EXTRACT(" + t + ".tags, '$.width')) AS width,"
                + " ST_AsText(" + t + ".geom) AS wkt, " + curb + " AS curb, " + footbridge + " AS footbridge_ok";
    }

    /** 출구 점 tags.attach_edge → 붙일 지상 길 번호. 비었거나 숫자가 아니면 null */
    static Long attachEdge(String v) {
        try {
            return v == null ? null : Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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

    /** 차로 수 (tags.lanes). 앞의 숫자만 읽는다. 없거나 1보다 작으면 null */
    static Integer lanes(String v) {
        Double n = leadingNumber(v);
        return n == null || n < 1 ? null : n.intValue();
    }

    /** 차도 폭(m, tags.width). "7", "7 m", "7.5"처럼 단위 표기가 섞여 앞의 숫자만 읽는다. 없거나 0 이하면 null */
    static Double widthM(String v) {
        Double n = leadingNumber(v);
        return n == null || n <= 0 ? null : n;
    }

    /** 일방통행 (tags.oneway): yes·1·true → 1(그린 방향), -1 → -1(반대 방향), 그 밖(no 등) → 0(양방), 없음 → null */
    static Integer oneway(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "yes", "1", "true" -> 1;
            case "-1" -> -1;
            default -> 0;
        };
    }

    private static final java.util.regex.Pattern LEADING_NUMBER = java.util.regex.Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)");

    private static Double leadingNumber(String v) {
        if (v == null) {
            return null;
        }
        java.util.regex.Matcher m = LEADING_NUMBER.matcher(v);
        return m.find() ? Double.valueOf(m.group(1)) : null;
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
