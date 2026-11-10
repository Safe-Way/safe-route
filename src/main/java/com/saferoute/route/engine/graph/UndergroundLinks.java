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
     * 발이 길 끝 점이나 앞 발에서 {@link RoadGraph#SPLIT_MIN_M} 안이면 나누지 않고 그 점에 붙인다
     */
    public static List<RoadEdge> attach(List<RoadEdge> edges, List<Exit> exits) {
        Map<Long, List<Exit>> byEdge = exits.stream().collect(Collectors.groupingBy(Exit::attachEdge));
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

    /** 길 e의 조각: a(선분 fromSeg 위) → 원래 선의 점 fromSeg..toSeg-1 → b(선분 toSeg 위). 성질·인도 쪽·이름은 그대로 */
    private static RoadEdge piece(long id, RoadEdge e, long from, long to, double[] a, int fromSeg, int toSeg, double[] b,
            double share) {
        List<double[]> line = new ArrayList<>();
        line.add(a);
        line.addAll(e.geometry().subList(fromSeg, toSeg));
        line.add(b);
        return new RoadEdge(id, from, to, e.lengthM() * share, e.highway(), line, e.penalty(), e.sidewalk(), e.name());
    }
}
