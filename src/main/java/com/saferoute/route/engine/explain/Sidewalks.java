package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 경로가 차도를 따라가는 구간마다 인도가 어느 쪽에 있는지. 앱이 "오른쪽 인도로 가세요" 안내나
 * 경로 선을 인도 쪽으로 옮겨 그리는 데 쓴다.
 * <p>
 * OSM sidewalk 태그의 left·right는 길을 그린 방향 기준이라, 거꾸로 걸으면 바꿔 준다.
 * 인도·보행로·계단처럼 사람이 걷는 길 자체는 넣지 않는다. 같은 값이 이어지는 길은 한 구간으로 묶는다.
 */
public final class Sidewalks {

    /** 차도가 아닌 걷는 길 (highway 값) */
    static final Set<String> WALKWAYS = Set.of("footway", "path", "pedestrian", "steps", "cycleway",
            "corridor", "bridleway");

    private Sidewalks() {
    }

    /**
     * @param offset path.line()의 첫 점이 응답 geometry에서 몇 번째인지 ({@link RoadPath#startOffset})
     */
    public static List<RouteResult.Sidewalk> along(RoadPath path, int offset) {
        List<RouteResult.Sidewalk> out = new ArrayList<>();
        boolean[] forward = path.forward();
        int at = offset;
        for (int i = 0; i < path.edges().size(); i++) {
            RoadEdge e = path.edges().get(i);
            int to = at + e.geometry().size() - 1;
            // 지하 길(비 오는 날 역 출구 계단·엘리베이터·지하상가)은 차도가 아니다. highway로 나누지 않고 성질로 빼서
            // 지상 도로망(맑은 날 응답)은 그대로 둔다 (2026-11-09)
            if (!WALKWAYS.contains(e.highway()) && !RouteFinder.isUnderground(e)) {
                String side = side(e.sidewalk(), forward[i]);
                RouteResult.Sidewalk last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && last.to() == at && last.side().equals(side)) {
                    out.set(out.size() - 1, new RouteResult.Sidewalk(last.from(), to, side));
                } else {
                    out.add(new RouteResult.Sidewalk(at, to, side));
                }
            }
            at = to;
        }
        return out;
    }

    /** 길을 그린 방향 기준 값을 걷는 방향 기준으로. 모르면 unknown */
    static String side(String sidewalk, boolean forward) {
        if (sidewalk == null) {
            return "unknown";
        }
        if (forward) {
            return sidewalk;
        }
        return switch (sidewalk) {
            case "left" -> "right";
            case "right" -> "left";
            default -> sidewalk;
        };
    }
}
