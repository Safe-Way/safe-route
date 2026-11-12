package com.saferoute.route.engine.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;

import com.saferoute.route.engine.geo.Geo;

/**
 * 큰길은 건널 곳에서만 건너게 하는, 탐색에만 쓰는 두 번째 도로망 (2026-11-11, specs/011).
 * 큰길이 지도에 가운데 선 하나라, 양쪽 골목이 그 선에 붙은 아무 점에서나 건너편으로 넘어갔다.
 * <ul>
 *   <li>큰길 길이 2개 이상 지나는 노드를 칸으로 나눈다. 칸 = 노드에서 각도순으로 이웃한 큰길 두 갈래 사이 (큰길 2개면 양쪽 두 칸).
 *       옆 길(큰길 아닌 길·횡단보도 길)은 노드에서 {@link #ALONG_M} 간 점이 든 칸에만 잇는다</li>
 *   <li>큰길 길은 L 줄·R 줄 두 벌로 잇는다 (from → to로 걸을 때 왼쪽·오른쪽). 줄마다 노드에서 그 쪽 칸에 붙어,
 *       큰길을 따라 걷다가 반대쪽 골목으로 빠지지 못한다. 모퉁이를 돌아도(북쪽으로 오른쪽 → 동쪽으로 꺾음) 같은 칸이라 안 건넌다</li>
 *   <li>건너기 = 큰길 갈래 하나를 사이에 둔 두 칸을 잇는 길({@link #isConnector}, {@link #CONNECTOR_M}).
 *       큰길 3개 이상 교차로는 모든 갈래에 둔다 (교차로 횡단보도 자료가 많이 빠져 있어, 요구하면 길을 못 찾는다).
 *       큰길 2개 노드는 건널 수 있을 때만: 노드 {@link #NODE_M} 안이면서 그 노드의 큰길 선 {@link #LINE_M} 안인
 *       횡단보도·신호 점이 있거나 횡단보도 길(footway=crossing)이 붙음. 잇는 길 없이 합치면 쪽을 바꾸는 값이 0이라
 *       같은 길이 두 줄 중 아무거나 골라 건널 곳마다 쪽을 오갔다 (시연 경로 200개에 588번, 대부분 교차로)</li>
 *   <li>나누지 않는 노드(= 아무 데서나 건넘): 큰길 길이 1개·{@link #MAX_ARMS}개 넘음, 두 갈래가 {@link #MIN_ANGLE_DEG}° 안이거나
 *       갈래 점이 노드에 붙음(각도를 못 정함), 자기 루프</li>
 * </ul>
 * 커버율·행사·신고·붙이기·응답 조립은 나누기 전 도로망을 쓴다 (커버율은 등·CCTV를 가장 가까운 길 하나에 붙여, 두 벌에서
 * 계산하면 한쪽이 어두워진다). 복사 길은 {@link RoadGraph#original}로 원래 길이 된다.
 * shortcut: 길 중간 횡단보도(큰길 노드 15m 밖, 광주 12곳)는 건널 곳으로 안 본다. 쓰려면 커버율 계산 전에 그 자리에서 길을 잘라야 한다
 */
public final class Crossings {

    /**
     * 복사 번호 간격. 원래 번호(OSM ~1e10, 붙일 때 만든 음수 임시 번호 −1…−10억대)는 |번호| &lt; OFFSET/2라 안 겹친다.
     * <ul>
     *   <li>노드 칸 s(0부터) = 원래 + (s+1)×OFFSET. 칸은 {@link #MAX_ARMS}개까지라 long 안</li>
     *   <li>큰길 L 줄·옆 길 = 원래 + OFFSET, 큰길 R 줄 = 원래 + 2×OFFSET</li>
     *   <li>잇는 길 = 건너는 큰길 갈래의 원래 길 번호 + 3×OFFSET(그 길의 from 끝) 또는 + 4×OFFSET(to 끝)</li>
     * </ul>
     * 노드와 길은 번호 공간이 따로다
     */
    static final long OFFSET = 1_000_000_000_000_000L;

    /** 칸 번호가 long(9.2×OFFSET)을 넘지 않게. 큰길 갈래가 이보다 많은 노드는 안 나눈다 (실제로는 없음) */
    static final int MAX_ARMS = 8;

    /**
     * 잇는 길 길이(m) = 한 번 건너는 손해. 신호 기다림 20~30초를 걸음으로 친 값이라, 이보다 덜 줄면 건넜다 돌아오지 않는다
     * (1m일 때 가운데 선 길이 차이로 23m 사이에 두 번 건넘, 시연 40쌍에 3곳). 탐색 비용에만 쓰고
     * 경로 길이·선·길 목록에는 안 들어가고 건넌 곳으로 남는다 ({@link com.saferoute.route.engine.search.RoadPath})
     */
    static final double CONNECTOR_M = 30;

    /** 이 거리(m) 안 횡단보도·신호 점을 그 노드에서 건널 수 있는 점으로 본다. OSM 신호등 점은 교차로 노드에서 몇 m 떨어져 찍힌다 */
    public static final double NODE_M = 15;

    /** 점이 그 노드의 큰길 선에서 이보다(m) 멀면 옆 소로 쪽 횡단보도라 큰길 건너기에 안 쓴다 (Fable 검토) */
    public static final double LINE_M = 5;

    /** 쪽은 노드에서 길을 따라 이만큼(m) 간 점으로 잰다. 첫 선분이 큰길과 거의 평행하면 다음 꼭짓점 하나로는 쪽이 임의가 된다 */
    static final double ALONG_M = 4;

    /** 갈래 점이 노드에서 이보다(m) 가까우면 각도를 못 정해 안 나눈다. 안전한 쪽으로 실패 */
    static final double MIN_TANGENT_M = 1;

    /** 두 큰길 갈래가 이보다(°) 가까우면(같은 이웃으로 가는 중복 선·머리핀) 사이 칸이 의미 없어 안 나눈다 */
    static final double MIN_ANGLE_DEG = 10;

    /** 교차로 앞뒤 큰길 갈래가 이보다(°) 벌어지면 곧장 지난 것으로 본다 ({@link #crossesSideArm}) */
    static final double STRAIGHT_DEG = 135;

    /** 나눈 노드의 큰길 갈래 (각도순)와 그 각도(라디안, 동쪽 0·반시계). 칸 s = 갈래 s에서 반시계로 갈래 s+1까지. cross: 큰길 2개 노드에서 건널 수 있음 */
    private record Junction(List<RoadEdge> arms, double[] angles, boolean cross) {

        int size() {
            return arms.size();
        }
    }

    private final RoadGraph unsplit;
    private final Map<Long, Junction> split = new HashMap<>();
    private final RoadGraph graph;

    /** points: 건널 수 있는 곳 점 {경도, 위도} (횡단보도·신호등) */
    public Crossings(RoadGraph unsplit, List<double[]> points) {
        this.unsplit = unsplit;
        Graph<Long, RoadEdge> g = unsplit.graph();
        for (Long n : g.vertexSet()) {
            Junction j = junction(n, g.edgesOf(n), points);
            if (j != null) {
                split.put(n, j);
            }
        }
        if (split.isEmpty()) {
            graph = unsplit;
            return;
        }
        Map<Long, double[]> nodes = new HashMap<>(unsplit.nodes());
        split.forEach((n, j) -> {
            double[] p = nodes.remove(n);
            for (int s = 0; s < j.size(); s++) {
                nodes.put(copy(n, s), p);
            }
        });
        List<RoadEdge> edges = new ArrayList<>();
        Map<Long, RoadEdge> originals = new HashMap<>();
        for (RoadEdge e : g.edgeSet()) {
            boolean a = split.containsKey(e.from());
            boolean b = split.containsKey(e.to());
            if (!a && !b) {
                edges.add(e);
            } else if (big(e)) {
                // left: from → to로 걸을 때 왼쪽 줄
                for (boolean left : new boolean[] {true, false}) {
                    long u = a ? copy(e.from(), sector(e.from(), e, left)) : e.from();
                    long v = b ? copy(e.to(), sector(e.to(), e, left)) : e.to();
                    edges.add(copy(e, left ? e.id() + OFFSET : e.id() + 2 * OFFSET, u, v, originals));
                }
            } else {
                long u = a ? copy(e.from(), sector(e.from(), ahead(e, e.from()))) : e.from();
                long v = b ? copy(e.to(), sector(e.to(), ahead(e, e.to()))) : e.to();
                edges.add(copy(e, e.id() + OFFSET, u, v, originals));
            }
        }
        split.forEach((n, j) -> {
            if (j.size() < 3 && !j.cross()) {
                return;
            }
            double[] p = unsplit.point(n);
            int k = j.size();
            for (int i = 0; i < k; i++) {
                RoadEdge arm = j.arms().get(i);
                long id = arm.id() + (arm.from() == n ? 3 : 4) * OFFSET;
                edges.add(new RoadEdge(id, copy(n, (i + k - 1) % k), copy(n, i), CONNECTOR_M, null, List.of(p, p),
                        RoadEdge.EdgePenalty.NONE));
            }
        });
        graph = new RoadGraph(nodes, edges, originals);
    }

    /** 탐색용 도로망. 나눈 노드가 없으면 나누기 전 도로망 그대로 */
    public RoadGraph graph() {
        return graph;
    }

    /**
     * 탐색 도로망의 길 c(원래 길 original)를 forward(from → to)로 걸을 때 큰길의 어느 쪽인지: left·right (걷는 방향 기준).
     * 큰길 복사 길(L 줄 +OFFSET, R 줄 +2×OFFSET)만 알고, 나머지(나누지 않은 길·옆 길 복사)는 null.
     * 번호 차이로 판단한다: 원래 번호가 음수(붙일 때 나눈 임시 길)여도 맞다 (specs/013)
     */
    public static String walkSide(RoadEdge c, RoadEdge original, boolean forward) {
        if (!big(original)) {
            return null;
        }
        long d = c.id() - original.id();
        if (d != OFFSET && d != 2 * OFFSET) {
            return null;
        }
        return (d == OFFSET) == forward ? "left" : "right";
    }

    /** 칸과 칸을 잇는 길(큰길 갈래 하나 건너기)인지. 탐색 비용 말고는 쓰지 않는다 (경로에서 빼고 건넌 곳으로 남긴다) */
    public static boolean isConnector(RoadEdge e) {
        // 복사 길은 2×OFFSET + 원래 번호까지, 잇는 길은 3×OFFSET − 10억대부터라 그 사이로 가른다
        return e.id() > 2 * OFFSET + OFFSET / 2;
    }

    /** 복사 번호의 원래 번호: 칸 노드면 원래 노드, 잇는 길이면 건넌 큰길 갈래 ({@link #OFFSET}: 원래 번호는 |번호| &lt; OFFSET/2) */
    public static long base(long id) {
        return Math.floorMod(id + OFFSET / 2, OFFSET) - OFFSET / 2;
    }

    /**
     * 잇는 길 c가 걷는 큰길(앞 before → 뒤 after, 원래 길)이 아닌 가로 큰길 갈래를 건너며 교차로를 곧장 지나는지
     * (앞뒤 갈래 사이 {@link #STRAIGHT_DEG}° 넘음): 걷는 큰길의 쪽은 그대로다 (민수 ②: 쪽이 안 바뀌면 안 보냄).
     * 꺾으며 건넌 것(T자 남쪽 갈래 → 서쪽 갈래로 동쪽 갈래를 건넘)은 건넌 것이다. 앞뒤가 같은 길(되돌아감)이면 아니다
     */
    public static boolean crossesSideArm(RoadEdge c, RoadEdge before, RoadEdge after) {
        if (before == null || after == null || before.equals(after) || !big(before) || !big(after)) {
            return false;
        }
        long arm = base(c.id());
        if (arm == before.id() || arm == after.id()) {
            return false;
        }
        long n = base(c.from());
        double[] p = c.geometry().get(0);
        double d = Math.abs(angle(p, ahead(before, n)) - angle(p, ahead(after, n)));
        return Math.min(d, 2 * Math.PI - d) > Math.toRadians(STRAIGHT_DEG);
    }

    /** 출발·도착을 붙인 노드가 나눈 노드면 요청 점(gps)이 든 칸 노드, 아니면 그대로 */
    public long place(long node, double[] gps) {
        return split.containsKey(node) ? copy(node, sector(node, gps)) : node;
    }

    /** 나눌 노드면 큰길 갈래, 아니면 null */
    private Junction junction(long n, Set<RoadEdge> at, List<double[]> points) {
        List<RoadEdge> big = new ArrayList<>();
        boolean cross = false;
        for (RoadEdge e : at) {
            if (e.from() == e.to()) {
                return null;
            }
            cross |= e.penalty().get(Penalty.CROSSWALK) >= 1;
            if (big(e)) {
                big.add(e);
            }
        }
        int k = big.size();
        if (k < 2 || k > MAX_ARMS) {
            return null;
        }
        double[] p = unsplit.point(n);
        Map<RoadEdge, Double> angle = new HashMap<>();
        for (RoadEdge e : big) {
            double[] q = ahead(e, n);
            if (Geo.distance(p, q) < MIN_TANGENT_M) {
                return null;
            }
            angle.put(e, angle(p, q));
        }
        big.sort(java.util.Comparator.comparingDouble(angle::get));
        double[] angles = big.stream().mapToDouble(angle::get).toArray();
        for (int i = 0; i < k; i++) {
            double gap = (i + 1 < k ? angles[i + 1] : angles[0] + 2 * Math.PI) - angles[i];
            if (gap < Math.toRadians(MIN_ANGLE_DEG)) {
                return null;
            }
        }
        // 교차로(3개 이상)는 모든 갈래에서 건너니 점을 볼 필요가 없다
        for (int i = 0; k == 2 && !cross && i < points.size(); i++) {
            double[] q = points.get(i);
            // 위도 차이로 먼저 걸러 하버사인 계산을 줄인다 (나눌 후보 노드 × 범위 안 점이라 요청마다 수십만 번)
            if (Math.abs(q[1] - p[1]) * 111_000 <= NODE_M && Geo.distance(p, q) <= NODE_M && (Geo.distanceToLine(q, big.get(0).geometry()) <= LINE_M
                    || Geo.distanceToLine(q, big.get(1).geometry()) <= LINE_M)) {
                cross = true;
            }
        }
        return new Junction(List.copyOf(big), angles, cross);
    }

    /**
     * 큰길 길 e의 left 줄(from → to로 걸을 때 왼쪽)이 노드 n에서 붙는 칸.
     * n에서 나가는 쪽으로 걸으면 왼쪽은 그 갈래의 반시계 쪽 칸(s), n으로 들어오면 시계 쪽 칸(s−1)이다
     */
    private int sector(long n, RoadEdge e, boolean left) {
        Junction j = split.get(n);
        int i = j.arms().indexOf(e);
        return left == (e.from() == n) ? i : (i + j.size() - 1) % j.size();
    }

    /** 점 q가 든 노드 n의 칸 */
    private int sector(long n, double[] q) {
        Junction j = split.get(n);
        double a = angle(unsplit.point(n), q);
        int s = j.size() - 1;
        for (int i = 0; i < j.size() && j.angles()[i] <= a; i++) {
            s = i;
        }
        return s;
    }

    /** 노드 p에서 본 점 q의 각도 (라디안, 동쪽 0·반시계) */
    private static double angle(double[] p, double[] q) {
        return Math.atan2(q[1] - p[1], (q[0] - p[0]) * Math.cos(Math.toRadians(p[1])));
    }

    /** 노드 n에서 길 e를 따라 {@link #ALONG_M} 간 점. 길이 더 짧으면 다른 끝 */
    private static double[] ahead(RoadEdge e, long n) {
        List<double[]> g = new ArrayList<>(e.geometry());
        if (e.from() != n) {
            java.util.Collections.reverse(g);
        }
        double walked = 0;
        for (int i = 1; i < g.size(); i++) {
            double seg = Geo.distance(g.get(i - 1), g.get(i));
            if (walked + seg >= ALONG_M) {
                return RoadGraph.lerp(g.get(i - 1), g.get(i), (ALONG_M - walked) / seg);
            }
            walked += seg;
        }
        return g.get(g.size() - 1);
    }

    private static boolean big(RoadEdge e) {
        return e.highway() != null && RoadGraph.BIG_ROADS.contains(e.highway());
    }

    /** 나눈 노드 n의 칸 s 번호 */
    private static long copy(long n, int s) {
        return n + (s + 1) * OFFSET;
    }

    /** 양 끝만 바꾼 복사 길 (선·길이·성질은 그대로) */
    private static RoadEdge copy(RoadEdge e, long id, long from, long to, Map<Long, RoadEdge> originals) {
        RoadEdge c = new RoadEdge(id, from, to, e.lengthM(), e.highway(), e.geometry(), e.penalty(), e.sidewalk(),
                e.name(), e.lanes(), e.oneway(), e.widthM());
        originals.put(id, e);
        return c;
    }
}
