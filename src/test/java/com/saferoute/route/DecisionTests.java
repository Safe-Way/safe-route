package com.saferoute.route;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleFunction;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsWeightedGraph;
import org.jgrapht.graph.MaskSubgraph;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.saferoute.ingest.FacilityTable;
import com.saferoute.route.ChosunNetworkBuilder.Grid;
import com.saferoute.route.ChosunNetworkBuilder.Spot;
import com.saferoute.route.TestSupport.Region;
import com.saferoute.route.TestSupport.Route;
import com.saferoute.route.TestSupport.Out;
import com.saferoute.route.data.MysqlFacilitySource;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.NightOption;
import com.saferoute.situation.NightResult;
import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 2026-11-02 결정 문서("경로 엔진 결정 사항 정리")의 규칙을 그대로 구현해 네 지역에서 확인한다.
 * 서버 코드가 아니라 이 테스트 안에서만 구현한다. 아직 정하지 않은 부분은 아래처럼 가정한다.
 * <ul>
 *   <li>선호(가까운 시설, 밝은 길)는 우회 한도 안에서 가장 강하게: 한도를 넘지 않는 가장 큰 λ를 이분 탐색</li>
 *   <li>"밝기 > CCTV > 도움 지점"은 9 : 3 : 1 비율로 근사</li>
 *   <li>커버율 = 길 길이 중 시설 반경 안에 드는 비율 (10m 간격 표본)</li>
 * </ul>
 * 실행: ./mvnw test -Dtest=DecisionTests -Ddecision
 */
class DecisionTests {

    static final List<Region> REGIONS = List.of(
            new Region("광주 조선대(동구)", ChosunNetworkBuilder.OSM),
            new Region("광주 북구", Path.of("data", "raw", "osm", "gwangju_bukgu.osm")),
            new Region("서울 관악구", Path.of("data", "raw", "osm", "seoul_gwanak.osm")),
            new Region("부산 부산대(금정구)", Path.of("data", "raw", "osm", "busan_pnu.osm")));

    static final Set<FacilityTable> HELP_DAY = EnumSet.of(FacilityTable.POLICE, FacilityTable.CONVENIENCE_STORE,
            FacilityTable.EMERGENCY_BELL, FacilityTable.CHILD_SAFETY_HOUSE);
    static final Set<FacilityTable> HELP_NIGHT = EnumSet.of(FacilityTable.POLICE, FacilityTable.CONVENIENCE_STORE,
            FacilityTable.EMERGENCY_BELL);
    static final Set<FacilityTable> HELP_NIGHT_NO_STORE = EnumSet.of(FacilityTable.POLICE, FacilityTable.EMERGENCY_BELL);
    static final Set<FacilityTable> REST = EnumSet.of(FacilityTable.HEAT_SHELTER, FacilityTable.COLD_SHELTER,
            FacilityTable.PUBLIC_TOILET);
    static final Set<FacilityTable> REST_NO_COLD = EnumSet.of(FacilityTable.HEAT_SHELTER, FacilityTable.PUBLIC_TOILET);

    /** 길마다 커버율(0~1)을 계산해 둔 지표 */
    enum Cov {
        LIGHT30, CCTV50, LIGHT_ROAD30, CCTV_ROAD50, HELP_DAY150, HELP_NIGHT150, HELP_NIGHT_NO_STORE150, HELP_NIGHT160, HELP_NIGHT300, REST120, REST_NO_COLD120, HELP_NIGHT120, TOILET120, SCHOOL300
    }

    /** 한 경로 규칙의 집계 */
    static final class Stat {
        int n, noRoute, stairsPairs, stairsAvoided;
        double detour, unknownCurb;
        final double[] cov = new double[Cov.values().length];

        String covs(Cov... cs) {
            StringBuilder sb = new StringBuilder();
            for (Cov c : cs) {
                sb.append(" %s %4.1f%%".formatted(c.name(), n == 0 ? 0 : 100 * cov[c.ordinal()] / n));
            }
            return sb.toString();
        }
    }

    @Test
    void decisions() throws Exception {
        assumeTrue(System.getProperty("decision") != null, "-Ddecision을 줄 때만 실행");
        for (Region r : REGIONS) {
            assumeTrue(Files.exists(r.osm()), "OSM 파일이 없음: " + r.osm());
        }
        Map<String, String> env = TestSupport.readEnv();
        JdbcTemplate teamDb = new JdbcTemplate(new SingleConnectionDataSource(env.get("DB_URL"),
                env.get("DB_USERNAME"), env.get("DB_PASSWORD"), true));
        Map<String, String> review = ChosunNetworkBuilder.readReview(
                new ClassPathResource("db/footbridge_review.csv").getContentAsString(StandardCharsets.UTF_8));

        Out o = new Out("결정 사항 확인 (지역별 200쌍, 직선 0.5~3km, 긴급 안내는 100곳)");
        for (Region region : REGIONS) {
            long t0 = System.currentTimeMillis();
            ChosunNetworkBuilder b = new ChosunNetworkBuilder(region.osm());
            b.readOsm();
            b.split();
            b.addFootbridges(teamDb, review);
            b.computePenalties(teamDb);
            RoadGraph g = b.toRecommendedGraph();

            List<double[]> pts = b.edges.stream().flatMap(e -> e.line().stream()).toList();
            double lat = pts.stream().mapToDouble(p -> p[1]).average().orElse(35);
            double lon = pts.stream().mapToDouble(p -> p[0]).average().orElse(127);
            List<Facility> facs = new MysqlFacilitySource(teamDb).within(BBox.around(pts, 400),
                    EnumSet.allOf(FacilityTable.class));
            BBox zbox = BBox.around(pts, 400);
            List<Spot> schools = teamDb.query("SELECT lon, lat FROM child_protection_zone WHERE MBRContains(ST_GeomFromText(?, 0), geom)",
                    (rs, i) -> new Spot(rs.getDouble(1), rs.getDouble(2), 1, null), zbox.toWkt());
            Map<Long, double[]> cov = coverage(g, facs, schools, lat);
            o.line("  어린이보호구역 대상 시설 %d곳", schools.size());

            o.line("");
            o.line("■ %s: 길 %,d개 %.0fkm, 시설 %s", region.name(), g.graph().edgeSet().size(),
                    g.graph().edgeSet().stream().mapToDouble(RoadEdge::lengthM).sum() / 1000, count(facs));
            long curbKnown = g.graph().edgeSet().stream().filter(e -> e.penalty().get(Penalty.CURB) != 0.5
                    && e.penalty().get(Penalty.CURB) != 0).count();
            long curbUnknown = g.graph().edgeSet().stream().filter(e -> e.penalty().get(Penalty.CURB) == 0.5).count();
            o.line("  횡단 길: 보도턱 모름 %d, 턱 확인(낮춤 없음) %d", curbUnknown, curbKnown);

            // 1. 도로망 전체 커버율 (반경 결정, 지킴이집·편의점·한파쉼터 결정)
            double total = g.graph().edgeSet().stream().mapToDouble(RoadEdge::lengthM).sum();
            StringBuilder net = new StringBuilder("  도로망 커버율:");
            for (Cov c : Cov.values()) {
                double s = g.graph().edgeSet().stream().mapToDouble(e -> e.lengthM() * cov.get(e.id())[c.ordinal()]).sum();
                net.append(" %s %.0f%%".formatted(c.name(), 100 * s / total));
            }
            o.line(net.toString());

            // 2. 밤 시작 (어린이 일몰 60분 전)
            ZonedDateTime day = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            NightResult sun = new NightJudge().judge(lat, lon, day, NightOption.AUTO);
            o.line("  2026-11-02 일몰 %s → 어린이 밤 시작 %s", sun.sunset().withNano(0),
                    sun.sunset().minusMinutes(60).withNano(0));

            // 3. 모드별 경로
            runPairs(o, g, cov);

            // 4. 긴급 안내
            emergency(o, g, facs, lat);
            o.line("  (소요 %.0f초)", (System.currentTimeMillis() - t0) / 1000.0);
        }
        o.print();
        Files.writeString(Path.of("target", "decision-report.txt"), o.toString());
    }

    // ------------------------------------------------------------------ 경로 규칙

    static boolean noStairs(RoadEdge e) {
        return e.penalty().get(Penalty.STAIRS) < 1;
    }

    /** 휠체어 (결정): 계단·확인된 턱·못 지나는 육교 제외 */
    static boolean wheelchair(RoadEdge e) {
        return e.penalty().get(Penalty.STAIRS) < 1 && e.penalty().get(Penalty.CURB) < 1
                && e.penalty().get(Penalty.FOOTBRIDGE) < 1;
    }

    /** 휠체어 (비교안): 보도턱·육교를 모르면(0.5)도 제외 */
    static boolean wheelchairStrict(RoadEdge e) {
        return wheelchair(e) && e.penalty().get(Penalty.CURB) < 0.5 && e.penalty().get(Penalty.FOOTBRIDGE) < 0.5;
    }

    static double miss(Map<Long, double[]> cov, RoadEdge e, Cov c) {
        return 1 - cov.get(e.id())[c.ordinal()];
    }

    /** 밤: 밝기 > CCTV > 도움 지점 (9 : 3 : 1) */
    static double night(Map<Long, double[]> cov, RoadEdge e, Cov help) {
        return night(cov, e, help, 9, 3, 1);
    }

    /** 밤 비율을 바꿔 본다. 합이 13이 되게 맞춰 9:3:1과 같은 세기로 비교한다 */
    static double night(Map<Long, double[]> cov, RoadEdge e, Cov help, double light, double cctv, double h) {
        double k = 13 / (light + cctv + h);
        return e.lengthM() * k * (light * miss(cov, e, Cov.LIGHT_ROAD30) + cctv * miss(cov, e, Cov.CCTV_ROAD50)
                + h * miss(cov, e, help));
    }

    record Rule(String name, double cap, Predicate<RoadEdge> passable, ToDoubleFunction<RoadEdge> extra) {
    }

    static List<Rule> rules(Map<Long, double[]> cov) {
        ToDoubleFunction<RoadEdge> stairsWeight = e -> e.lengthM() * 0.6 * e.penalty().get(Penalty.STAIRS);
        ToDoubleFunction<RoadEdge> stairsFixed = e -> 150 * (noStairs(e) ? 0 : 1);
        ToDoubleFunction<RoadEdge> rest = e -> e.lengthM() * 4 * miss(cov, e, Cov.REST120);
        return List.of(
                new Rule("어른 낮 (최단)", 1.3, e -> true, e -> 0),
                new Rule("어린이 낮 (도움 지점 150m)", 1.3, e -> true, e -> e.lengthM() * 4 * miss(cov, e, Cov.HELP_DAY150)),
                new Rule("어린이 낮 시설3:구역1", 1.3, e -> true,
                        e -> e.lengthM() * (3 * miss(cov, e, Cov.HELP_DAY150) + miss(cov, e, Cov.SCHOOL300))),
                new Rule("어린이 낮 시설1:구역1", 1.3, e -> true,
                        e -> e.lengthM() * 2 * (miss(cov, e, Cov.HELP_DAY150) + miss(cov, e, Cov.SCHOOL300))),
                new Rule("어린이 낮 구역3:시설1", 1.3, e -> true,
                        e -> e.lengthM() * (miss(cov, e, Cov.HELP_DAY150) + 3 * miss(cov, e, Cov.SCHOOL300))),
                new Rule("어린이 낮 구역만", 1.3, e -> true, e -> e.lengthM() * 4 * miss(cov, e, Cov.SCHOOL300)),
                new Rule("노인 낮 (계단 0.6×길이 + 쉼 곳 120m)", 1.2, e -> true,
                        e -> stairsWeight.applyAsDouble(e) + rest.applyAsDouble(e)),
                new Rule("노인 낮 (비교: 계단 1곳 +150m + 쉼 곳)", 1.2, e -> true,
                        e -> stairsFixed.applyAsDouble(e) + rest.applyAsDouble(e)),
                new Rule("노인 조건부 (계단 없는 길 1.2배 안이면 계단 제외 + 쉼 곳)", 1.2, e -> true, rest),
                new Rule("노인 조건부 밤 (계단 제외 조건 + 밝기>CCTV·쉼 곳)", 1.2, e -> true,
                        e -> night(cov, e, Cov.REST120)),
                new Rule("노인 조건부 밤 새 순서 (밝기27:시설9:CCTV3:화장실1, 120m)", 1.2, e -> true,
                        e -> e.lengthM() * 13.0 / 40 * (27 * miss(cov, e, Cov.LIGHT_ROAD30) + 9 * miss(cov, e, Cov.HELP_NIGHT120)
                                + 3 * miss(cov, e, Cov.CCTV_ROAD50) + miss(cov, e, Cov.TOILET120))),
                new Rule("휠체어 (제외 + 최단, 모름은 통과)", 99, DecisionTests::wheelchair, e -> 0),
                new Rule("휠체어 (비교: 모름도 제외)", 99, DecisionTests::wheelchairStrict, e -> 0),
                new Rule("어른 밤 (밝기>CCTV>도움 300m)", 1.3, e -> true, e -> night(cov, e, Cov.HELP_NIGHT300)),
                new Rule("어른 밤 엄격 우선순위 (10000:100:1)", 1.3, e -> true,
                        e -> night(cov, e, Cov.HELP_NIGHT300, 10000, 100, 1)),
                new Rule("어른 밤 같은 비중 (1:1:1)", 1.3, e -> true, e -> night(cov, e, Cov.HELP_NIGHT300, 1, 1, 1)),
                new Rule("어른 밤 새 순서 (밝기9:시설3:CCTV1, 300m)", 1.3, e -> true,
                        e -> night(cov, e, Cov.HELP_NIGHT300, 9, 1, 3)),
                new Rule("어른 밤 새 순서 (밝기9:시설3:CCTV1, 160m)", 1.3, e -> true,
                        e -> night(cov, e, Cov.HELP_NIGHT160, 9, 1, 3)),
                new Rule("어린이 밤 새 순서 (밝기9:시설3:CCTV1, 150m)", 1.3, e -> true,
                        e -> night(cov, e, Cov.HELP_NIGHT150, 9, 1, 3)),
                new Rule("어린이 밤 구역 4순위 (밝기9:시설3:CCTV1:구역1)", 1.3, e -> true,
                        e -> e.lengthM() * (9 * miss(cov, e, Cov.LIGHT_ROAD30) + 3 * miss(cov, e, Cov.HELP_NIGHT150)
                                + miss(cov, e, Cov.CCTV_ROAD50) + miss(cov, e, Cov.SCHOOL300))),
                new Rule("어린이 밤 구역 2순위 (밝기9:구역3:시설3:CCTV1)", 1.3, e -> true,
                        e -> e.lengthM() * 13.0 / 16 * (9 * miss(cov, e, Cov.LIGHT_ROAD30) + 3 * miss(cov, e, Cov.SCHOOL300)
                                + 3 * miss(cov, e, Cov.HELP_NIGHT150) + miss(cov, e, Cov.CCTV_ROAD50))),
                new Rule("어린이 밤 (밝기>CCTV>도움 150m)", 1.3, e -> true, e -> night(cov, e, Cov.HELP_NIGHT150)),
                new Rule("어린이 밤 엄격 우선순위 (10000:100:1)", 1.3, e -> true,
                        e -> night(cov, e, Cov.HELP_NIGHT150, 10000, 100, 1)),
                new Rule("노인 밤 (밝기>CCTV + 계단·쉼 곳)", 1.2, e -> true,
                        e -> night(cov, e, Cov.REST120) + stairsWeight.applyAsDouble(e)));
    }

    /** 한도 안에서 가장 강한 선호: extra를 λ배 해서 더하고, 우회 한도를 넘지 않는 가장 큰 λ */
    static Route find(RoadGraph g, Graph<Long, RoadEdge> usable, long a, long z, ToDoubleFunction<RoadEdge> extra,
            double cap) {
        Route shortest = TestSupport.search(g, usable, a, z, RoadEdge::lengthM);
        if (shortest == null) {
            return null;
        }
        DoubleFunction<Route> at = l -> TestSupport.search(g, usable, a, z,
                e -> e.lengthM() + l * extra.applyAsDouble(e));
        Route full = at.apply(1);
        if (full.length() <= shortest.length() * cap) {
            return full;
        }
        double lo = 0, hi = 1;
        Route best = shortest;
        for (int i = 0; i < 7; i++) {
            double mid = (lo + hi) / 2;
            Route r = at.apply(mid);
            if (r.length() <= shortest.length() * cap) {
                lo = mid;
                best = r;
            } else {
                hi = mid;
            }
        }
        return best;
    }

    void runPairs(Out o, RoadGraph g, Map<Long, double[]> cov) {
        List<Rule> rules = rules(cov);
        Map<String, Stat> stats = new HashMap<>();
        Map<String, Graph<Long, RoadEdge>> masks = new HashMap<>();
        for (Rule r : rules) {
            stats.put(r.name(), new Stat());
            masks.put(r.name(), new MaskSubgraph<>(g.graph(), v -> false, e -> !r.passable().test(e)));
        }
        Stat adultShort = new Stat();
        List<Long> nodes = new ArrayList<>(g.graph().vertexSet());
        nodes.removeIf(n -> g.graph().degreeOf(n) == 0);
        Random rnd = new Random(1);
        int pairs = 0;
        while (pairs < 200) {
            long a = nodes.get(rnd.nextInt(nodes.size()));
            long z = nodes.get(rnd.nextInt(nodes.size()));
            double d = Geo.distance(g.point(a), g.point(z));
            if (d < 500 || d > 3000) {
                continue;
            }
            Route base = TestSupport.search(g, g.graph(), a, z, RoadEdge::lengthM);
            if (base == null) {
                continue;
            }
            pairs++;
            add(adultShort, base, base, cov);
            boolean baseStairs = base.edges().stream().anyMatch(e -> !noStairs(e));
            for (Rule r : rules) {
                Stat s = stats.get(r.name());
                Route rt = r.name().startsWith("노인 조건부")
                        ? seniorConditional(g, masks.get("어른 낮 (최단)"), noStairsMask(g, masks), a, z, r.extra(), base)
                        : find(g, masks.get(r.name()), a, z, r.extra(), r.cap());
                if (rt == null) {
                    s.noRoute++;
                    continue;
                }
                add(s, rt, base, cov);
                if (baseStairs) {
                    s.stairsPairs++;
                    if (rt.edges().stream().allMatch(DecisionTests::noStairs)) {
                        s.stairsAvoided++;
                    }
                }
            }
        }
        o.line("  [경로] 우회율·커버율은 같은 쌍의 어른 최단 대비. 어른 최단 기준:%s",
                adultShort.covs(Cov.LIGHT_ROAD30, Cov.CCTV_ROAD50, Cov.HELP_NIGHT160, Cov.HELP_DAY150, Cov.SCHOOL300, Cov.REST120));
        for (Rule r : rules) {
            Stat s = stats.get(r.name());
            String extra = switch (r.name().substring(0, 2)) {
                case "어린" -> s.covs(Cov.HELP_DAY150, Cov.SCHOOL300, Cov.HELP_NIGHT150, Cov.LIGHT_ROAD30, Cov.CCTV_ROAD50);
                case "노인" -> s.covs(Cov.REST120, Cov.TOILET120, Cov.HELP_NIGHT120, Cov.LIGHT_ROAD30, Cov.CCTV_ROAD50) + " 계단 피함 %d/%d쌍".formatted(s.stairsAvoided,
                        s.stairsPairs);
                case "휠체" -> " 계단 지남 %d/%d쌍, 보도턱 모르는 횡단 경로당 %.1f곳".formatted(s.stairsPairs - s.stairsAvoided,
                        s.stairsPairs, s.n == 0 ? 0 : s.unknownCurb / s.n);
                default -> s.covs(Cov.LIGHT30, Cov.CCTV50, Cov.LIGHT_ROAD30, Cov.CCTV_ROAD50, Cov.HELP_NIGHT160, Cov.HELP_NIGHT300);
            };
            o.line("    %-34s 경로 %3d/200, 우회 %.3f |%s", r.name(), s.n, s.n == 0 ? 0 : s.detour / s.n, extra);
        }
    }

    static Graph<Long, RoadEdge> noStairsMask(RoadGraph g, Map<String, Graph<Long, RoadEdge>> masks) {
        return masks.computeIfAbsent("계단 없음", k -> new MaskSubgraph<>(g.graph(), v -> false, e -> !noStairs(e)));
    }

    /**
     * 노인 조건부 제외(추천안): 계단 없는 길이 어른 최단의 1.2배 안이면 계단 길을 빼고 그 안에서 쉼 곳을 선호한다.
     * 없으면 계단을 허용한다 (경로는 늘 나온다).
     */
    static Route seniorConditional(RoadGraph g, Graph<Long, RoadEdge> all, Graph<Long, RoadEdge> noStairs, long a,
            long z, ToDoubleFunction<RoadEdge> extra, Route base) {
        Route free = TestSupport.search(g, noStairs, a, z, RoadEdge::lengthM);
        if (free != null && free.length() <= base.length() * 1.2) {
            return find(g, noStairs, a, z, extra, base.length() * 1.2 / free.length());
        }
        return find(g, all, a, z, extra, 1.2);
    }

    static void add(Stat s, Route r, Route base, Map<Long, double[]> cov) {
        s.n++;
        s.detour += r.length() / base.length();
        for (Cov c : Cov.values()) {
            double in = r.edges().stream().mapToDouble(e -> e.lengthM() * cov.get(e.id())[c.ordinal()]).sum();
            s.cov[c.ordinal()] += in / r.length();
        }
        s.unknownCurb += r.edges().stream().filter(e -> e.penalty().get(Penalty.CURB) == 0.5).count();
    }

    // ------------------------------------------------------------------ 커버율

    /** 길마다 지표별 커버율. LIGHT30은 도로망 도구가 계산한 값(1 − 밝기 감점)을 그대로 쓴다 */
    static Map<Long, double[]> coverage(RoadGraph g, List<Facility> facs, List<Spot> schools, double lat) {
        Map<Cov, Grid> grids = new HashMap<>();
        grids.put(Cov.CCTV50, grid(facs, EnumSet.of(FacilityTable.CCTV), lat));
        grids.put(Cov.HELP_DAY150, grid(facs, HELP_DAY, lat));
        grids.put(Cov.HELP_NIGHT150, grid(facs, HELP_NIGHT, lat));
        grids.put(Cov.HELP_NIGHT_NO_STORE150, grid(facs, HELP_NIGHT_NO_STORE, lat));
        grids.put(Cov.HELP_NIGHT300, grids.get(Cov.HELP_NIGHT150));
        grids.put(Cov.HELP_NIGHT160, grids.get(Cov.HELP_NIGHT150));
        grids.put(Cov.HELP_NIGHT120, grids.get(Cov.HELP_NIGHT150));
        grids.put(Cov.TOILET120, grid(facs, EnumSet.of(FacilityTable.PUBLIC_TOILET), lat));
        grids.put(Cov.SCHOOL300, new Grid(schools, lat));
        grids.put(Cov.REST120, grid(facs, REST, lat));
        grids.put(Cov.REST_NO_COLD120, grid(facs, REST_NO_COLD, lat));
        Map<Cov, Double> radius = Map.ofEntries(Map.entry(Cov.CCTV50, 50.0), Map.entry(Cov.HELP_DAY150, 150.0), Map.entry(Cov.HELP_NIGHT150, 150.0), Map.entry(Cov.HELP_NIGHT_NO_STORE150, 150.0), Map.entry(Cov.HELP_NIGHT160, 160.0), Map.entry(Cov.HELP_NIGHT120, 120.0), Map.entry(Cov.TOILET120, 120.0), Map.entry(Cov.SCHOOL300, 300.0), Map.entry(Cov.HELP_NIGHT300, 300.0), Map.entry(Cov.REST120, 120.0), Map.entry(Cov.REST_NO_COLD120, 120.0));
        Map<Long, double[]> out = new HashMap<>();
        for (RoadEdge e : g.graph().edgeSet()) {
            double[] v = new double[Cov.values().length];
            v[Cov.LIGHT30.ordinal()] = 1 - e.penalty().get(Penalty.LIGHT);
            List<double[]> samples = samples(e.geometry());
            for (Map.Entry<Cov, Grid> c : grids.entrySet()) {
                double r = radius.get(c.getKey());
                long in = samples.stream().filter(p -> !c.getValue().near(List.of(p), r).isEmpty()).count();
                v[c.getKey().ordinal()] = samples.isEmpty() ? 0 : (double) in / samples.size();
            }
            out.put(e.id(), v);
        }
        Map<Long, Double> light = roadCoverage(g, facs.stream().filter(f -> f.type() == FacilityTable.STREETLIGHT
                || f.type() == FacilityTable.SECURITY_LIGHT).toList(), 30, lat);
        Map<Long, Double> cctv = roadCoverage(g, facs.stream().filter(f -> f.type() == FacilityTable.CCTV).toList(), 50, lat);
        out.forEach((id, v) -> {
            v[Cov.LIGHT_ROAD30.ordinal()] = light.get(id);
            v[Cov.CCTV_ROAD50.ordinal()] = cctv.get(id);
        });
        return out;
    }

    /** 시설을 가장 가까운 길에 붙일 때 허용하는 거리. 넘으면 길을 비추거나 찍지 않는다고 본다 */
    static final double SNAP_MAX_M = 20;

    /**
     * 길 따라 커버율: 시설을 가장 가까운 길(20m 안)에 붙이고, 붙인 곳에서 길을 따라 radius 안을 덮인 구간으로 본다.
     * 직선 반경과 달리 건물 너머 반대편 길은 길로 이어진 거리가 멀어 빠진다.
     */
    static Map<Long, Double> roadCoverage(RoadGraph g, List<Facility> facs, double radius, double lat) {
        double kx = 111_320 * Math.cos(Math.toRadians(lat)), ky = 110_540, cell = 50;
        Map<Long, List<RoadEdge>> index = new HashMap<>();
        for (RoadEdge e : g.graph().edgeSet()) {
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (double[] p : e.geometry()) {
                x0 = Math.min(x0, p[0] * kx);
                x1 = Math.max(x1, p[0] * kx);
                y0 = Math.min(y0, p[1] * ky);
                y1 = Math.max(y1, p[1] * ky);
            }
            for (long x = (long) Math.floor(x0 / cell); x <= (long) Math.floor(x1 / cell); x++) {
                for (long y = (long) Math.floor(y0 / cell); y <= (long) Math.floor(y1 / cell); y++) {
                    index.computeIfAbsent(x * 1_000_003 + y, k -> new ArrayList<>()).add(e);
                }
            }
        }
        // 1. 시설마다 가장 가까운 길과 그 길의 from에서 잰 위치(m)
        Map<Long, List<Double>> onEdge = new HashMap<>();
        Map<Long, Double> dist = new HashMap<>();
        java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        for (Facility f : facs) {
            double fx = f.lon() * kx, fy = f.lat() * ky;
            RoadEdge best = null;
            double bestD = SNAP_MAX_M, bestS = 0;
            long cx = (long) Math.floor(fx / cell), cy = (long) Math.floor(fy / cell);
            for (long x = cx - 1; x <= cx + 1; x++) {
                for (long y = cy - 1; y <= cy + 1; y++) {
                    for (RoadEdge e : index.getOrDefault(x * 1_000_003 + y, List.of())) {
                        double run = 0;
                        List<double[]> line = e.geometry();
                        for (int i = 1; i < line.size(); i++) {
                            double ax = line.get(i - 1)[0] * kx, ay = line.get(i - 1)[1] * ky;
                            double bx = line.get(i)[0] * kx, by = line.get(i)[1] * ky;
                            double len = Math.hypot(bx - ax, by - ay);
                            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((fx - ax) * (bx - ax) + (fy - ay) * (by - ay)) / (len * len)));
                            double d = Math.hypot(ax + (bx - ax) * t - fx, ay + (by - ay) * t - fy);
                            if (d < bestD) {
                                bestD = d;
                                best = e;
                                bestS = (run + t * len) / Math.max(1e-9, edgeLen(line, kx, ky)) * e.lengthM();
                            }
                            run += len;
                        }
                    }
                }
            }
            if (best == null) {
                continue;
            }
            onEdge.computeIfAbsent(best.id(), k -> new ArrayList<>()).add(bestS);
            pq.add(new double[] {bestS, best.from()});
            pq.add(new double[] {best.lengthM() - bestS, best.to()});
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
                double nd = top[0] + e.lengthM();
                if (nd <= radius && nd < dist.getOrDefault(m, Double.MAX_VALUE)) {
                    pq.add(new double[] {nd, m});
                }
            }
        }
        // 3. 길마다 10m 표본이 덮였는지
        Map<Long, Double> out = new HashMap<>();
        for (RoadEdge e : g.graph().edgeSet()) {
            double len = e.lengthM();
            int k = Math.max(1, (int) (len / 10));
            double df = dist.getOrDefault(e.from(), Double.MAX_VALUE), dt = dist.getOrDefault(e.to(), Double.MAX_VALUE);
            List<Double> same = onEdge.getOrDefault(e.id(), List.of());
            int in = 0;
            for (int j = 0; j < k; j++) {
                double t = (j + 0.5) / k * len;
                double d = Math.min(df + t, dt + len - t);
                for (double s : same) {
                    d = Math.min(d, Math.abs(t - s));
                }
                in += d <= radius ? 1 : 0;
            }
            out.put(e.id(), (double) in / k);
        }
        return out;
    }

    static double edgeLen(List<double[]> line, double kx, double ky) {
        double run = 0;
        for (int i = 1; i < line.size(); i++) {
            run += Math.hypot((line.get(i)[0] - line.get(i - 1)[0]) * kx, (line.get(i)[1] - line.get(i - 1)[1]) * ky);
        }
        return run;
    }

    static Grid grid(List<Facility> facs, Set<FacilityTable> types, double lat) {
        return new Grid(facs.stream().filter(f -> types.contains(f.type()))
                .map(f -> new Spot(f.lon(), f.lat(), f.count(), null)).toList(), lat);
    }

    /** 선을 10m 간격으로 나눈 점 (구간 가운데) */
    static List<double[]> samples(List<double[]> line) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            int k = Math.max(1, (int) (Geo.distance(a, b) / 10));
            for (int j = 0; j < k; j++) {
                double f = (j + 0.5) / k;
                out.add(new double[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f});
            }
        }
        return out;
    }

    static String count(List<Facility> facs) {
        Map<FacilityTable, Integer> n = new java.util.EnumMap<>(FacilityTable.class);
        facs.forEach(f -> n.merge(f.type(), 1, Integer::sum));
        return n.toString();
    }

    // ------------------------------------------------------------------ 긴급 안내

    /** 시설을 가장 가까운 노드에 붙인다 (100m 넘게 떨어지면 뺀다) */
    static Map<Long, List<Facility>> snap(RoadGraph g, List<Facility> facs) {
        Map<Long, List<Facility>> out = new HashMap<>();
        for (Facility f : facs) {
            RoadGraph.Snap s = g.nearest(f.lon(), f.lat());
            if (s != null && s.distanceM() <= 100) {
                out.computeIfAbsent(s.node(), k -> new ArrayList<>()).add(f);
            }
        }
        return out;
    }

    void emergency(Out o, RoadGraph g, List<Facility> all, double lat) {
        List<Facility> help = all.stream().filter(f -> HELP_DAY.contains(f.type())).toList();
        Map<Long, List<Facility>> at = snap(g, help);
        List<Long> nodes = new ArrayList<>(g.graph().vertexSet());
        nodes.removeIf(n -> g.graph().degreeOf(n) == 0);
        Random rnd = new Random(2);
        for (String label : List.of("낮(4종)", "밤(지킴이집 제외)", "휠체어 밤")) {
            Set<FacilityTable> types = label.startsWith("낮") ? HELP_DAY : HELP_NIGHT;
            Graph<Long, RoadEdge> usable = label.startsWith("휠체어")
                    ? new MaskSubgraph<>(g.graph(), v -> false, e -> !wheelchair(e)) : g.graph();
            var dij = new DijkstraShortestPath<>(new AsWeightedGraph<>(usable, RoadEdge::lengthM, false, false));
            int n = 0, differ = 0, within300 = 0, unreachable = 0;
            double walk = 0, extra = 0;
            for (int i = 0; i < 100; i++) {
                long src = nodes.get(rnd.nextInt(nodes.size()));
                ShortestPathAlgorithm.SingleSourcePaths<Long, RoadEdge> paths = dij.getPaths(src);
                double bestWalk = Double.MAX_VALUE, straightBestD = Double.MAX_VALUE, walkOfStraight = Double.MAX_VALUE;
                for (Map.Entry<Long, List<Facility>> e : at.entrySet()) {
                    if (e.getValue().stream().noneMatch(f -> types.contains(f.type()))) {
                        continue;
                    }
                    double w = paths.getWeight(e.getKey());
                    double s = e.getValue().stream().filter(f -> types.contains(f.type()))
                            .mapToDouble(f -> Geo.distance(g.point(src), f.point())).min().orElse(Double.MAX_VALUE);
                    bestWalk = Math.min(bestWalk, w);
                    if (s < straightBestD) {
                        straightBestD = s;
                        walkOfStraight = w;
                    }
                }
                if (bestWalk == Double.POSITIVE_INFINITY || bestWalk == Double.MAX_VALUE) {
                    unreachable++;
                    continue;
                }
                n++;
                walk += bestWalk;
                within300 += bestWalk <= 300 ? 1 : 0;
                if (walkOfStraight > bestWalk + 1) {
                    differ++;
                    extra += walkOfStraight == Double.POSITIVE_INFINITY ? 0 : walkOfStraight - bestWalk;
                }
            }
            o.line("  [긴급 %s] 평균 걷는 거리 %.0fm, 300m 안 %d/%d곳, 직선 최근접 ≠ 걷기 최근접 %d곳 (그때 평균 %.0fm 더 걸음)%s",
                    label, n == 0 ? 0 : walk / n, within300, n, differ, differ == 0 ? 0 : extra / differ,
                    unreachable > 0 ? ", 도달 불가 %d곳".formatted(unreachable) : "");
        }
    }
}
