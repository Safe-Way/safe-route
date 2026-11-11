package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 경로가 차도를 따라가는 구간마다 인도가 어느 쪽에 있는지와 선에서 인도까지 거리. 앱이 "오른쪽 인도로 가세요" 안내나
 * 경로 선을 인도 쪽으로 옮겨 그리는 데 쓴다.
 * <p>
 * OSM sidewalk 태그의 left·right는 길을 그린 방향 기준이라, 거꾸로 걸으면 바꿔 준다. 태그가 없는 일방통행 길은
 * 차가 가는 방향 오른쪽 바깥에 인도가 있다고 본다 (방향별로 두 줄 그린 큰길, 2026-11-11). 태그가 없는 양방향
 * primary·secondary·tertiary는 양쪽(both)으로 본다 (w15).
 * 인도·보행로·계단처럼 사람이 걷는 길 자체는 넣지 않는다. 인도 쪽·길 종류·차로·일방·폭이 모두 같은 길만 한 구간으로 묶는다.
 */
public final class Sidewalks {

    /** 차도가 아닌 걷는 길 (highway 값) */
    static final Set<String> WALKWAYS = Set.of("footway", "path", "pedestrian", "steps", "cycleway",
            "corridor", "bridleway");

    /**
     * 인도 태그가 없으면 양쪽에 인도가 있다고 보는 양방향 큰 길. 도심 큰 길은 대개 양쪽에 인도가 있고, 양방향 길의 인도 태그는
     * primary 5%·secondary 20%·tertiary 22%뿐이라 모름으로 두면 앱이 선을 옮기지 못한다 (2026-11-11, 진행표 w15)
     */
    static final Set<String> BOTH_WHEN_UNTAGGED = Set.of("primary", "secondary", "tertiary");

    /** 차로 하나 폭(m). 앱이 요청한 식의 값 (2026-11-11) */
    static final double LANE_M = 3.25;
    /** 차도 끝에서 인도 가운데까지(m). 보도 폭 3m 가정 */
    static final double CURB_TO_SIDEWALK_M = 1.5;

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
                Boolean oneway = e.oneway() == null ? null : e.oneway() != 0;
                RouteResult.Sidewalk s = new RouteResult.Sidewalk(at, to, side(drawnSide(e), forward[i]), e.highway(),
                        e.lanes(), oneway, e.widthM(), offsetM(e.highway(), e.lanes(), oneway, e.widthM()));
                RouteResult.Sidewalk last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && last.to() == at && last.equals(span(s, last.from(), at))) {
                    out.set(out.size() - 1, span(s, last.from(), to));
                } else {
                    out.add(s);
                }
            }
            at = to;
        }
        return out;
    }

    /**
     * 길을 그린 방향 기준 인도 쪽. 태그가 먼저이고, 없으면 일방통행 길은 차가 가는 방향 오른쪽,
     * 양방향(또는 일방인지 모르는) 큰 길은 양쪽. 그 밖은 모름(null)
     */
    static String drawnSide(RoadEdge e) {
        if (e.sidewalk() != null) {
            return e.sidewalk();
        }
        if (e.oneway() != null && e.oneway() != 0) {
            return e.oneway() > 0 ? "right" : "left";
        }
        return BOTH_WHEN_UNTAGGED.contains(e.highway()) ? "both" : null;
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

    /**
     * 선(차도 가운데)에서 인도 가운데까지(m, 소수 1자리). 폭 → 차로 수 → 기본 차로 수 순으로 반폭을 정한다.
     * 일방인지 모르면(null) 양방 기본값
     */
    static double offsetM(String highway, Integer lanes, Boolean oneway, Double widthM) {
        double half = widthM != null ? widthM / 2
                : (lanes != null ? lanes : defaultLanes(highway, Boolean.TRUE.equals(oneway))) * LANE_M / 2;
        return Math.round((half + CURB_TO_SIDEWALK_M) * 10) / 10.0;
    }

    /** 차로 수 태그가 없을 때. 팀 DB에서 차로 수 태그가 있는 길의 길 종류·일방별 중앙값 (2026-11-11) */
    static int defaultLanes(String highway, boolean oneway) {
        String h = highway == null ? "" : highway;
        if (h.endsWith("_link")) {
            return oneway ? 1 : 2;
        }
        return switch (h) {
            case "trunk" -> oneway ? 2 : 4;
            case "primary", "secondary", "tertiary" -> oneway ? 3 : 4;
            default -> oneway ? 1 : 2;
        };
    }

    private static RouteResult.Sidewalk span(RouteResult.Sidewalk s, int from, int to) {
        return new RouteResult.Sidewalk(from, to, s.side(), s.highway(), s.lanes(), s.oneway(), s.widthM(), s.offsetM());
    }
}
