package com.saferoute.route;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.jdbc.core.JdbcTemplate;

import com.saferoute.ingest.FacilityTable;
import com.saferoute.route.data.MysqlFacilitySource;
import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;

/**
 * 조선대 주변 OSM 파일 + 팀 DB의 시설로 도로망과 길별 감점을 만든다 (A파트 일을 시험 삼아 해 보는 도구, 테스트 전용).
 *
 * <ol>
 *   <li>OSM에서 걸을 수 있는 길만 골라, 길이 만나는 점마다 잘라 road_edge로 만든다</li>
 *   <li>서로 이어지지 않은 작은 조각은 버리고 가장 큰 덩어리만 남긴다 (1주차 확인: 98%가 한 덩어리)</li>
 *   <li>길마다 감점(요구사항 6.1·6.4 공식)을 계산한다</li>
 * </ol>
 */
final class ChosunNetworkBuilder {

    static final Path OSM = Path.of("조선대_데이터확인", "raw", "highway.osm");

    /** 걸을 수 있는 길 종류 (construction 등은 뺌) */
    static final Set<String> WALKABLE = Set.of("footway", "path", "pedestrian", "steps", "residential", "service",
            "living_street", "unclassified", "track", "cycleway", "tertiary", "tertiary_link", "secondary",
            "secondary_link", "primary", "primary_link", "trunk", "trunk_link");
    static final Set<String> PEDESTRIAN = Set.of("footway", "path", "pedestrian", "steps", "cycleway");

    record Node(long id, double lon, double lat, Map<String, String> tags) {
        double[] p() {
            return new double[] {lon, lat};
        }
    }

    record Way(long id, List<Long> nodes, Map<String, String> tags) {
        String highway() {
            return tags.get("highway");
        }
    }

    /** 만든 길 하나 */
    record Edge(long id, long from, long to, List<Long> nodeIds, List<double[]> line, double lengthM, Way way) {
    }

    /** 시설 점 하나 (count: 등 개수·카메라 대수, flag: 휠체어 가능·보도턱 낮춤 1/0/NULL) */
    record Spot(double lon, double lat, int count, Integer flag) {
        double[] p() {
            return new double[] {lon, lat};
        }
    }

    /** 읽을 OSM 파일 (기본: 조선대 주변) */
    final Path osm;

    ChosunNetworkBuilder() {
        this(OSM);
    }

    ChosunNetworkBuilder(Path osm) {
        this.osm = osm;
    }

    final Map<Long, Node> nodes = new HashMap<>();
    final List<Way> ways = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    final Map<Long, Map<Penalty, Double>> penalties = new HashMap<>();
    final Map<Long, Boolean> dataGap = new HashMap<>();
    final Map<String, Integer> stats = new LinkedHashMap<>();
    /** 공공데이터로 만든 육교 길의 감점 (육교·계단) */
    final Map<Long, Map<Penalty, Double>> syntheticPenalty = new HashMap<>();
    /** 육교 처리 기록 (육교마다 조건 검사 결과) */
    final List<String> footbridgeLog = new ArrayList<>();

    static final Set<String> MOTOR = Set.of("trunk", "trunk_link", "primary", "primary_link", "secondary",
            "secondary_link", "tertiary", "tertiary_link");

    // ------------------------------------------------------------------ 1. OSM 읽기

    void readOsm() throws IOException, XMLStreamException {
        try (InputStream in = Files.newInputStream(osm)) {
            XMLStreamReader r = XMLInputFactory.newInstance().createXMLStreamReader(in);
            Map<String, String> tags = null;
            List<Long> refs = null;
            long id = 0;
            double lon = 0, lat = 0;
            boolean inNode = false, inWay = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "node" -> {
                            inNode = true;
                            id = Long.parseLong(r.getAttributeValue(null, "id"));
                            lon = Double.parseDouble(r.getAttributeValue(null, "lon"));
                            lat = Double.parseDouble(r.getAttributeValue(null, "lat"));
                            tags = new HashMap<>();
                        }
                        case "way" -> {
                            inWay = true;
                            id = Long.parseLong(r.getAttributeValue(null, "id"));
                            tags = new HashMap<>();
                            refs = new ArrayList<>();
                        }
                        case "nd" -> {
                            if (inWay) {
                                refs.add(Long.parseLong(r.getAttributeValue(null, "ref")));
                            }
                        }
                        case "tag" -> {
                            if (tags != null) {
                                tags.put(r.getAttributeValue(null, "k"), r.getAttributeValue(null, "v"));
                            }
                        }
                        default -> {
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (inNode && r.getLocalName().equals("node")) {
                        nodes.put(id, new Node(id, lon, lat, tags));
                        inNode = false;
                        tags = null;
                    } else if (inWay && r.getLocalName().equals("way")) {
                        ways.add(new Way(id, refs, tags));
                        inWay = false;
                        tags = null;
                    }
                }
            }
        }
        stats.put("OSM 점", nodes.size());
        stats.put("OSM 길", ways.size());
    }

    static boolean walkable(Way w) {
        String hw = w.highway();
        if (hw == null || !WALKABLE.contains(hw) || "yes".equals(w.tags().get("area"))) {
            return false;
        }
        String foot = w.tags().get("foot");
        String access = w.tags().get("access");
        if ("no".equals(foot)) {
            return false;
        }
        return !("private".equals(access) || "no".equals(access)) || "yes".equals(foot);
    }

    // ------------------------------------------------------------------ 2. 길이 만나는 점마다 자르기

    void split() {
        List<Way> walk = ways.stream().filter(ChosunNetworkBuilder::walkable)
                .filter(w -> w.nodes().stream().allMatch(nodes::containsKey)).toList();
        Map<Long, Integer> used = new HashMap<>();
        for (Way w : walk) {
            for (int i = 0; i < w.nodes().size(); i++) {
                long n = w.nodes().get(i);
                // 길의 양 끝은 꼭 자른다 (두 번 센 것과 같게)
                used.merge(n, (i == 0 || i == w.nodes().size() - 1) ? 2 : 1, Integer::sum);
            }
        }
        for (Way w : walk) {
            int seq = 0;
            int start = 0;
            for (int i = 1; i < w.nodes().size(); i++) {
                long n = w.nodes().get(i);
                if (used.get(n) >= 2 || i == w.nodes().size() - 1) {
                    List<Long> ids = List.copyOf(w.nodes().subList(start, i + 1));
                    List<double[]> line = ids.stream().map(x -> nodes.get(x).p()).toList();
                    double len = Geo.length(line);
                    if (len > 0) {
                        edges.add(new Edge(w.id() * 1000 + seq++, ids.get(0), ids.get(ids.size() - 1), ids, line, len, w));
                    }
                    start = i;
                }
            }
        }
        stats.put("걸을 수 있는 OSM 길", walk.size());
        stats.put("자른 길(전체)", edges.size());
        keepLargestComponent();
        stats.put("자른 길(가장 큰 덩어리)", edges.size());
    }

    void keepLargestComponent() {
        Map<Long, List<Edge>> adj = new HashMap<>();
        for (Edge e : edges) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            adj.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
        Set<Long> seen = new HashSet<>();
        Set<Long> best = Set.of();
        for (Long start : adj.keySet()) {
            if (seen.contains(start)) {
                continue;
            }
            Set<Long> comp = new HashSet<>();
            ArrayDeque<Long> q = new ArrayDeque<>(List.of(start));
            seen.add(start);
            while (!q.isEmpty()) {
                long n = q.poll();
                comp.add(n);
                for (Edge e : adj.get(n)) {
                    long other = e.from() == n ? e.to() : e.from();
                    if (seen.add(other)) {
                        q.add(other);
                    }
                }
            }
            if (comp.size() > best.size()) {
                best = comp;
            }
        }
        Set<Long> keep = best;
        edges.removeIf(e -> !keep.contains(e.from()));
    }

    // ------------------------------------------------------------------ 2-1. 육교 연결

    /** 기준점(ref) 둘레를 평면으로 펴서 m 단위 좌표로 */
    static double[] xy(double[] p, double[] ref) {
        double mLat = 111_195.0;
        double mLon = mLat * Math.cos(Math.toRadians(ref[1]));
        return new double[] {(p[0] - ref[0]) * mLon, (p[1] - ref[1]) * mLat};
    }

    /** 두 선분 a-b, c-d가 교차하는지 (평면) */
    static boolean segmentsCross(double[] a, double[] b, double[] c, double[] d) {
        double d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** 선분 a-b가 선(점 목록)을 가로지르는지 */
    static boolean crossesLine(double[] a, double[] b, List<double[]> line, double[] ref) {
        double[] pa = xy(a, ref), pb = xy(b, ref);
        for (int i = 1; i < line.size(); i++) {
            if (segmentsCross(pa, pb, xy(line.get(i - 1), ref), xy(line.get(i), ref))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 육교 표의 육교를 도로망에 연결한다 (결정 문서 B4의 조건).
     * OSM에 육교 길이 없을 때만, 조건 1~6을 모두 통과하고 사람이 "있음"으로 확인한 육교만 길로 만든다.
     */
    void addFootbridges(JdbcTemplate teamDb, Map<String, String> review) {
        List<double[]> pts = edges.stream().flatMap(e -> e.line().stream()).toList();
        BBox box = BBox.around(pts, 0);
        List<Map<String, Object>> rows = teamDb.queryForList(
                "SELECT name, lon, lat, kind, wheelchair_ok, length_m FROM footbridge"
                        + " WHERE MBRContains(ST_GeomFromText(?, 0), geom)", box.toWkt());
        int created = 0;
        long nextId = -1000;
        for (Map<String, Object> r : rows) {
            String name = String.valueOf(r.get("name"));
            double[] p = {((Number) r.get("lon")).doubleValue(), ((Number) r.get("lat")).doubleValue()};
            String status = review.getOrDefault(name, "미확인");
            StringBuilder log = new StringBuilder(name).append(": ");

            // 0. OSM에 이미 육교 길이 있으면 만들지 않는다 (감점 계산에서 그 길에 정보를 붙인다)
            boolean osmBridge = edges.stream().anyMatch(e -> "yes".equals(e.way().tags().get("bridge"))
                    && PEDESTRIAN.contains(e.way().highway()) && Geo.distanceToLine(p, e.line()) <= 40);
            if (osmBridge) {
                footbridgeLog.add(log.append("조건0 OSM에 육교 길이 이미 있음 → 그 길에 정보만 붙임").toString());
                continue;
            }
            // 1. 30m 안에 건너는 큰길
            List<Edge> roads = edges.stream().filter(e -> MOTOR.contains(e.way().highway())
                    && Geo.distanceToLine(p, e.line()) <= 30).toList();
            if (roads.isEmpty()) {
                footbridgeLog.add(log.append("조건1 실패: 30m 안에 큰길 없음").toString());
                continue;
            }
            Edge nearest = roads.stream().min(java.util.Comparator.comparingDouble(e -> Geo.distanceToLine(p, e.line()))).get();
            String roadName = nearest.way().tags().get("name");
            // 왕복 차도면 같은 이름의 큰길 줄을 모두 건너야 한다
            List<Edge> mustCross = roadName == null ? List.of(nearest)
                    : roads.stream().filter(e -> roadName.equals(e.way().tags().get("name"))).toList();
            log.append("큰길 ").append(roadName).append(" ").append(mustCross.size()).append("줄, ");

            Object lenObj = r.get("length_m");
            Double len = null;
            try {
                len = lenObj == null ? null : Double.parseDouble(lenObj.toString());
            } catch (NumberFormatException ignored) {
                // 길이를 모르면 조건 4를 검사할 수 없다
            }
            if (len == null || len <= 0) {
                footbridgeLog.add(log.append("조건4 실패: 표에 육교 길이 없음").toString());
                continue;
            }
            // 2. 큰길 양쪽의 보행 노드 (큰길 위의 노드는 뺀다)
            Set<Long> roadNodes = new HashSet<>();
            roads.forEach(e -> roadNodes.addAll(e.nodeIds()));
            double radius = Math.max(40, len);
            Set<Long> cand = new HashSet<>();
            for (Edge e : edges) {
                if (MOTOR.contains(e.way().highway())) {
                    continue;
                }
                for (long n : List.of(e.from(), e.to())) {
                    if (!roadNodes.contains(n) && Geo.distance(p, nodes.get(n).p()) <= radius) {
                        cand.add(n);
                    }
                }
            }
            // 큰길의 방향으로 양쪽을 나눈다
            double[] r0a = null, r0b = null;
            double bestSeg = Double.MAX_VALUE;
            for (int i = 1; i < nearest.line().size(); i++) {
                double d = Geo.distanceToSegment(p, nearest.line().get(i - 1), nearest.line().get(i));
                if (d < bestSeg) {
                    bestSeg = d;
                    r0a = nearest.line().get(i - 1);
                    r0b = nearest.line().get(i);
                }
            }
            double[] ra = xy(r0a, p), rb = xy(r0b, p);
            List<Long> left = new ArrayList<>(), right = new ArrayList<>();
            for (long n : cand) {
                (cross(ra, rb, xy(nodes.get(n).p(), p)) > 0 ? left : right).add(n);
            }
            if (left.isEmpty() || right.isEmpty()) {
                footbridgeLog.add(log.append("조건2 실패: 큰길 한쪽에 보행 노드 없음 (왼쪽 ").append(left.size())
                        .append(", 오른쪽 ").append(right.size()).append(")").toString());
                continue;
            }
            // 3~5. 가로지름, 길이, 위치
            record Pair(long a, long b, double dist, double score) {
            }
            List<Pair> ok = new ArrayList<>();
            int fail3 = 0, fail4 = 0, fail5 = 0;
            for (long a : left) {
                for (long b : right) {
                    double[] pa = nodes.get(a).p(), pb = nodes.get(b).p();
                    if (!mustCross.stream().allMatch(e -> crossesLine(pa, pb, e.line(), p))) {
                        fail3++;
                        continue;
                    }
                    double dist = Geo.distance(pa, pb);
                    if (dist < 0.5 * len || dist > 2 * len) {
                        fail4++;
                        continue;
                    }
                    double[] mid = {(pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2};
                    double off = Geo.distance(mid, p);
                    if (off > 20) {
                        fail5++;
                        continue;
                    }
                    ok.add(new Pair(a, b, dist, Math.abs(dist - len) + off));
                }
            }
            if (ok.isEmpty()) {
                footbridgeLog.add(log.append("조건3~5 실패 (가로지르지 않음 ").append(fail3).append(", 길이 안 맞음 ")
                        .append(fail4).append(", 위치 안 맞음 ").append(fail5).append(")").toString());
                continue;
            }
            ok.sort(java.util.Comparator.comparingDouble(Pair::score));
            Pair best = ok.get(0);
            // 6. 애매하면 만들지 않는다 (다른 노드 쌍이 거의 같은 점수)
            if (ok.size() > 1) {
                Pair second = ok.get(1);
                boolean differentPlace = second.a() != best.a() && second.b() != best.b();
                if (differentPlace && second.score() - best.score() < 3) {
                    footbridgeLog.add(log.append("조건6 실패: 후보가 비슷한 쌍이 여러 개 (점수 ")
                            .append(Math.round(best.score())).append(" / ").append(Math.round(second.score())).append(")").toString());
                    continue;
                }
            }
            log.append("조건 1~6 통과 (노드 ").append(best.a()).append("–").append(best.b()).append(", 거리 ")
                    .append(Math.round(best.dist())).append("m, 표 길이 ").append(len).append("m)");
            // 사람 확인
            if (!"있음".equals(status)) {
                footbridgeLog.add(log.append(" → 사람 확인 '").append(status).append("'이라 만들지 않음").toString());
                continue;
            }
            Map<String, String> tags = new HashMap<>(Map.of("highway", "footway", "bridge", "yes",
                    "source", "footbridge_std", "name", name));
            Way w = new Way(nextId, List.of(best.a(), best.b()), tags);
            List<double[]> line = List.of(nodes.get(best.a()).p(), nodes.get(best.b()).p());
            Edge e = new Edge(nextId, best.a(), best.b(), List.of(best.a(), best.b()), line, len, w);
            edges.add(e);
            Integer okFlag = (Integer) (r.get("wheelchair_ok") == null ? null : ((Number) r.get("wheelchair_ok")).intValue());
            String kind = String.valueOf(r.get("kind"));
            Map<Penalty, Double> pen = new EnumMap<>(Penalty.class);
            pen.put(Penalty.FOOTBRIDGE, okFlag == null ? 0.5 : okFlag == 1 ? 0.0 : 1.0);
            // 경사로·승강기가 없으면 계단으로만 오르내린다
            pen.put(Penalty.STAIRS, kind.contains("경사로") || kind.contains("승강기") ? 0.0 : 1.0);
            syntheticPenalty.put(nextId, pen);
            nextId--;
            created++;
            footbridgeLog.add(log.append(" → 육교 길 만듦").toString());
        }
        stats.put("육교 표 (도로망 범위)", rows.size());
        stats.put("공공데이터로 만든 육교 길", created);
    }

    /** 검토 목록 파일 (이름,상태,메모) */
    static Map<String, String> readReview(String csv) {
        Map<String, String> m = new HashMap<>();
        for (String line : csv.split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",", 3);
            if (f.length >= 2) {
                m.put(f[0].strip(), f[1].strip());
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ 3. 감점 계산

    /** 50m 칸으로 나눈 시설 색인 (반경 안 시설을 빨리 찾기) */
    static final class Grid {
        static final double CELL_M = 50;
        final double dLat = CELL_M / 111_195.0;
        final double dLon;
        final Map<Long, List<Spot>> cells = new HashMap<>();
        int size;

        Grid(List<Spot> spots, double lat) {
            dLon = CELL_M / (111_195.0 * Math.cos(Math.toRadians(lat)));
            for (Spot s : spots) {
                cells.computeIfAbsent(key((int) Math.floor(s.lon() / dLon), (int) Math.floor(s.lat() / dLat)),
                        k -> new ArrayList<>()).add(s);
            }
            size = spots.size();
        }

        static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        /** 선에서 radiusM 안의 시설 */
        List<Spot> near(List<double[]> line, double radiusM) {
            double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            for (double[] p : line) {
                minLon = Math.min(minLon, p[0]);
                maxLon = Math.max(maxLon, p[0]);
                minLat = Math.min(minLat, p[1]);
                maxLat = Math.max(maxLat, p[1]);
            }
            int pad = (int) Math.ceil(radiusM / CELL_M);
            List<Spot> out = new ArrayList<>();
            for (int x = (int) Math.floor(minLon / dLon) - pad; x <= (int) Math.floor(maxLon / dLon) + pad; x++) {
                for (int y = (int) Math.floor(minLat / dLat) - pad; y <= (int) Math.floor(maxLat / dLat) + pad; y++) {
                    for (Spot s : cells.getOrDefault(key(x, y), List.of())) {
                        if (Geo.distanceToLine(s.p(), line) <= radiusM) {
                            out.add(s);
                        }
                    }
                }
            }
            return out;
        }

        /** 점에서 가장 가까운 시설까지 거리. maxM 안에 없으면 maxM */
        double nearestM(double[] p, double maxM) {
            double best = maxM;
            for (Spot s : near(List.of(p), maxM)) {
                best = Math.min(best, Geo.distance(p, s.p()));
            }
            return best;
        }
    }

    /** 같은 종류가 10m 안에 겹치면 하나로 (요구사항 5.3). 개수가 큰 쪽을 남긴다 */
    static List<Spot> dedupe(List<Spot> spots, double lat) {
        Grid g = new Grid(List.of(), lat);
        List<Spot> kept = new ArrayList<>();
        for (Spot s : spots) {
            List<Spot> close = g.near(List.of(s.p()), 10);
            if (close.isEmpty()) {
                g.cells.computeIfAbsent(Grid.key((int) Math.floor(s.lon() / g.dLon), (int) Math.floor(s.lat() / g.dLat)),
                        k -> new ArrayList<>()).add(s);
                kept.add(s);
            } else if (s.count() > close.get(0).count()) {
                Spot old = close.get(0);
                kept.remove(old);
                kept.add(s);
                g.cells.values().forEach(l -> l.remove(old));
                g.cells.computeIfAbsent(Grid.key((int) Math.floor(s.lon() / g.dLon), (int) Math.floor(s.lat() / g.dLat)),
                        k -> new ArrayList<>()).add(s);
            }
        }
        return kept;
    }

    /** 팀 DB에서 도로망 범위(+500m) 안의 시설을 읽어 감점을 계산한다 (읽기만 한다) */
    /** 격자 계산용 기준 위도 (도로망 평균) */
    double refLat = 35.14;

    // 추천 규칙 실험용 값 (결정 문서 A3·A5·B1). 기존 감점(penalties)은 그대로 두고 따로 계산한다
    /** 밝기 커버율: 길이 중 등 30m 안에 드는 비율 (0~1) */
    final Map<Long, Double> lightCoverage = new HashMap<>();
    /** 지킴이집 멂: 주변 1km 밀도로 정한 기준(300~800m)으로 나눈 값 */
    final Map<Long, Double> guardRegional = new HashMap<>();
    /** 길의 횡단 수 {신호 있음, 신호 없음, 모름} */
    final Map<Long, int[]> crossingKinds = new HashMap<>();

    void computePenalties(JdbcTemplate teamDb) {
        List<double[]> pts = edges.stream().flatMap(e -> e.line().stream()).toList();
        refLat = pts.stream().mapToDouble(p -> p[1]).average().orElse(35.14);
        BBox box = BBox.around(pts, 500);
        MysqlFacilitySource src = new MysqlFacilitySource(teamDb);
        Map<FacilityTable, List<Spot>> byType = new EnumMap<>(FacilityTable.class);
        for (Facility f : src.within(box, java.util.EnumSet.allOf(FacilityTable.class))) {
            byType.computeIfAbsent(f.type(), k -> new ArrayList<>()).add(new Spot(f.lon(), f.lat(), f.count(), null));
        }
        List<Spot> lights = new ArrayList<>(byType.getOrDefault(FacilityTable.SECURITY_LIGHT, List.of()));
        lights.addAll(byType.getOrDefault(FacilityTable.STREETLIGHT, List.of()));
        int rawLights = lights.size();
        lights = dedupe(lights, refLat);
        List<Spot> cctv = dedupe(byType.getOrDefault(FacilityTable.CCTV, List.of()), refLat);
        List<Spot> help = new ArrayList<>();
        for (FacilityTable t : List.of(FacilityTable.POLICE, FacilityTable.CONVENIENCE_STORE,
                FacilityTable.CHILD_SAFETY_HOUSE, FacilityTable.EMERGENCY_BELL)) {
            help.addAll(byType.getOrDefault(t, List.of()));
        }
        List<Spot> rest = new ArrayList<>();
        for (FacilityTable t : List.of(FacilityTable.HEAT_SHELTER, FacilityTable.COLD_SHELTER, FacilityTable.PUBLIC_TOILET)) {
            rest.addAll(byType.getOrDefault(t, List.of()));
        }
        String inBox = " WHERE MBRContains(ST_GeomFromText(?, 0), geom)";
        // 횡단보도: flag = 보도턱 낮춤, count = 신호등 (1 있음, 0 없음, -1 모름)
        List<Spot> crosswalks = teamDb.query("SELECT lon, lat, curb_lowered, traffic_light FROM crosswalk" + inBox,
                (rs, i) -> new Spot(rs.getDouble(1), rs.getDouble(2),
                        rs.getObject(4) == null ? -1 : rs.getInt(4), (Integer) rs.getObject(3, Integer.class)), box.toWkt());
        List<Spot> footbridges = teamDb.query("SELECT lon, lat, wheelchair_ok FROM footbridge" + inBox,
                (rs, i) -> new Spot(rs.getDouble(1), rs.getDouble(2), 1, (Integer) rs.getObject(3, Integer.class)), box.toWkt());

        stats.put("가로등+보안등 (원본 → 10m 중복 합침)", rawLights);
        stats.put("가로등+보안등 (합친 뒤)", lights.size());
        stats.put("CCTV 지점", cctv.size());
        stats.put("도움 지점 (경찰·편의점·지킴이집·비상벨)", help.size());
        stats.put("지킴이집", byType.getOrDefault(FacilityTable.CHILD_SAFETY_HOUSE, List.of()).size());
        stats.put("쉼 곳 (쉼터·화장실)", rest.size());
        stats.put("횡단보도 표준데이터", crosswalks.size());
        stats.put("육교", footbridges.size());

        Grid gLight = new Grid(lights, refLat);
        Grid gCctv = new Grid(cctv, refLat);
        Grid gHelp = new Grid(help, refLat);
        Grid gGuard = new Grid(byType.getOrDefault(FacilityTable.CHILD_SAFETY_HOUSE, List.of()), refLat);
        Grid gRest = new Grid(rest, refLat);
        Grid gHeat = new Grid(byType.getOrDefault(FacilityTable.HEAT_SHELTER, List.of()), refLat);
        Grid gCold = new Grid(byType.getOrDefault(FacilityTable.COLD_SHELTER, List.of()), refLat);
        Grid gCross = new Grid(crosswalks, refLat);
        Grid gBridge = new Grid(footbridges, refLat);

        int gaps = 0;
        int overRoadBridges = 0;
        int crossingEdges = 0, stdMatched = 0, osmSignalKnown = 0, curbKnown = 0, stdSignal = 0, stdNoSignal = 0;
        for (Edge e : edges) {
            Map<Penalty, Double> p = new EnumMap<>(Penalty.class);
            double len = e.lengthM();
            double[] mid = midpoint(e.line());
            Map<String, String> t = e.way().tags();
            String hw = e.way().highway();

            // 밝기 부족: 1 − min(1, 조명 수 ÷ (길이 ÷ 25m)), 30m 안
            int lamps = gLight.near(e.line(), 30).stream().mapToInt(Spot::count).sum();
            // 데이터 부족: 150m 안에 등이 하나도 없으면 "어둡다"가 아니라 "자료가 없다"로 본다
            // (캠퍼스 안·남구처럼 등 자료가 빈 곳. 요구사항 6.3의 시군구 밀도 규칙을 단순화한 것)
            boolean gap = gLight.near(List.of(mid), 150).isEmpty();
            p.put(Penalty.LIGHT, gap ? 0.5 : 1 - Math.min(1, lamps / (len / 25)));
            // [추천 A3] 밝기 커버율: 5m 간격 점 중 등 30m 안에 드는 비율
            List<Spot> nearLamps = gLight.near(e.line(), 30);
            int samples = 0, lit = 0;
            for (double[] q : samplePoints(e.line(), 5)) {
                samples++;
                for (Spot s : nearLamps) {
                    if (Geo.distance(q, s.p()) <= 30) {
                        lit++;
                        break;
                    }
                }
            }
            lightCoverage.put(e.id(), gap ? 0.5 : samples == 0 ? 0 : (double) lit / samples);
            // [추천 A5] 지킴이집: 주변 1km 안 개수로 그 동네 간격을 추정해 기준(300~800m)으로
            int nGuard = gGuard.near(List.of(mid), 1000).size();
            double guardT = nGuard == 0 ? 800 : Math.min(800, Math.max(300, Math.sqrt(Math.PI * 1e6 / nGuard)));
            guardRegional.put(e.id(), gGuard.nearestM(mid, guardT) / guardT);
            // CCTV 부족: 1 − min(1, 카메라 대수 ÷ (길이 ÷ 100m)), 50m 안
            // (요구사항 6.1은 50m마다 1대. 카메라가 있는 길에서 100m마다 1대면 충분하다고 완화.
            //  50m 안에 카메라가 없는 길은 찍히지 않는 길이라 그대로 1)
            int cams = gCctv.near(e.line(), 50).stream().mapToInt(Spot::count).sum();
            p.put(Penalty.CCTV, 1 - Math.min(1, cams / (len / 100)));
            // 거리 방식: 길 가운데에서 가장 가까운 곳 ÷ 기준 거리
            p.put(Penalty.HELP, gHelp.nearestM(mid, 300) / 300);
            // 지킴이집: 요구사항 6.4는 300m. 조선대 주변 93곳뿐이라 길의 60%가 1이 되어 500m로 완화
            p.put(Penalty.GUARD, gGuard.nearestM(mid, 500) / 500);
            p.put(Penalty.REST, gRest.nearestM(mid, 300) / 300);
            p.put(Penalty.HEAT, gHeat.nearestM(mid, 500) / 500);
            p.put(Penalty.COLD, gCold.nearestM(mid, 500) / 500);
            // 길 성질
            boolean steps = "steps".equals(hw);
            boolean covered = "yes".equals(t.get("covered")) || "yes".equals(t.get("tunnel")) || "yes".equals(t.get("indoor"));
            p.put(Penalty.STAIRS, steps ? 1.0 : 0.0);
            p.put(Penalty.COVER, covered ? 0.0 : 1.0);
            p.put(Penalty.TRAFFIC, hw.startsWith("trunk") || hw.startsWith("primary") ? 1.0
                    : hw.startsWith("secondary") ? 0.5 : 0.0);
            // 횡단: OSM 횡단 구간(footway=crossing)이거나 길 안에 횡단보도 점이 있으면 횡단으로 센다
            int crossings = (int) e.nodeIds().stream().map(nodes::get)
                    .filter(n -> "crossing".equals(n.tags().get("highway"))).count();
            boolean crossingWay = "crossing".equals(t.get("footway"));
            if (crossingWay) {
                crossings = Math.max(crossings, 1);
            }
            p.put(Penalty.CROSSING, Math.min(1, crossings / (len / 100) / 2));
            // 보도턱 (휠체어): 횡단이면 표준데이터(15m 안) → OSM kerb 태그 → 모름 0.5
            double curb = 0;
            if (crossings > 0) {
                crossingEdges++;
                List<Spot> cw = gCross.near(e.line(), 15);
                if (!cw.isEmpty()) {
                    stdMatched++;
                    if (cw.get(0).count() == 1) {
                        stdSignal++;
                    } else if (cw.get(0).count() == 0) {
                        stdNoSignal++;
                    }
                }
                boolean osmSignal = e.nodeIds().stream().map(nodes::get).map(n -> n.tags().get("crossing"))
                        .anyMatch(c -> c != null && (c.contains("traffic_signals") || c.equals("uncontrolled")
                                || c.equals("unmarked") || c.equals("no")));
                if (osmSignal) {
                    osmSignalKnown++;
                }
                // [추천 B1] 횡단마다 신호 여부: OSM 태그 → 표준데이터(15m 안) → 모름
                int[] kinds = new int[3];
                List<Node> crossNodes = e.nodeIds().stream().map(nodes::get)
                        .filter(n -> "crossing".equals(n.tags().get("highway"))).toList();
                int count = Math.max(1, crossNodes.size());
                for (int k = 0; k < count; k++) {
                    String c = k < crossNodes.size() ? crossNodes.get(k).tags().get("crossing") : null;
                    if (c == null && crossingWay) {
                        c = t.get("crossing");
                    }
                    int kind;
                    if (c != null && c.contains("traffic_signals")) {
                        kind = 0;
                    } else if (c != null && (c.equals("uncontrolled") || c.equals("unmarked") || c.equals("no"))) {
                        kind = 1;
                    } else if (!cw.isEmpty() && cw.get(0).count() >= 0) {
                        kind = cw.get(0).count() == 1 ? 0 : 1;
                    } else {
                        kind = 2;
                    }
                    kinds[kind]++;
                }
                crossingKinds.put(e.id(), kinds);
                Integer lowered = cw.isEmpty() ? null : cw.get(0).flag();
                if (lowered == null) {
                    String kerb = e.nodeIds().stream().map(nodes::get).map(n -> n.tags().get("kerb"))
                            .filter(k -> k != null).findFirst().orElse(null);
                    lowered = kerb == null ? null : (kerb.equals("lowered") || kerb.equals("flush")) ? 1
                            : kerb.equals("raised") ? 0 : null;
                }
                curb = lowered == null ? 0.5 : lowered == 1 ? 0 : 1;
                if (lowered != null) {
                    curbKnown++;
                }
            }
            p.put(Penalty.CURB, curb);
            // 육교 (휠체어): 보행용 다리가 차도를 건널 때만 육교로 본다 (하천·캠퍼스 다리는 0).
            // 육교 표(40m 안)의 휠체어 가능 여부, 모르면 0.5
            double bridge = 0;
            if ("yes".equals(t.get("bridge")) && PEDESTRIAN.contains(hw) && e.id() >= 0) {
                boolean overRoad = edges.stream().anyMatch(o -> MOTOR.contains(o.way().highway())
                        && o.line().stream().anyMatch(q -> Geo.distance(q, mid) < 100)
                        && crossesLine(e.line().get(0), e.line().get(e.line().size() - 1), o.line(), mid));
                if (overRoad) {
                    List<Spot> fb = gBridge.near(e.line(), 40);
                    Integer ok = fb.isEmpty() ? null : fb.get(0).flag();
                    bridge = ok == null ? 0.5 : ok == 1 ? 0 : 1;
                    overRoadBridges++;
                }
            }
            p.put(Penalty.FOOTBRIDGE, bridge);
            // 공공데이터로 만든 육교 길은 육교 표의 값을 쓴다
            if (syntheticPenalty.containsKey(e.id())) {
                p.putAll(syntheticPenalty.get(e.id()));
            }

            penalties.put(e.id(), p);
            dataGap.put(e.id(), gap);
            if (gap) {
                gaps++;
            }
        }
        stats.put("데이터 부족 길 (150m 안 등 없음)", gaps);
        stats.put("OSM 보행용 다리 중 차도를 건너는 것 (육교로 봄)", overRoadBridges);
        stats.put("횡단 길", crossingEdges);
        stats.put("  표준데이터(15m 안)가 붙은 횡단 길", stdMatched);
        stats.put("    그중 신호등 있음 / 없음", stdSignal * 10000 + stdNoSignal);
        stats.put("  OSM 태그로 신호 여부를 아는 횡단 길", osmSignalKnown);
        stats.put("  보도턱 정보가 있는 횡단 길", curbKnown);
    }

    /** 선 위에 stepM 간격으로 찍은 점들 (양 끝 포함) */
    static List<double[]> samplePoints(List<double[]> line, double stepM) {
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double seg = Geo.distance(a, b);
            int n = (int) Math.floor(seg / stepM);
            for (int k = 1; k <= n; k++) {
                double f = k * stepM / seg;
                out.add(new double[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f});
            }
            out.add(b);
        }
        return out;
    }

    static double[] midpoint(List<double[]> line) {
        double half = Geo.length(line) / 2, run = 0;
        for (int i = 1; i < line.size(); i++) {
            double seg = Geo.distance(line.get(i - 1), line.get(i));
            if (run + seg >= half) {
                double f = seg == 0 ? 0 : (half - run) / seg;
                double[] a = line.get(i - 1), b = line.get(i);
                return new double[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
            }
            run += seg;
        }
        return line.get(0);
    }

    /** 추천 규칙(A3 커버율, A5 지역 기준)으로 바꾼 감점의 도로망. 계단·횡단은 고정 비용으로 따로 센다 */
    com.saferoute.route.engine.graph.RoadGraph toRecommendedGraph() {
        Map<Long, double[]> ns = new HashMap<>();
        List<com.saferoute.route.engine.graph.RoadEdge> es = new ArrayList<>();
        for (Edge e : edges) {
            ns.put(e.from(), nodes.get(e.from()).p());
            ns.put(e.to(), nodes.get(e.to()).p());
            Map<Penalty, Double> p = new EnumMap<>(penalties.get(e.id()));
            p.put(Penalty.LIGHT, 1 - lightCoverage.getOrDefault(e.id(), 0.0));
            p.put(Penalty.GUARD, guardRegional.getOrDefault(e.id(), 1.0));
            es.add(new com.saferoute.route.engine.graph.RoadEdge(e.id(), e.from(), e.to(), e.lengthM(),
                    e.way().highway(), e.line(),
                    new com.saferoute.route.engine.graph.RoadEdge.EdgePenalty(p, dataGap.get(e.id()))));
        }
        return new com.saferoute.route.engine.graph.RoadGraph(ns, es);
    }

    /** DB에 쓰지 않고 바로 경로 엔진에 넣을 도로망 */
    com.saferoute.route.engine.graph.RoadGraph toRoadGraph() {
        Map<Long, double[]> ns = new HashMap<>();
        List<com.saferoute.route.engine.graph.RoadEdge> es = new ArrayList<>();
        for (Edge e : edges) {
            ns.put(e.from(), nodes.get(e.from()).p());
            ns.put(e.to(), nodes.get(e.to()).p());
            es.add(new com.saferoute.route.engine.graph.RoadEdge(e.id(), e.from(), e.to(), e.lengthM(),
                    e.way().highway(), e.line(),
                    new com.saferoute.route.engine.graph.RoadEdge.EdgePenalty(penalties.get(e.id()), dataGap.get(e.id()))));
        }
        return new com.saferoute.route.engine.graph.RoadGraph(ns, es);
    }

    // ------------------------------------------------------------------ 4. 개인 DB에 쓰기

    /** road_node·road_edge·edge_penalty를 지우고 새로 만든다 (개인 DB에서만 부른다) */
    void write(JdbcTemplate personalDb, String ddl) {
        personalDb.execute("DROP TABLE IF EXISTS edge_penalty, road_edge, road_node");
        for (String stmt : ddl.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!stmt.isBlank()) {
                personalDb.execute(stmt);
            }
        }
        Set<Long> used = new HashSet<>();
        edges.forEach(e -> {
            used.add(e.from());
            used.add(e.to());
        });
        List<Object[]> nodeRows = used.stream().map(nodes::get)
                .map(n -> new Object[] {n.id(), n.lon(), n.lat(), n.lon(), n.lat()}).toList();
        personalDb.batchUpdate("INSERT INTO road_node (id, lon, lat, geom) VALUES (?, ?, ?, ST_SRID(POINT(?, ?), 0))", nodeRows);

        List<Object[]> edgeRows = new ArrayList<>();
        List<Object[]> penRows = new ArrayList<>();
        for (Edge e : edges) {
            String wkt = "LINESTRING(" + String.join(", ", e.line().stream().map(p -> p[0] + " " + p[1]).toList()) + ")";
            Map<Penalty, Double> p = penalties.get(e.id());
            edgeRows.add(new Object[] {e.id(), e.from(), e.to(), e.lengthM(), e.way().highway(),
                    p.get(Penalty.COVER) == 0 ? 1 : 0, p.get(Penalty.STAIRS) == 1 ? 1 : 0, wkt});
            Object[] row = new Object[Penalty.values().length + 2];
            row[0] = e.id();
            for (int i = 0; i < Penalty.values().length; i++) {
                row[i + 1] = p.getOrDefault(Penalty.values()[i], 0.0);
            }
            row[row.length - 1] = dataGap.get(e.id()) ? 1 : 0;
            penRows.add(row);
        }
        personalDb.batchUpdate("INSERT INTO road_edge (id, from_node, to_node, length_m, highway, covered, steps, geom)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 0))", edgeRows);
        String cols = String.join(", ", java.util.Arrays.stream(Penalty.values()).map(x -> x.name().toLowerCase()).toList());
        String marks = String.join(", ", java.util.Collections.nCopies(Penalty.values().length, "?"));
        personalDb.batchUpdate("INSERT INTO edge_penalty (edge_id, " + cols + ", data_gap) VALUES (?, " + marks + ", ?)",
                penRows);
    }
}
