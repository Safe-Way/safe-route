package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 경로가 차도를 따라가는 구간마다 인도가 어느 쪽에 있는지와 선에서 인도까지 거리. 앱이 "오른쪽 인도로 가세요" 안내나
 * 경로 선을 인도 쪽으로 옮겨 그리는 데 쓴다.
 * <p>
 * OSM sidewalk 태그의 left·right는 길을 그린 방향 기준이라, 거꾸로 걸으면 바꿔 준다. 태그가 없는 일방통행 길은
 * 차가 가는 방향 오른쪽 바깥에 인도가 있다고 보고(방향별로 두 줄 그린 큰길, 2026-11-11), 태그가 없는 양방향 큰 길은
 * 양쪽(both)으로 본다. 둘 다 trunk·primary·secondary·tertiary와 그 연결로만이고 골목급은 모름 (w15).
 * 인도·보행로·계단처럼 사람이 걷는 길 자체는 넣지 않는다. 인도 쪽·길 종류·차로·일방·폭·인도 폭이 모두 같은 길만 한 구간으로 묶는다.
 * <p>
 * 큰 길 both 구간 바로 뒤(없으면 앞)에 따로 그린 인도가 붙어 있으면 그 인도가 있는 쪽(left·right)으로 보낸다. 앱이 내 위치 쪽으로
 * 옮긴 선이 반대쪽 인도로 이어지며 횡단보도 없는 곳에서 차도를 건너는 것처럼 보였다 (서남동 행정복지센터 앞, 2026-11-12 진행표 w20).
 */
public final class Sidewalks {

    /** 차도가 아닌 걷는 길 (highway 값). 도로망을 읽을 때 인도 재질을 쓸 차도인지도 이것으로 본다 (MysqlRoadGraphSource) */
    public static final Set<String> WALKWAYS = Set.of("footway", "path", "pedestrian", "steps", "cycleway",
            "corridor", "bridleway");

    /**
     * 인도 태그가 없을 때 쪽을 정하는 큰 길과 그 연결로: 일방은 차가 가는 방향 오른쪽, 양방(또는 모름)은 양쪽.
     * 도심 큰 길은 대개 인도가 있고, 양방향 길의 인도 태그는 primary 5%·secondary 20%·tertiary 22%뿐이라 모름으로 두면 앱이 선을 옮기지 못한다.
     * 골목급(residential·service 등)은 인도가 없는 길이 많아 정하지 않는다 (2026-11-11, 진행표 w15)
     */
    static final Set<String> SIDE_BY_RULE = RoadGraph.BIG_ROADS;

    /** 차로 하나 폭(m). 앱이 요청한 식의 값 (2026-11-11) */
    static final double LANE_M = 3.25;
    /** 차도 끝에서 인도 가운데까지(m). 보도 폭 3m 가정 */
    static final double CURB_TO_SIDEWALK_M = 1.5;

    /** both 구간에서 이 거리(m) 안 골목·횡단보도 길을 지나 붙은 인도까지 본다. 현장 예는 골목 7m + 골목 횡단보도 */
    static final double LOOK_M = 30;
    /** 차도 선에서 이보다 먼 걷는 길은 그 길 인도가 아니다 */
    static final double WALKWAY_MAX_M = 25;
    /** 걷는 길로 이만큼(m) 들어간 점으로 쪽을 본다. 붙는 끝은 골목 입구나 차도 선 위일 수 있다 */
    static final double INTO_M = 5;
    /** 차도 선(연장선)에서 이보다 가까우면 쪽을 모른다: 큰 길이 끝나고 같은 줄로 이어지는 보행로 */
    static final double ON_LINE_M = 1;

    private Sidewalks() {
    }

    /**
     * 인도 쪽 구간과 그 구간의 경로 길 번호 first..last. before·after = 큰 길 both 구간 앞·뒤에 붙은 따로 그린 인도 쪽
     * (걷는 방향 기준 left·right, 없으면 null). 그 밖 구간은 둘 다 null
     */
    record Stretch(RouteResult.Sidewalk sidewalk, int first, int last, String before, String after) {
    }

    /**
     * @param offset path.line()의 첫 점이 응답 geometry에서 몇 번째인지 ({@link RoadPath#startOffset})
     */
    public static List<RouteResult.Sidewalk> along(RoadPath path, int offset) {
        return stretches(path, offset).stream().map(Stretch::sidewalk).toList();
    }

    /**
     * 골목길 구간 ({@link RouteFinder#isAlley}, 2026-11-12). 이어진 골목길은 한 구간으로 묶는다. from·to는 {@link #along}과 같은 기준
     *
     * @param offset path.line()의 첫 점이 응답 geometry에서 몇 번째인지 ({@link RoadPath#startOffset})
     */
    public static List<RouteResult.Alley> alleys(RoadPath path, int offset) {
        return runs(path, offset, RouteFinder::isAlley).stream()
                .map(r -> new RouteResult.Alley(r.from(), r.to(), r.lengthM())).toList();
    }

    /** 좁은 인도 구간 ({@link RouteFinder#isNarrowSidewalk}, 2026-11-13). 폭은 그 구간에서 가장 좁은 값. from·to는 {@link #along}과 같은 기준 */
    public static List<RouteResult.Narrow> narrows(RoadPath path, int offset) {
        return runs(path, offset, RouteFinder::isNarrowSidewalk).stream()
                .map(r -> new RouteResult.Narrow(r.from(), r.to(), r.lengthM(),
                        r.edges().stream().map(RoadEdge::sidewalkWidthM).filter(java.util.Objects::nonNull)
                                .min(Double::compare).orElse(null)))
                .toList();
    }

    /** 이어진 which 길 한 구간. from·to는 geometry 점 번호 */
    private record Run(int from, int to, int lengthM, List<RoadEdge> edges) {
    }

    private static List<Run> runs(RoadPath path, int offset, java.util.function.Predicate<RoadEdge> which) {
        List<Run> out = new ArrayList<>();
        int at = offset;
        int from = -1;
        List<RoadEdge> run = new ArrayList<>();
        for (RoadEdge e : path.edges()) {
            if (which.test(e)) {
                from = from < 0 ? at : from;
                run.add(e);
            } else if (from >= 0) {
                out.add(run(from, at, run));
                from = -1;
                run = new ArrayList<>();
            }
            at += e.geometry().size() - 1;
        }
        if (from >= 0) {
            out.add(run(from, at, run));
        }
        return out;
    }

    private static Run run(int from, int to, List<RoadEdge> edges) {
        return new Run(from, to, (int) Math.round(edges.stream().mapToDouble(RoadEdge::lengthM).sum()), edges);
    }

    static List<Stretch> stretches(RoadPath path, int offset) {
        List<Stretch> out = new ArrayList<>();
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
                        e.lanes(), oneway, e.widthM(), offsetM(e.highway(), e.lanes(), oneway, e.widthM()), e.sidewalkWidthM());
                Stretch last = out.isEmpty() ? null : out.get(out.size() - 1);
                RouteResult.Sidewalk l = last == null ? null : last.sidewalk();
                if (l != null && l.to() == at && l.equals(span(s, l.from(), at))) {
                    out.set(out.size() - 1, new Stretch(span(s, l.from(), to), last.first(), i, null, null));
                } else {
                    out.add(new Stretch(s, i, i, null, null));
                }
            }
            at = to;
        }
        for (int k = 0; k < out.size(); k++) {
            Stretch t = out.get(k);
            RouteResult.Sidewalk s = t.sidewalk();
            if (!"both".equals(s.side()) || !big(path.edges().get(t.first()))) {
                continue;
            }
            List<double[]> line = path.line().subList(s.from() - offset, s.to() - offset + 1);
            String before = walkwaySide(path, forward, t.first() - 1, -1, line);
            String after = walkwaySide(path, forward, t.last() + 1, 1, line);
            String side = after != null ? after : before != null ? before : "both";
            out.set(k, new Stretch(new RouteResult.Sidewalk(s.from(), s.to(), side, s.highway(), s.lanes(), s.oneway(),
                    s.widthM(), s.offsetM(), s.sidewalkWidthM()), t.first(), t.last(), before, after));
        }
        return out;
    }

    /**
     * 경로 길 j부터 dir(+1 뒤, −1 앞) 쪽으로 처음 만나는 따로 그린 인도가 큰 길 구간 선 line(걷는 방향)의 어느 쪽인지.
     * 사이 골목·횡단보도 길은 {@link #LOOK_M}까지 건너뛰고, 큰 길을 먼저 만나거나 인도가 선에서 멀면 null
     */
    private static String walkwaySide(RoadPath path, boolean[] forward, int j, int dir, List<double[]> line) {
        double walked = 0;
        for (; j >= 0 && j < path.edges().size() && walked <= LOOK_M; j += dir) {
            RoadEdge e = path.edges().get(j);
            if (big(e)) {
                return null;
            }
            if (sidewalkLike(e)) {
                // 큰 길 쪽 끝에서 시작하는 선: 뒤 인도는 걷는 방향 그대로, 앞 인도는 거꾸로
                List<double[]> g = forward[j] == dir > 0 ? e.geometry() : e.geometry().reversed();
                double[] p = Turns.walk(g, 0, INTO_M, 1);
                double[] a = line.get(dir > 0 ? line.size() - 2 : 0);
                double[] b = line.get(dir > 0 ? line.size() - 1 : 1);
                // 큰 길 끝 조각의 연장선에서 p까지 수직 거리. 오른쪽이 +
                double right = Geo.distance(a, p) * Math.sin(Math.toRadians(Turns.heading(a, p) - Turns.heading(a, b)));
                if (Math.abs(right) < ON_LINE_M || Geo.distanceToLine(p, line) > WALKWAY_MAX_M) {
                    return null;
                }
                return right > 0 ? "right" : "left";
            }
            walked += e.lengthM();
        }
        return null;
    }

    /** 길가 인도로 볼 걷는 길. 계단·건물 안 통로·지하 길·횡단보도 길은 아니다 */
    private static boolean sidewalkLike(RoadEdge e) {
        return e.highway() != null && WALKWAYS.contains(e.highway()) && !"steps".equals(e.highway())
                && !"corridor".equals(e.highway()) && e.penalty().get(Penalty.CROSSWALK) < 1
                && !RouteFinder.isUnderground(e);
    }

    static boolean big(RoadEdge e) {
        return e.highway() != null && RoadGraph.BIG_ROADS.contains(e.highway());
    }

    /**
     * 길을 그린 방향 기준 인도 쪽. 태그가 먼저이고, 없으면 큰 길({@link #SIDE_BY_RULE})만 일방은 차가 가는 방향 오른쪽,
     * 양방(또는 일방인지 모르는)은 양쪽. 골목급은 모름(null)
     */
    static String drawnSide(RoadEdge e) {
        if (e.sidewalk() != null) {
            return e.sidewalk();
        }
        if (e.highway() == null || !SIDE_BY_RULE.contains(e.highway())) {
            return null;
        }
        if (e.oneway() != null && e.oneway() != 0) {
            return e.oneway() > 0 ? "right" : "left";
        }
        return "both";
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
        return new RouteResult.Sidewalk(from, to, s.side(), s.highway(), s.lanes(), s.oneway(), s.widthM(), s.offsetM(),
                s.sidewalkWidthM());
    }
}
