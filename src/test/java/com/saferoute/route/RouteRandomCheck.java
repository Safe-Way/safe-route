package com.saferoute.route;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsWeightedGraph;
import org.jgrapht.graph.MaskSubgraph;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.saferoute.route.TestSupport.Out;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.MysqlPoiSource;
import com.saferoute.route.data.MysqlRoadGraphSource;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.Nearby;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;

/**
 * 운영 테스트: 팀 DB(광주 전체 도로망)에서 매번 다른 임의 경로로 "어떤 경로든 지켜야 하는 규칙"을 검사하고 속도를 잰다.
 * 「경로 엔진 테스트 문서」 테스트 방법 참고. CI에서는 돌지 않는다 (팀 DB 필요).
 * <pre>
 * 실행:      ./mvnw test -Dtest=RouteRandomCheck -Dlive
 * 쌍 수:     -Dpairs=150 (기본)
 * 다시 돌리기: 출력된 시드로 -Dseed=값
 * 모드 고르기: -Dmodes=wheelchair,senior
 * 설정 바꿔 보기: -Doverrides="route.profiles.wheelchair.max-detour=1.2;route.profiles.wheelchair.night.help-m=120"
 * </pre>
 */
class RouteRandomCheck {

    static final String[] MODES = {"adult", "child", "senior", "wheelchair"};
    static final int[] HOURS = {12, 21};
    static final double MIN_M = 300;
    static final double MAX_M = 5000;
    static final long LIMIT_MS = 1000;

    /** 자동차전용도로(OSM motorroad·expressway) 길 번호. 사람이 걸을 수 없는 길인데 도로망에 들어 있다 */
    static Set<Long> carOnly = Set.of();
    /** 자동차전용도로 길 번호 → 도로 이름 */
    static Map<Long, String> carOnlyName = Map.of();
    /** 자동차전용도로를 지난 경로 예시 */
    static final List<String> carOnlyCases = new ArrayList<>();

    /** 경로 없음 사례 (분석용) */
    static final List<String> noRoutes = new ArrayList<>();

    record Run(String mode, boolean night, long ms, String error, boolean differs, double ratio, boolean capped,
            Extra extra) {

        Run(String mode, boolean night, long ms, String error, boolean differs, double ratio, boolean capped) {
            this(mode, night, ms, error, differs, ratio, capped, null);
        }
    }

    /**
     * 정책 효과 분석용 값.
     *
     * @param coverage    기준별 {최단 커버율, 안전 커버율} (길이 가중, 0~1)
     * @param generalM    모든 길로 잰 최단 길이 (제외 규칙 없이)
     * @param stairsOnGeneral 제외 없는 최단에 계단이 있는지
     * @param stairsOnSafe    안전 경로에 계단이 있는지
     */
    record Extra(Map<Criterion, double[]> coverage, double shortestM, double generalM, boolean stairsOnGeneral,
            boolean stairsOnSafe, boolean carOnlyOnShortest, boolean carOnlyOnSafe) {
    }

    @Test
    void randomRoutes() throws Exception {
        assumeTrue(System.getProperty("live") != null, "-Dlive를 줄 때만 실행");
        long seed = Long.getLong("seed", System.currentTimeMillis() % 1_000_000);
        int pairs = Integer.getInteger("pairs", 150);

        Map<String, String> env = TestSupport.readEnv();
        JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(env.get("DB_URL"), env.get("DB_USERNAME"),
                env.get("DB_PASSWORD"), true));
        String overrides = System.getProperty("overrides", "");
        RouteProperties props = TestRoutes.props(overrides.isBlank() ? new String[0] : overrides.split(";"));
        String[] modes = System.getProperty("modes", String.join(",", MODES)).split(",");
        MysqlRoadGraphSource roads = new MysqlRoadGraphSource(db);
        MysqlPoiSource pois = new MysqlPoiSource(db);
        RouteScorer scorer = new RouteScorer(props);
        RouteResultAssembler assembler = new RouteResultAssembler(new FacilityCounter(props), props);
        pois.holidays();
        pois.dataDate();
        carOnlyName = new java.util.HashMap<>();
        db.query("SELECT id, IFNULL(JSON_UNQUOTE(JSON_EXTRACT(tags, '$.\"name:ko\"')), IFNULL(name, '이름 없음')) FROM road_edge"
                + " WHERE JSON_EXTRACT(tags, '$.motorroad') = 'yes' OR JSON_EXTRACT(tags, '$.expressway') = 'yes'",
                rs -> {
                    carOnlyName.put(rs.getLong(1), rs.getString(2));
                });
        carOnly = carOnlyName.keySet();

        // 시드로 노드를 섞어 뽑고, 같은 시드로 직선 300m~5km 쌍을 만든다
        List<double[]> nodes = db.query("SELECT lon, lat FROM road_node ORDER BY RAND(?) LIMIT 4000",
                (rs, i) -> new double[] {rs.getDouble(1), rs.getDouble(2)}, seed);
        Random rnd = new Random(seed);
        List<double[][]> trips = new ArrayList<>();
        while (trips.size() < pairs) {
            double[] a = nodes.get(rnd.nextInt(nodes.size()));
            double[] b = nodes.get(rnd.nextInt(nodes.size()));
            double d = Geo.distance(a, b);
            if (d >= MIN_M && d <= MAX_M) {
                trips.add(new double[][] {a, b});
            }
        }

        List<Run> runs = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < trips.size(); i++) {
            double[] from = trips.get(i)[0];
            double[] to = trips.get(i)[1];
            for (int hour : HOURS) {
                ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
                RouteService service = new RouteService(roads, pois, new NightJudge(), scorer, assembler, props,
                        java.time.Clock.fixed(now.toInstant(), now.getZone()));
                for (String mode : modes) {
                    String where = "쌍 %d %s %d시 (%.6f,%.6f)→(%.6f,%.6f)".formatted(i, mode, hour, from[1], from[0],
                            to[1], to[0]);
                    try {
                        runs.add(check(service, props, roads, pois, scorer, assembler, from, to, mode, now, where,
                                violations));
                    } catch (RuntimeException e) {
                        violations.add(where + ": 정해지지 않은 예외 " + e);
                        runs.add(new Run(mode, hour == 21, 0, "UNEXPECTED", false, 1, false));
                    }
                }
            }
        }
        report(seed, pairs, runs, violations, overrides);
    }

    /** RouteService.compute와 같은 순서로 계산하고, 중간 결과(경로의 길)로 규칙을 검사한다 */
    static Run check(RouteService service, RouteProperties props, MysqlRoadGraphSource roads, MysqlPoiSource pois,
            RouteScorer scorer, RouteResultAssembler assembler, double[] from, double[] to, String mode,
            ZonedDateTime now, String where, List<String> violations) {
        long started = System.nanoTime();
        RouteProperties.Profile profile = props.profile(mode);
        Conditions cond = service.conditions(from, now, NightOption.AUTO, profile.nightLeadMin());
        RouteProperties.Phase phase = profile.phase(cond.night());
        RoadGraph graph = roads.load(BBox.around(List.of(from, to), props.searchMarginM()));
        RoadGraph.Snap a = graph.nearest(from[0], from[1]);
        RoadGraph.Snap b = graph.nearest(to[0], to[1]);
        if (a == null || b == null || a.distanceM() > props.snapMaxM() || b.distanceM() > props.snapMaxM()) {
            // 길에서 너무 먼 점. 서비스는 NO_ROUTE로 답한다. 점 자체가 노드라면 큰 덩어리와 이어지지 않은 조각에 있는 것
            noRoutes.add(where + ": 큰 연결 덩어리까지 출발 %.0fm·도착 %.0fm (한도 %.0fm), 읽은 길 %d개".formatted(
                    a == null ? -1 : a.distanceM(), b == null ? -1 : b.distanceM(), props.snapMaxM(),
                    graph.graph().edgeSet().size()));
            return viaService(service, from, to, mode, cond.night(), "NO_ROUTE(붙이기)");
        }
        RouteProperties.CountRadius c = props.countRadiusM();
        double margin = Math.max(Math.max(Math.max(phase.helpM(), phase.restM()), Math.max(phase.schoolM(),
                props.coverage().cctvM())), Math.max(Math.max(c.light(), c.cctv()), Math.max(c.bell(), c.help())));
        Nearby nearby = new Nearby(pois.within(BBox.around(new ArrayList<>(graph.nodes().values()), margin),
                RouteService.POI_KINDS), pois.holidays(), now.toLocalDateTime(), pois.dataDate());
        ToDoubleFunction<RoadEdge> extra = scorer.extraCost(graph, phase, cond.night(), nearby);
        RouteFinder.Found f;
        try {
            f = RouteFinder.find(graph, a.node(), b.node(), extra, profile.maxDetour(), profile::passable,
                    profile.avoidStairs());
        } catch (RouteException e) {
            // 지날 수 있는 길로 두 점이 이어지지 않음. 읽은 범위(사각형 + 500m) 안에서만 찾으므로 범위 탓인지도 본다
            boolean anyRoad = dijkstra(graph, x -> true, a.node(), b.node()) < Double.MAX_VALUE;
            String why = "";
            if (anyRoad) {
                var all = new DijkstraShortestPath<>(new AsWeightedGraph<>(graph.graph(), RoadEdge::lengthM, false, false))
                        .getPath(a.node(), b.node());
                Map<String, Long> blocked = new TreeMap<>();
                for (RoadEdge edge : all.getEdgeList()) {
                    if (!profile.passable(edge)) {
                        edge.penalty().values().forEach((k, v) -> {
                            if (v >= 1) {
                                blocked.merge(k.name() + " 길" + edge.id(), 1L, Long::sum);
                            }
                        });
                    }
                }
                why = " 최단 길에서 막힌 곳 " + blocked.keySet();
            }
            noRoutes.add(where + (anyRoad ? ": 모든 길로는 이어짐 (제외한 길 때문에 끊김)" + why : ": 모든 길로도 안 이어짐"));
            return viaService(service, from, to, mode, cond.night(), "NO_ROUTE(끊김)");
        }
        RouteResult r = assembler.assemble(cond, profile, phase, f, from, to, a.distanceM() + b.distanceM(), nearby);
        long ms = (System.nanoTime() - started) / 1_000_000;

        String tag = where + ": ";
        // 1. 최단 경로는 지날 수 있는 길 중 실제 최단
        Predicate<RoadEdge> passable = profile::passable;
        double best = dijkstra(graph, passable, a.node(), b.node());
        if (Math.abs(best - f.shortest().lengthM()) > 0.5) {
            violations.add(tag + "최단 %.1fm인데 실제 최단 %.1fm".formatted(f.shortest().lengthM(), best));
        }
        // 2. 우회 한도
        if (profile.maxDetour() > 0 && f.safe().lengthM() > f.shortest().lengthM() * profile.maxDetour() + 1e-6) {
            violations.add(tag + "안전 %.1fm > 최단 %.1fm × %.1f".formatted(f.safe().lengthM(), f.shortest().lengthM(),
                    profile.maxDetour()));
        }
        // 3. 지날 수 없는 길 (휠체어: 경사로 없는 계단·확인된 턱·못 지나는 육교)
        for (RoadPath p : List.of(f.shortest(), f.safe())) {
            if (p.edges().stream().anyMatch(passable.negate())) {
                violations.add(tag + "지날 수 없는 길이 경로에 있음");
            }
        }
        // 4. 노인: 한도 안에 계단 없는 길이 있으면 계단을 안 지남
        if (profile.avoidStairs() && f.safe().edges().stream().anyMatch(RouteFinder::isStairs)) {
            double free = dijkstra(graph, passable.and(e -> !RouteFinder.isStairs(e)), a.node(), b.node());
            if (free <= f.shortest().lengthM() * profile.maxDetour()) {
                violations.add(tag + "계단 없는 길 %.1fm가 한도 안인데 계단을 지남".formatted(free));
            }
        }
        // 5. 설명 문장과 경로 수
        String text = r.explanation().text();
        if (r.routes().size() == 1) {
            if (!Set.of("가장 짧은 길이 가장 안전한 길입니다", "더 안전한 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다",
                    "지금은 밤이 아니라 가장 짧은 길을 안내합니다", "휠체어로 지날 수 있는 길 중 가장 짧은 길입니다").contains(text)) {
                violations.add(tag + "경로 1개인데 설명: " + text);
            }
        } else {
            if (!text.contains("더 돌지만") && !text.startsWith("같은 거리로")) {
                violations.add(tag + "경로 2개인데 설명: " + text);
            }
            int shortM = r.routes().get(0).distanceM();
            int safeM = r.routes().get(1).distanceM();
            if (safeM < shortM || r.explanation().extraM() != safeM - shortM) {
                violations.add(tag + "거리 최단 %d·안전 %d·추가 %d".formatted(shortM, safeM, r.explanation().extraM()));
            }
        }
        // 6. 속도
        if (ms > LIMIT_MS) {
            violations.add(tag + "응답 %dms".formatted(ms));
        }
        boolean differs = r.routes().size() == 2;

        // 정책 효과: 기준별 커버율 (최단 vs 안전), 제외 없는 최단과의 차이, 계단
        Map<Criterion, double[]> coverage = new java.util.EnumMap<>(Criterion.class);
        if (phase.hasWeights()) {
            Coverage cov = scorer.coverage(graph, phase, cond.night(), nearby);
            for (Criterion k : phase.weights().keySet()) {
                coverage.put(k, new double[] {covered(f.shortest(), cov, k), covered(f.safe(), cov, k)});
            }
        }
        var general = new DijkstraShortestPath<>(new AsWeightedGraph<>(graph.graph(), RoadEdge::lengthM, false, false))
                .getPath(a.node(), b.node());
        List<RoadEdge> onCar = f.shortest().edges().stream().filter(e -> carOnly.contains(e.id())).toList();
        if (!onCar.isEmpty() && mode.equals("adult") && !cond.night()) {
            double[] p0 = onCar.get(0).geometry().get(0);
            carOnlyCases.add(where + ": 최단 %.0fm 중 %.0fm를 %s (길 %d개, 첫 구간 길%d 위치 %.5f,%.5f)".formatted(
                    f.shortest().lengthM(), onCar.stream().mapToDouble(RoadEdge::lengthM).sum(),
                    onCar.stream().map(e -> carOnlyName.get(e.id())).distinct().toList(), onCar.size(),
                    onCar.get(0).id(), p0[1], p0[0]));
        }
        Extra effect = new Extra(coverage, f.shortest().lengthM(), general == null ? Double.NaN : general.getWeight(),
                general != null && general.getEdgeList().stream().anyMatch(RouteFinder::isStairs),
                f.safe().edges().stream().anyMatch(RouteFinder::isStairs),
                f.shortest().edges().stream().anyMatch(e -> carOnly.contains(e.id())),
                f.safe().edges().stream().anyMatch(e -> carOnly.contains(e.id())));
        return new Run(mode, cond.night(), ms, null, differs, f.safe().lengthM() / f.shortest().lengthM(),
                f.detourCapped(), effect);
    }

    /**
     * 처음 범위에서 못 찾은 경우: 실제 서비스가 범위를 넓혀 다시 찾거나 휠체어 제외를 풀어 찾는지 본다.
     * 찾으면 "찾음(넓힘)" 또는 "찾음(제외 풂)", 못 찾으면 처음 원인 그대로.
     */
    static Run viaService(RouteService service, double[] from, double[] to, String mode, boolean night, String first) {
        long started = System.nanoTime();
        try {
            RouteResult r = service.route(new com.saferoute.route.api.RouteRequest(
                    new com.saferoute.route.api.RouteRequest.Place(from[1], from[0], null),
                    new com.saferoute.route.api.RouteRequest.Place(to[1], to[0], null), "auto", mode));
            long ms = (System.nanoTime() - started) / 1_000_000;
            String how = r.explanation().text().contains("(주의:") ? "찾음(제외 풂)" : "찾음(넓힘)";
            retried.add("%s %s (%.6f,%.6f)→(%.6f,%.6f) %s → %s %dms, 경로 %dm: %s".formatted(mode, night ? "밤" : "낮",
                    from[1], from[0], to[1], to[0], first, how, ms,
                    r.routes().get(r.routes().size() - 1).distanceM(), r.explanation().text()));
            return new Run(mode, night, ms, how, r.routes().size() == 2, 1, false);
        } catch (RouteException e) {
            return new Run(mode, night, 0, first, false, 1, false);
        }
    }

    /** 다시 찾은 사례 */
    static final List<String> retried = new ArrayList<>();

    /** 경로 길이 중 기준 범위 안에 드는 비율 */
    static double covered(RoadPath p, Coverage cov, Criterion c) {
        double len = 0;
        double in = 0;
        for (RoadEdge e : p.edges()) {
            len += e.lengthM();
            in += e.lengthM() * cov.covered(e, c);
        }
        return len == 0 ? 0 : in / len;
    }

    static double dijkstra(RoadGraph graph, Predicate<RoadEdge> passable, long a, long b) {
        var g = new AsWeightedGraph<>(new MaskSubgraph<>(graph.graph(), v -> false, e -> !passable.test(e)),
                RoadEdge::lengthM, false, false);
        GraphPath<Long, RoadEdge> p = new DijkstraShortestPath<>(g).getPath(a, b);
        return p == null ? Double.MAX_VALUE : p.getWeight();
    }

    static void report(long seed, int pairs, List<Run> runs, List<String> violations, String overrides) {
        Out o = new Out("운영 테스트: 임의 경로 %d쌍 × 낮·밤 (시드 %d, 다시 돌리기 -Dseed=%d)%s".formatted(pairs, seed, seed,
                overrides.isBlank() ? "" : " 설정 바꿈: " + overrides));
        o.line("%-11s %-2s %5s %7s %7s %7s %7s %6s %6s %6s %s", "모드", "", "요청", "다른길%", "평균우회", "한도걸림",
                "경로없음", "중앙ms", "95%ms", "최대ms", "오류 내역");
        Map<String, List<Run>> groups = new LinkedHashMap<>();
        for (String mode : MODES) {
            for (boolean night : new boolean[] {false, true}) {
                groups.put(mode + (night ? " 밤" : " 낮"), new ArrayList<>());
            }
        }
        for (Run r : runs) {
            groups.get(r.mode() + (r.night() ? " 밤" : " 낮")).add(r);
        }
        List<Long> allMs = new ArrayList<>();
        for (Map.Entry<String, List<Run>> g : groups.entrySet()) {
            List<Run> list = g.getValue();
            if (list.isEmpty()) {
                continue;
            }
            List<Run> ok = list.stream().filter(r -> r.error() == null).toList();
            List<Long> ms = new ArrayList<>(ok.stream().map(Run::ms).toList());
            Collections.sort(ms);
            allMs.addAll(ms);
            List<Run> diff = ok.stream().filter(Run::differs).toList();
            Map<String, Long> errors = new TreeMap<>();
            list.stream().filter(r -> r.error() != null).forEach(r -> errors.merge(r.error(), 1L, Long::sum));
            long noRoute = list.stream().filter(r -> r.error() != null && r.error().startsWith("NO_ROUTE")).count();
            // 다시 찾아 성공한 요청도 응답 시간에 넣는다
            list.stream().filter(r -> r.error() != null && r.error().startsWith("찾음")).forEach(r -> ms.add(r.ms()));
            Collections.sort(ms);
            String[] mn = g.getKey().split(" ");
            o.line("%-11s %-2s %5d %6.0f%% %6.2f배 %6.0f%% %6.1f%% %6d %6d %6d %s", mn[0], mn[1], list.size(),
                    ok.isEmpty() ? 0 : 100.0 * diff.size() / ok.size(),
                    diff.stream().mapToDouble(Run::ratio).average().orElse(1),
                    ok.isEmpty() ? 0 : 100.0 * ok.stream().filter(Run::capped).count() / ok.size(),
                    100.0 * noRoute / list.size(), pct(ms, 50), pct(ms, 95), ms.isEmpty() ? 0 : ms.get(ms.size() - 1),
                    errors.isEmpty() ? "" : errors);
        }
        Collections.sort(allMs);
        o.line("");
        o.line("전체 응답: 중앙 %dms, 95%% %dms, 최대 %dms, 1초 넘음 %d건", pct(allMs, 50), pct(allMs, 95),
                allMs.isEmpty() ? 0 : allMs.get(allMs.size() - 1), allMs.stream().filter(m -> m > LIMIT_MS).count());
        o.line("규칙 위반 %d건%s", violations.size(), violations.isEmpty() ? "" : " (최대 40건 표시)");
        violations.stream().limit(40).forEach(v -> o.line("  " + v));
        policyReport(o, groups);
        o.line("처음 범위에서 못 찾아 서비스가 다시 찾은 경우 %d건", retried.size());
        retried.forEach(x -> o.line("  " + x));
        o.line("경로 없음 %d건%s", noRoutes.size(), noRoutes.isEmpty() ? "" : " (최대 30건 표시)");
        noRoutes.stream().limit(30).forEach(v -> o.line("  " + v));
        o.print();
    }

    /** 정책이 의도대로 효과를 내는지 */
    static void policyReport(Out o, Map<String, List<Run>> groups) {
        o.line("");
        o.line("■ 기준별 커버율 (경로 길이 중 범위 안 비율, 평균): 최단 → 안전 (안전이 더 높은 경우 / 더 낮은 경우)");
        for (Map.Entry<String, List<Run>> g : groups.entrySet()) {
            List<Run> ok = g.getValue().stream().filter(r -> r.extra() != null && !r.extra().coverage().isEmpty()).toList();
            if (ok.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (Criterion c : ok.get(0).extra().coverage().keySet()) {
                double s = ok.stream().mapToDouble(r -> r.extra().coverage().get(c)[0]).average().orElse(0);
                double t = ok.stream().mapToDouble(r -> r.extra().coverage().get(c)[1]).average().orElse(0);
                long up = ok.stream().filter(r -> r.extra().coverage().get(c)[1] > r.extra().coverage().get(c)[0] + 1e-9).count();
                long down = ok.stream().filter(r -> r.extra().coverage().get(c)[1] < r.extra().coverage().get(c)[0] - 1e-9).count();
                line.append(String.format("  %s %.0f%%→%.0f%% (%d/%d)", c, 100 * s, 100 * t, up, down));
            }
            o.line("  %-13s%s", g.getKey(), line);
        }

        o.line("");
        o.line("■ 휠체어: 제외 없는 최단과 비교 (휠체어 최단 ÷ 모든 길 최단)");
        for (String key : List.of("wheelchair 낮", "wheelchair 밤")) {
            List<Run> ok = groups.getOrDefault(key, List.of()).stream().filter(r -> r.extra() != null).toList();
            if (ok.isEmpty()) {
                continue;
            }
            long longer = ok.stream().filter(r -> shortestOf(r) > r.extra().generalM() + 0.5).count();
            double avgExtra = ok.stream().filter(r -> shortestOf(r) > r.extra().generalM() + 0.5)
                    .mapToDouble(r -> shortestOf(r) - r.extra().generalM()).average().orElse(0);
            double maxRatio = ok.stream().mapToDouble(r -> shortestOf(r) / r.extra().generalM()).max().orElse(1);
            o.line("  %-13s 최단부터 다른 경우 %d/%d (%.1f%%), 그때 평균 +%.0fm, 최대 %.2f배", key, longer, ok.size(),
                    100.0 * longer / ok.size(), avgExtra, maxRatio);
        }

        o.line("");
        o.line("■ 자동차전용도로(보행 금지)를 지나는 경로 (전체 %d개 길)", carOnly.size());
        for (Map.Entry<String, List<Run>> g : groups.entrySet()) {
            List<Run> ok = g.getValue().stream().filter(r -> r.extra() != null).toList();
            long sh = ok.stream().filter(r -> r.extra().carOnlyOnShortest()).count();
            long sa = ok.stream().filter(r -> r.extra().carOnlyOnSafe()).count();
            o.line("  %-13s 최단 %d/%d, 안전 %d/%d", g.getKey(), sh, ok.size(), sa, ok.size());
        }

        o.line("");
        carOnlyCases.forEach(c -> o.line("    예: " + c));
        o.line("");
        o.line("■ 노인: 제외 없는 최단에 계단이 있던 경우 안전 경로가 계단을 피했는지");
        for (String key : List.of("senior 낮", "senior 밤", "adult 밤")) {
            List<Run> ok = groups.getOrDefault(key, List.of()).stream()
                    .filter(r -> r.extra() != null && r.extra().stairsOnGeneral()).toList();
            long avoided = ok.stream().filter(r -> !r.extra().stairsOnSafe()).count();
            o.line("  %-13s 계단 있던 쌍 %d, 피함 %d (%.0f%%)", key, ok.size(), avoided,
                    ok.isEmpty() ? 0 : 100.0 * avoided / ok.size());
        }
    }

    /** 이 실행의 최단 길이 (이 모드가 지날 수 있는 길 중) */
    static double shortestOf(Run r) {
        return r.extra().shortestM();
    }

    static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
