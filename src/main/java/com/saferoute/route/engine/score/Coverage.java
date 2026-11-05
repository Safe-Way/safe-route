package com.saferoute.route.engine.score;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 길마다 기준별 커버율(0~1): 길 길이 중 시설 범위 안에 드는 비율. 요청 때 서버가 계산한다 (DB에는 원값만 있다).
 * <ul>
 *   <li>길 따라 (조명·CCTV): 시설을 가장 가까운 길 하나(snap 거리 안)에 붙이고, 붙인 곳에서 길을 따라 반경 안을 덮는다.
 *       직선 반경은 건물 너머 반대편 길까지 세어 약 1/3을 과대 계산했다</li>
 *   <li>반경 (도움 시설·쉼 곳·보호구역): 시설에서 직선 반경 안</li>
 * </ul>
 * 계산은 경도·위도를 그 지역 중심의 평면(m)으로 바꿔서 한다.
 */
public final class Coverage {

    /** 길 따라 퍼질 때 쓰는 길 색인 칸 크기(m) */
    private static final double EDGE_CELL_M = 50;

    private final Map<Long, double[]> byEdge;
    /** 조명의 표본별 결과. 어두운 구간 안내(FR-31)에만 쓰므로 조명만 남긴다 */
    private final Map<Criterion, Map<Long, Sampled>> sampled;

    private Coverage(Map<Long, double[]> byEdge, Map<Criterion, Map<Long, Sampled>> sampled) {
        this.byEdge = byEdge;
        this.sampled = sampled;
    }

    /** 아무 기준도 없을 때 */
    public static final Coverage NONE = new Coverage(Map.of(), Map.of());

    /**
     * 길 하나를 나눈 표본마다 기준 범위 안인지.
     *
     * @param offsetM 표본 자리: 길의 from에서 잰 거리(m, 길 길이 기준)
     * @param inside  그 표본이 범위 안이면 true
     */
    public record Sampled(double[] offsetM, boolean[] inside) {
    }

    /** 표본별 결과 (조명만). 계산하지 않은 기준이나 길이면 null */
    public Sampled sampled(RoadEdge e, Criterion c) {
        Map<Long, Sampled> m = sampled.get(c);
        return m == null ? null : m.get(e.id());
    }

    /** 그 길이 기준 범위 안에 드는 비율 (0~1). 계산하지 않은 기준은 0 */
    public double covered(RoadEdge e, Criterion c) {
        double[] v = byEdge.get(e.id());
        return v == null ? 0 : v[c.ordinal()];
    }

    /** 1 − 커버율: 범위 밖 비율 */
    public double missing(RoadEdge e, Criterion c) {
        return 1 - covered(e, c);
    }

    /**
     * 한 기준의 시설 점과 거리.
     *
     * @param alongRoad true면 길 따라, false면 직선 반경
     */
    public record Layer(List<double[]> points, double radiusM, boolean alongRoad) {
    }

    /**
     * @param snapM   길 따라 기준에서 시설을 길에 붙일 때 허용 거리
     * @param sampleM 길을 나누는 간격
     */
    public static Coverage compute(RoadGraph g, Map<Criterion, Layer> layers, double snapM, double sampleM) {
        Projection pr = Projection.of(g);
        Map<Long, List<Sample>> samples = new HashMap<>();
        for (RoadEdge e : g.graph().edgeSet()) {
            samples.put(e.id(), samples(e, pr, sampleM));
        }
        Map<Long, double[]> out = new HashMap<>();
        for (RoadEdge e : g.graph().edgeSet()) {
            out.put(e.id(), new double[Criterion.values().length]);
        }
        Map<Criterion, Map<Long, Sampled>> sampled = new EnumMap<>(Criterion.class);
        EdgeIndex index = null;
        for (Map.Entry<Criterion, Layer> c : layers.entrySet()) {
            Layer layer = c.getValue();
            int k = c.getKey().ordinal();
            if (layer.alongRoad()) {
                if (index == null) {
                    index = new EdgeIndex(g, pr);
                }
                Map<Long, Sampled> flags = c.getKey() == Criterion.LIGHT ? new HashMap<>() : null;
                alongRoad(g, index, pr, layer, snapM, samples, out, k, flags);
                if (flags != null) {
                    sampled.put(c.getKey(), flags);
                }
            } else {
                PointGrid grid = new PointGrid(layer.points(), pr, layer.radiusM());
                for (RoadEdge e : g.graph().edgeSet()) {
                    List<Sample> ss = samples.get(e.id());
                    long in = ss.stream().filter(s -> grid.anyWithin(s.x, s.y, layer.radiusM())).count();
                    out.get(e.id())[k] = ss.isEmpty() ? 0 : (double) in / ss.size();
                }
            }
        }
        return new Coverage(out, sampled);
    }

    // ------------------------------------------------------------------ 길 따라

    private static void alongRoad(RoadGraph g, EdgeIndex index, Projection pr, Layer layer, double snapM,
            Map<Long, List<Sample>> samples, Map<Long, double[]> out, int k, Map<Long, Sampled> flags) {
        double radius = layer.radiusM();
        Map<Long, List<Double>> onEdge = new HashMap<>();
        Map<Long, Double> dist = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        // 1. 시설마다 가장 가까운 길과 그 길 위 위치(from에서 m)
        for (double[] p : layer.points()) {
            double[] xy = pr.xy(p);
            EdgeIndex.Hit hit = index.nearest(xy[0], xy[1], snapM);
            if (hit == null) {
                continue;
            }
            onEdge.computeIfAbsent(hit.edge().id(), x -> new ArrayList<>()).add(hit.offsetM());
            pq.add(new double[] {hit.offsetM(), hit.edge().from()});
            pq.add(new double[] {hit.edge().lengthM() - hit.offsetM(), hit.edge().to()});
        }
        // 2. 붙인 곳에서 길을 따라 radius까지 퍼진다 (여러 출발점 다익스트라)
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            long n = (long) top[1];
            if (top[0] > radius || top[0] >= dist.getOrDefault(n, Double.MAX_VALUE)) {
                continue;
            }
            dist.put(n, top[0]);
            for (RoadEdge e : g.graph().edgesOf(n)) {
                long m = e.from() == n ? e.to() : e.from();
                double d = top[0] + e.lengthM();
                if (d <= radius && d < dist.getOrDefault(m, Double.MAX_VALUE)) {
                    pq.add(new double[] {d, m});
                }
            }
        }
        // 3. 길마다 표본이 덮였는지
        for (RoadEdge e : g.graph().edgeSet()) {
            List<Sample> ss = samples.get(e.id());
            double df = dist.getOrDefault(e.from(), Double.MAX_VALUE);
            double dt = dist.getOrDefault(e.to(), Double.MAX_VALUE);
            List<Double> same = onEdge.getOrDefault(e.id(), List.of());
            int in = 0;
            double[] offsets = new double[ss.size()];
            boolean[] inside = new boolean[ss.size()];
            for (int si = 0; si < ss.size(); si++) {
                Sample s = ss.get(si);
                double d = Math.min(df + s.offsetM, dt + e.lengthM() - s.offsetM);
                for (double o : same) {
                    d = Math.min(d, Math.abs(s.offsetM - o));
                }
                offsets[si] = s.offsetM;
                inside[si] = d <= radius;
                in += inside[si] ? 1 : 0;
            }
            if (flags != null) {
                flags.put(e.id(), new Sampled(offsets, inside));
            }
            out.get(e.id())[k] = ss.isEmpty() ? 0 : (double) in / ss.size();
        }
    }

    // ------------------------------------------------------------------ 도우미

    /** 길 위 표본점: 평면 좌표와 from에서의 거리(m, 길 길이 기준) */
    private record Sample(double x, double y, double offsetM) {
    }

    private static List<Sample> samples(RoadEdge e, Projection pr, double step) {
        List<double[]> line = e.geometry();
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += pr.dist(line.get(i - 1), line.get(i));
        }
        double scale = total == 0 ? 0 : e.lengthM() / total;
        List<Sample> out = new ArrayList<>();
        double run = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = pr.xy(line.get(i - 1));
            double[] b = pr.xy(line.get(i));
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int n = Math.max(1, (int) (len / step));
            for (int j = 0; j < n; j++) {
                double f = (j + 0.5) / n;
                out.add(new Sample(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, (run + f * len) * scale));
            }
            run += len;
        }
        return out;
    }

    /** 경도·위도 → 지역 중심 위도 기준 평면(m) */
    private record Projection(double kx, double ky) {

        static Projection of(RoadGraph g) {
            double lat = g.nodes().values().stream().mapToDouble(p -> p[1]).average().orElse(35.15);
            return new Projection(111_320 * Math.cos(Math.toRadians(lat)), 110_540);
        }

        double[] xy(double[] lonLat) {
            return new double[] {lonLat[0] * kx, lonLat[1] * ky};
        }

        double dist(double[] a, double[] b) {
            return Math.hypot((a[0] - b[0]) * kx, (a[1] - b[1]) * ky);
        }
    }

    /** 점을 반경 크기 칸에 나눠 둔 색인 */
    private static final class PointGrid {
        private final Map<Long, List<double[]>> cells = new HashMap<>();
        private final double cell;

        PointGrid(List<double[]> points, Projection pr, double radius) {
            this.cell = Math.max(1, radius);
            for (double[] p : points) {
                double[] xy = pr.xy(p);
                cells.computeIfAbsent(key((long) Math.floor(xy[0] / cell), (long) Math.floor(xy[1] / cell)),
                        k -> new ArrayList<>()).add(xy);
            }
        }

        boolean anyWithin(double x, double y, double r) {
            long cx = (long) Math.floor(x / cell);
            long cy = (long) Math.floor(y / cell);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    for (double[] p : cells.getOrDefault(key(cx + dx, cy + dy), List.of())) {
                        if (Math.hypot(p[0] - x, p[1] - y) <= r) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** 길을 칸에 나눠 둔 색인 (시설을 가장 가까운 길에 붙일 때) */
    private static final class EdgeIndex {

        record Hit(RoadEdge edge, double offsetM) {
        }

        private final Map<Long, List<RoadEdge>> cells = new HashMap<>();
        private final Map<Long, List<double[]>> lines = new HashMap<>();
        private final Map<Long, Double> scale = new HashMap<>();

        EdgeIndex(RoadGraph g, Projection pr) {
            for (RoadEdge e : g.graph().edgeSet()) {
                List<double[]> xy = e.geometry().stream().map(pr::xy).toList();
                lines.put(e.id(), xy);
                double total = 0;
                double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
                for (int i = 0; i < xy.size(); i++) {
                    double[] p = xy.get(i);
                    x0 = Math.min(x0, p[0]);
                    x1 = Math.max(x1, p[0]);
                    y0 = Math.min(y0, p[1]);
                    y1 = Math.max(y1, p[1]);
                    if (i > 0) {
                        total += Math.hypot(p[0] - xy.get(i - 1)[0], p[1] - xy.get(i - 1)[1]);
                    }
                }
                scale.put(e.id(), total == 0 ? 0 : e.lengthM() / total);
                for (long x = (long) Math.floor(x0 / EDGE_CELL_M); x <= (long) Math.floor(x1 / EDGE_CELL_M); x++) {
                    for (long y = (long) Math.floor(y0 / EDGE_CELL_M); y <= (long) Math.floor(y1 / EDGE_CELL_M); y++) {
                        cells.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(e);
                    }
                }
            }
        }

        /** maxM 안에서 가장 가까운 길. 없으면 null */
        Hit nearest(double fx, double fy, double maxM) {
            RoadEdge best = null;
            double bestD = maxM;
            double bestS = 0;
            long cx = (long) Math.floor(fx / EDGE_CELL_M);
            long cy = (long) Math.floor(fy / EDGE_CELL_M);
            for (long x = cx - 1; x <= cx + 1; x++) {
                for (long y = cy - 1; y <= cy + 1; y++) {
                    for (RoadEdge e : cells.getOrDefault(key(x, y), List.of())) {
                        List<double[]> line = lines.get(e.id());
                        double run = 0;
                        for (int i = 1; i < line.size(); i++) {
                            double ax = line.get(i - 1)[0], ay = line.get(i - 1)[1];
                            double bx = line.get(i)[0], by = line.get(i)[1];
                            double len = Math.hypot(bx - ax, by - ay);
                            double t = len == 0 ? 0
                                    : Math.max(0, Math.min(1, ((fx - ax) * (bx - ax) + (fy - ay) * (by - ay)) / (len * len)));
                            double d = Math.hypot(ax + (bx - ax) * t - fx, ay + (by - ay) * t - fy);
                            if (d < bestD) {
                                bestD = d;
                                best = e;
                                bestS = (run + t * len) * scale.get(e.id());
                            }
                            run += len;
                        }
                    }
                }
            }
            return best == null ? null : new Hit(best, bestS);
        }
    }

    private static long key(long x, long y) {
        return x * 1_000_003L + y;
    }
}
