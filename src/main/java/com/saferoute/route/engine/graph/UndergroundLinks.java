package com.saferoute.route.engine.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;

/**
 * 지하 길의 역 출구를 지상 길에 잇는다 (2026-11-09). A파트가 그린 출구 점은 지상 길과 이어져 있지 않아,
 * 출구마다 붙일 지상 길(출구 점 tags.attach_edge) 위 수직의 발에 점을 만들어 그 길을 나누고, 출구 점 → 발 점 짧은 길을 더한다.
 * 출발·도착을 길에 붙이는 {@link RoadGraph#nearest}와 같은 방식이다. 요청 때 범위 안 길에만 한다.
 * 연결 길은 지상이라 지붕·지하 성질이 없다 (비 맞는 구간).
 */
public final class UndergroundLinks {

    /** 출구 점: 점 번호, {경도, 위도}, 붙일 지상 길(road_edge.id) */
    public record Exit(long node, double[] point, long attachEdge) {
    }

    /** 새 발 점 번호. 요청 임시 번호(-1부터)·A파트 지하 점(-100만~-300만대)과 겹치지 않게 -1,000만부터 내려간다 */
    static final long NODE_BASE = -10_000_000L;
    /** 새 길 번호. 지하 길(-10억대)과 겹치지 않게 -20억부터 내려간다 */
    static final long EDGE_BASE = -2_000_000_000L;

    /** 출구에서 길 선에 내린 수직의 발: 발 점, 길 처음에서 선을 따라 잰 거리(m), 발이 있는 선분(seg-1 → seg) */
    private record Foot(Exit exit, double[] point, double alongM, int seg) {
    }

    private UndergroundLinks() {
    }

    /**
     * 출구마다 붙일 길을 나누고 연결 길을 더한 목록. 붙일 길이 목록에 없는 출구는 잇지 않는다 (막다른 점으로 남아 경로에 안 쓰임).
     * 한 길에 출구가 여럿이면(평동역 1·2번 출구가 같은 길) 길 처음에서 가까운 차례로 나눈다.
     * 발이 길 끝 점이나 앞 발에서 {@link RoadGraph#SPLIT_MIN_M} 안이면 나누지 않고 그 점에 붙인다.
     * 출구 점이 이미 모든 모드가 쓰는 지상 길(계단·높낮이 모르는 광장 선이 아닌 길)의 끝이면 잇지 않는다 (2026-11-16)
     */
    public static List<RoadEdge> attach(List<RoadEdge> edges, List<Exit> exits) {
        // A파트가 광장 선을 출구 점에 그어 둔 문화전당역 4번 출구는 곧은 연결 길이 공사 신고 자리를 가로질렀다 (2026-11-16).
        // 계단·광장 선만 닿은 출구는 휠체어가 못 나가므로 그대로 잇는다
        java.util.Set<Long> joined = edges.stream()
                .filter(e -> e.penalty().get(Penalty.UNDERGROUND) < 1 && e.penalty().get(Penalty.STAIRS) < 1
                        && e.penalty().get(Penalty.PLAZA) < 1)
                .flatMap(e -> java.util.stream.Stream.of(e.from(), e.to())).collect(Collectors.toSet());
        Map<Long, List<Exit>> byEdge = exits.stream().filter(x -> !joined.contains(x.node()))
                .collect(Collectors.groupingBy(Exit::attachEdge));
        List<RoadEdge> out = new ArrayList<>(edges.size() + 2 * exits.size());
        long node = NODE_BASE;
        long id = EDGE_BASE;
        for (RoadEdge e : edges) {
            List<Exit> on = byEdge.get(e.id());
            if (on == null) {
                out.add(e);
                continue;
            }
            List<double[]> g = e.geometry();
            double total = Geo.length(g);
            List<Foot> feet = on.stream().map(x -> foot(x, g)).sorted(Comparator.comparingDouble(Foot::alongM)).toList();
            // 길을 처음부터 따라가며 발 자리마다 끊는다. 조각 길이는 원래 길이를 선 길이 비율로 나눈다 (RoadGraph.split과 같음)
            long last = e.from();
            double[] lastP = g.get(0);
            double lastAlong = 0;
            int lastSeg = 1;
            for (Foot f : feet) {
                long at;
                double[] p;
                if (f.alongM() - lastAlong < RoadGraph.SPLIT_MIN_M) {
                    at = last;
                    p = lastP;
                } else if (total - f.alongM() < RoadGraph.SPLIT_MIN_M) {
                    at = e.to();
                    p = g.get(g.size() - 1);
                } else {
                    at = node--;
                    p = f.point();
                    out.add(piece(id--, e, last, at, lastP, lastSeg, f.seg(), p, (f.alongM() - lastAlong) / total));
                    last = at;
                    lastP = p;
                    lastAlong = f.alongM();
                    lastSeg = f.seg();
                }
                out.add(new RoadEdge(id--, f.exit().node(), at, Geo.distance(f.exit().point(), p), "footway",
                        List.of(f.exit().point(), p), EdgePenalty.NONE));
            }
            // 남은 조각. 한 번도 끊지 않았으면 원래 길 그대로
            out.add(last == e.from() ? e
                    : piece(id--, e, last, e.to(), lastP, lastSeg, g.size() - 1, g.get(g.size() - 1),
                            (total - lastAlong) / total));
        }
        return out;
    }

    /**
     * 지하 시설 점(places)에서 지날 수 있는 지하 길(passable)로 이어진 출구 점 (2026-11-16). 출구는 지상 길(연결 길)이 닿은 점이다.
     * 지하상가는 통로를 따라 양쪽 역 대합실을 거쳐 두 역의 출구가 모두 나오고, 역 대합실은 그 역 출구만 나온다.
     * 휠체어는 계단을 못 지나 엘리베이터 출구만 나온다
     */
    public static java.util.Set<Long> exits(RoadGraph graph, java.util.Set<Long> places,
            java.util.function.Predicate<RoadEdge> passable) {
        org.jgrapht.Graph<Long, RoadEdge> g = graph.graph();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.Deque<Long> todo = new java.util.ArrayDeque<>();
        for (long p : places) {
            if (g.containsVertex(p) && seen.add(p)) {
                todo.add(p);
            }
        }
        java.util.Set<Long> out = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            long v = todo.poll();
            for (RoadEdge e : g.edgesOf(v)) {
                if (e.penalty().get(Penalty.UNDERGROUND) < 1) {
                    out.add(v);
                } else if (passable.test(e)) {
                    long w = org.jgrapht.Graphs.getOppositeVertex(g, e, v);
                    // 시설 밖 점(지하상가에서 간 대합실)에서는 출구로만 간다: 역을 찾을 때 지하상가를 지나 다른 역 출구로 가지 않게
                    boolean go = places.contains(v)
                            || g.edgesOf(w).stream().anyMatch(x -> x.penalty().get(Penalty.UNDERGROUND) < 1);
                    if (go && seen.add(w)) {
                        todo.add(w);
                    }
                }
            }
        }
        return out;
    }

    /** 출구에서 길 선 위 가장 가까운 점 */
    private static Foot foot(Exit x, List<double[]> g) {
        Foot best = null;
        double bestM = Double.MAX_VALUE;
        double run = 0;
        for (int i = 1; i < g.size(); i++) {
            double[] q = RoadGraph.lerp(g.get(i - 1), g.get(i), RoadGraph.foot(x.point(), g.get(i - 1), g.get(i)));
            double m = Geo.distance(x.point(), q);
            if (m < bestM) {
                bestM = m;
                best = new Foot(x, q, run + Geo.distance(g.get(i - 1), q), i);
            }
            run += Geo.distance(g.get(i - 1), g.get(i));
        }
        return best;
    }

    /** 길 e의 조각: a(선분 fromSeg 위) → 원래 선의 점 fromSeg..toSeg-1 → b(선분 toSeg 위). 성질·인도 쪽·이름·차로·일방·폭·인도 폭은 그대로 */
    private static RoadEdge piece(long id, RoadEdge e, long from, long to, double[] a, int fromSeg, int toSeg, double[] b,
            double share) {
        List<double[]> line = new ArrayList<>();
        line.add(a);
        line.addAll(e.geometry().subList(fromSeg, toSeg));
        line.add(b);
        return new RoadEdge(id, from, to, e.lengthM() * share, e.highway(), line, e.penalty(), e.sidewalk(), e.name(),
                e.lanes(), e.oneway(), e.widthM(), e.sidewalkWidthM());
    }
}
