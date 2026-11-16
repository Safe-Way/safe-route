package com.saferoute.route;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.saferoute.route.data.PreferenceSource;
import com.saferoute.route.TestSupport.Out;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.MysqlPoiSource;
import com.saferoute.route.data.MysqlRoadGraphSource;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;

/**
 * 비 오는 날 기준(walk-more)이 일반 경로를 얼마나 흔드는지(우회 비용)와 모드 기준을 지키는지 잰다 (2026-11-09, 004 계획 8.2·9장 F4).
 * 결과로 값을 바꾸지 않고 보고만 한다 (값은 사용자 결정). 팀 DB만 쓰고 외부 날씨는 부르지 않는다: 날씨 없는 엔진에 rain on·off를 준다.
 * <pre>
 * 실행: ./mvnw test -Dtest=RainSensitivityCheck -Dlive
 * 쌍:   시연 길 1 + 임의 쌍 40(RouteRandomCheck와 같은 방법이라 같은 -Dseed면 같은 쌍) + 역 20곳 가로지르기
 *       (대합실 점 -1000001~-1000020의 서쪽 약 300m → 동쪽 약 300m)
 * 설정: 4모드 × 12시·21시. 기준선 rain off, 비교 rain on × walk-more 0.4 (확정값, 0.3·0.5 비교는 2026-11-09 보고)
 * </pre>
 * 좌표는 출력하지 않는다 (점 번호·거리만).
 */
class RainSensitivityCheck {

    static final int RANDOM_PAIRS = 40;
    /** 역을 가로지르는 쌍: 대합실 점에서 동서로 이만큼 (m) */
    static final double ACROSS_M = 300;
    static final String[] MODES = {"adult", "child", "senior", "wheelchair"};
    static final int[] HOURS = {12, 21};

    record Pair(String name, double[] from, double[] to) {
    }

    /** 비교할 설정. overrides는 route.yml 값 덮어쓰기 */
    record Setting(String label, String rain, double walkMore, String... overrides) {
    }

    static final List<Setting> SETTINGS = List.of(
            new Setting("기준선 rain off", "off", 0),
            new Setting("walk-more 0.4", "on", 0.4, "route.rain.walk-more=0.4"));

    @Test
    void sensitivity() throws Exception {
        assumeTrue(System.getProperty("live") != null, "-Dlive를 줄 때만 실행");
        long seed = Long.getLong("seed", 261109);
        Map<String, String> env = TestSupport.readEnv();
        JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(env.get("DB_URL"), env.get("DB_USERNAME"),
                env.get("DB_PASSWORD"), true));
        MysqlRoadGraphSource roads = new MysqlRoadGraphSource(db, TestRoutes.props());
        MysqlPoiSource pois = new MysqlPoiSource(db);
        List<Pair> pairs = pairs(db, seed);

        Out o = new Out("비 오는 날 기준 민감도 (2026-11-04, 쌍 %d개 = 시연 1 + 임의 %d(시드 %d) + 역 가로지르기 %d)"
                .formatted(pairs.size(), RANDOM_PAIRS, seed, pairs.size() - 1 - RANDOM_PAIRS));
        // 시연 길 비율: 확정값(0.4)으로 지하 길을 얻는다
        RouteResult demo = run(roads, pois, at(12), SETTINGS.get(1), "adult", pairs.subList(0, 1)).get(0);
        if (demo != null && underground(demo)) {
            double ratio = (double) safe(demo).distanceM() / demo.routes().get(0).distanceM();
            StringBuilder margins = new StringBuilder();
            for (Setting s : SETTINGS.subList(1, SETTINGS.size())) {
                margins.append(" | %s → 한도 %.1f, 여유 %+.3f".formatted(s.label(), 1 + s.walkMore(), 1 + s.walkMore() - ratio));
            }
            o.line("시연 길: 비 올 때 지상 최단 %dm, 지하 %dm = %.3f배%s", demo.routes().get(0).distanceM(),
                    safe(demo).distanceM(), ratio, margins);
        } else {
            o.line("시연 길: walk-more 0.4에서 지하로 가지 않음");
        }
        o.line("시연: 시연 길 안전 경로가 지하로 가는지와 최단·안전 m");
        o.line("바뀜: 안전 경로 선이 기준선(rain off)과 다른 쌍 / 둘 다 경로를 찾은 쌍");
        o.line("추가: 안전 경로 길이 − 기준선 안전 경로 길이 평균 (괄호: 바뀐 쌍만)");
        o.line("지붕 이득: 안전 경로 covered.roof − 같은 요청 최단 경로 covered.roof 평균");
        o.line("지하: 안내 경로가 지하를 지난 쌍 수 (알림 kind underground)");
        o.line("기준 유지: 비 경로가 지붕을 안 쓴(covered.roof 0) 쌍 중 그 모드 주 기준(노인 낮 rest, 어린이 낮 school, 밤 light)");
        o.line("          커버 m가 맑은 날보다 줄어든 쌍 / 지붕 안 쓴 쌍 (평균 차이 = 비 − 맑은 날). 어른·휠체어 낮은 기준이 없어 -");
        for (int hour : HOURS) {
            ZonedDateTime now = at(hour);
            for (String mode : MODES) {
                List<RouteResult> base = run(roads, pois, now, SETTINGS.get(0), mode, pairs);
                boolean night = base.stream().filter(Objects::nonNull).anyMatch(r -> r.conditions().night());
                String key = night ? "light" : "senior".equals(mode) ? "rest" : "child".equals(mode) ? "school" : null;
                o.line("");
                o.line("■ %s %d시 (%s)", mode, hour, night ? "밤" : "낮");
                for (Setting s : SETTINGS) {
                    summarize(o, s, base, s == SETTINGS.get(0) ? base : run(roads, pois, now, s, mode, pairs), key);
                }
            }
        }
        o.print();
    }

    static ZonedDateTime at(int hour) {
        return ZonedDateTime.of(2026, 11, 4, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    }

    /** 시연 길, RouteRandomCheck와 같은 방법의 임의 쌍, 역을 동서로 가로지르는 쌍 */
    static List<Pair> pairs(JdbcTemplate db, long seed) {
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair("시연(-3001603→-3001501)", RouteLiveCheck.RAIN_DEMO.from(), RouteLiveCheck.RAIN_DEMO.to()));
        List<double[]> nodes = db.query("SELECT lon, lat FROM road_node ORDER BY RAND(?) LIMIT 4000",
                (rs, i) -> new double[] {rs.getDouble(1), rs.getDouble(2)}, seed);
        Random rnd = new Random(seed);
        int n = 0;
        while (n < RANDOM_PAIRS) {
            double[] a = nodes.get(rnd.nextInt(nodes.size()));
            double[] b = nodes.get(rnd.nextInt(nodes.size()));
            double d = Geo.distance(a, b);
            if (d >= RouteRandomCheck.MIN_M && d <= RouteRandomCheck.MAX_M) {
                pairs.add(new Pair("임의" + n++, a, b));
            }
        }
        db.query("SELECT id, lon, lat FROM road_node_excluded WHERE reason = 'underground'"
                + " AND id BETWEEN -1000020 AND -1000001 ORDER BY id DESC", rs -> {
                    double lat = rs.getDouble("lat");
                    double dLon = ACROSS_M / (111_195 * Math.cos(Math.toRadians(lat)));
                    double lon = rs.getDouble("lon");
                    pairs.add(new Pair("역" + rs.getLong("id"), new double[] {lon - dLon, lat}, new double[] {lon + dLon, lat}));
                });
        return pairs;
    }

    /** 쌍마다 이 설정으로 계산. 경로를 못 주면 null */
    static List<RouteResult> run(MysqlRoadGraphSource roads, MysqlPoiSource pois, ZonedDateTime now, Setting s,
            String mode, List<Pair> pairs) {
        RouteProperties props = TestRoutes.props(s.overrides());
        RouteService service = new RouteService(roads, pois, new NightJudge(), new RouteScorer(props),
                new RouteResultAssembler(new FacilityCounter(props), props), props,
                Clock.fixed(now.toInstant(), now.getZone()), PreferenceSource.NONE, null);
        List<RouteResult> out = new ArrayList<>();
        for (Pair p : pairs) {
            try {
                out.add(service.route(new RouteRequest(new Place(p.from()[1], p.from()[0], null),
                        new Place(p.to()[1], p.to()[0], null), "auto", mode, null, s.rain())));
            } catch (RouteException e) {
                out.add(null);
            }
        }
        return out;
    }

    /** 설정 한 줄. key: 그 모드·시각의 주 기준 covered 키 (없으면 null) */
    static void summarize(Out o, Setting s, List<RouteResult> base, List<RouteResult> res, String key) {
        int ok = 0;
        int changed = 0;
        int under = 0;
        int roofless = 0;
        int weaker = 0;
        double extra = 0;
        double extraChanged = 0;
        double gain = 0;
        double keyDiff = 0;
        for (int i = 0; i < res.size(); i++) {
            RouteResult b = base.get(i);
            RouteResult r = res.get(i);
            if (r != null && underground(r)) {
                under++;
            }
            if (b == null || r == null) {
                continue;
            }
            ok++;
            RouteResult.Route sr = safe(r);
            RouteResult.Route sb = safe(b);
            int more = sr.distanceM() - sb.distanceM();
            extra += more;
            if (!sameLine(sr.geometry(), sb.geometry())) {
                changed++;
                extraChanged += more;
            }
            int roof = sr.covered().getOrDefault("roof", 0);
            gain += roof - r.routes().get(0).covered().getOrDefault("roof", 0);
            if (key != null && roof == 0) {
                roofless++;
                int d = sr.covered().getOrDefault(key, 0) - sb.covered().getOrDefault(key, 0);
                keyDiff += d;
                weaker += d < 0 ? 1 : 0;
            }
        }
        long failed = res.stream().filter(Objects::isNull).count();
        RouteResult demo = res.get(0);
        String keep = "off".equals(s.rain()) || key == null ? "-"
                : "%s 줄어듦 %d/%d (평균 %+.1fm)".formatted(key, weaker, roofless, roofless == 0 ? 0 : keyDiff / roofless);
        o.line("  %-15s 시연 %s | 바뀜 %2d/%2d | 추가 %6.1fm (바뀐 쌍 %6.1fm) | 지붕 이득 %5.1fm | 지하 %2d | 실패 %d | 기준 유지 %s",
                s.label(), demo == null ? "실패" : "%s 최단 %dm·안전 %dm".formatted(underground(demo) ? "지하" : "지상",
                        demo.routes().get(0).distanceM(), safe(demo).distanceM()),
                changed, ok, ok == 0 ? 0 : extra / ok, changed == 0 ? 0 : extraChanged / changed, ok == 0 ? 0 : gain / ok,
                under, failed, keep);
    }

    static boolean underground(RouteResult r) {
        return r.explanation().notes().stream().anyMatch(n -> n.kind().equals("underground"));
    }

    static RouteResult.Route safe(RouteResult r) {
        return r.routes().get(r.routes().size() - 1);
    }

    static boolean sameLine(List<double[]> a, List<double[]> b) {
        return a.size() == b.size() && IntStream.range(0, a.size()).allMatch(i -> Arrays.equals(a.get(i), b.get(i)));
    }
}
