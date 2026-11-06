package com.saferoute.route.engine.event;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 행사·돌발상황 영향 반경과 길·경로가 겹치는지 (2026-11-06, 교수님 피드백 "사고·행사로 막힌 길을 돌아가기").
 * 반경은 지점에서 잰 원이다. 길 선이 원에 조금이라도 들어가면 그 길은 영향 안에 있다고 본다.
 */
public final class RoadEvents {

    /** 경로를 따라 영향 구간을 잴 간격(m) */
    static final double STEP_M = 10;

    private RoadEvents() {
    }

    /** 영향 반경에 들어가는 길 id. 경로를 찾을 때 조건부로 피한다 */
    public static Set<Long> zone(RoadGraph graph, List<RoadEvent> events) {
        Set<Long> ids = new HashSet<>();
        if (events.isEmpty()) {
            return ids;
        }
        for (RoadEdge e : graph.graph().edgeSet()) {
            for (RoadEvent ev : events) {
                if (Geo.distanceToLine(ev.point(), e.geometry()) <= ev.radiusM()) {
                    ids.add(e.id());
                    break;
                }
            }
        }
        return ids;
    }

    /**
     * 경로 선이 영향 반경 안을 지나는 구간 {처음 m, 끝 m}. 선의 처음부터 따라 잰다. 지나지 않으면 null.
     * 반경 안에 두 번 들어가면 처음 들어간 곳부터 마지막으로 나온 곳까지 한 구간으로 본다.
     */
    public static double[] stretch(RoadEvent ev, List<double[]> line) {
        double[] p = ev.point();
        if (line.size() < 2 || Geo.distanceToLine(p, line) > ev.radiusM()) {
            return null;
        }
        double from = -1;
        double to = -1;
        double walked = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double seg = Geo.distance(a, b);
            int n = Math.max(1, (int) Math.ceil(seg / STEP_M));
            for (int k = i == 1 ? 0 : 1; k <= n; k++) {
                double t = (double) k / n;
                double[] q = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
                if (Geo.distance(p, q) <= ev.radiusM()) {
                    double at = walked + seg * t;
                    if (from < 0) {
                        from = at;
                    }
                    to = at;
                }
            }
            walked += seg;
        }
        if (from < 0) {
            // 선이 원을 STEP_M보다 짧게 스치면 표본에 안 잡힌다. 가장 가까운 자리 한 점으로 둔다
            double at = Geo.along(p, line).atM();
            return new double[] {at, at};
        }
        return new double[] {from, to};
    }
}
