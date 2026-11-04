package com.saferoute.route;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.saferoute.route.TestSupport.Out;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.MysqlPoiSource;
import com.saferoute.route.data.MysqlRoadGraphSource;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;
import com.saferoute.tmap.TmapUnavailableException;
import com.saferoute.tmap.TmapWalkClient;
import com.saferoute.tmap.WalkRoute;

/**
 * 발표용 비교 (FR-13·15): 출발·도착 30쌍마다 TMAP 보통 길과 우리 안전 길을 구해
 * 거리와 길 옆 시설 수(가로등·CCTV·비상벨·도움 지점)를 나란히 센다.
 * 시설은 B의 FacilityCounter로 세서, 앱 응답의 counts와 같은 기준이다.
 *
 * 실행: ./mvnw test -Dtest=TmapCompareCheck -Dlive   (IntelliJ는 VM options에 -Dlive)
 * 바꿔 보기: -Dmode=child (기본 adult), -Dhour=12 (기본 21시)
 *
 * TMAP은 하루 1,000건 중 한 번 실행에 30건을 쓴다. TMAP 약관상 결과를 24시간 넘게 저장하면 안 되므로
 * 파일·DB에 남기지 않고 화면에만 찍는다. 발표에는 맨 아래 합계·평균만 옮겨 쓴다.
 * 출발·도착은 사용자 위치가 아니라 문화전당 주변에서 정해진 규칙으로 만든 점이다.
 */
class TmapCompareCheck {

    /** 팀 DB 도로망 중심 (국립아시아문화전당). 도로망은 반경 2km */
    static final double CENTER_LON = 126.9204;
    static final double CENTER_LAT = 35.1466;
    static final int PAIRS = 30;

    record Pair(int no, double[] from, double[] to) {
    }

    record Row(Pair pair, int tmapM, int safeM, Map<FacilityGroup, Integer> tmap, Map<FacilityGroup, Integer> safe) {
    }

    /**
     * 같은 30쌍이 매번 나오도록 씨앗을 고정해 점을 뽑는다.
     * 두 점 모두 중심에서 1.5km 안, 두 점 사이 직선거리 500m~1.5km.
     */
    static List<Pair> pairs() {
        Random r = new Random(20261103);
        List<Pair> out = new ArrayList<>();
        while (out.size() < PAIRS) {
            double[] a = randomPoint(r);
            double[] b = randomPoint(r);
            double d = Geo.distance(a, b);
            if (d >= 500 && d <= 1500) {
                out.add(new Pair(out.size() + 1, a, b));
            }
        }
        return out;
    }

    private static double[] randomPoint(Random r) {
        double distM = 1500 * Math.sqrt(r.nextDouble());
        double angle = 2 * Math.PI * r.nextDouble();
        double dLat = distM * Math.cos(angle) / 111_195.0;
        double dLon = distM * Math.sin(angle) / (111_195.0 * Math.cos(Math.toRadians(CENTER_LAT)));
        return new double[] {round6(CENTER_LON + dLon), round6(CENTER_LAT + dLat)};
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    @Test
    void compare() throws Exception {
        assumeTrue(System.getProperty("live") != null, "-Dlive를 줄 때만 실행 (TMAP 30건을 씀)");
        String mode = System.getProperty("mode", "adult");
        int hour = Integer.parseInt(System.getProperty("hour", "21"));

        Map<String, String> env = TestSupport.readEnv();
        JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(env.get("DB_URL"), env.get("DB_USERNAME"),
                env.get("DB_PASSWORD"), true));
        RouteProperties props = TestRoutes.props();
        MysqlPoiSource pois = new MysqlPoiSource(db);
        FacilityCounter counter = new FacilityCounter(props);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).withHour(hour).withMinute(0);
        RouteService service = new RouteService(new MysqlRoadGraphSource(db), pois, new NightJudge(),
                new RouteScorer(props), new RouteResultAssembler(counter, props), props,
                Clock.fixed(now.toInstant(), now.getZone()));
        TmapWalkClient tmap = new TmapWalkClient(env.getOrDefault("TMAP_APP_KEY", ""));

        Out o = new Out("TMAP 보통 길 vs 우리 안전 길 (" + mode + ", " + hour + "시)");
        o.line("번호,출발(경도 위도),도착(경도 위도),TMAP m,안전 m,거리 배율,"
                + "가로등 TMAP,가로등 안전,CCTV TMAP,CCTV 안전,비상벨 TMAP,비상벨 안전,도움 TMAP,도움 안전");
        List<Row> rows = new ArrayList<>();
        int failed = 0;
        for (Pair p : pairs()) {
            try {
                RouteResult ours = service.route(new RouteRequest(new Place(p.from()[1], p.from()[0], null),
                        new Place(p.to()[1], p.to()[0], null), "auto", mode));
                RouteResult.Route safe = ours.routes().get(ours.routes().size() - 1);
                WalkRoute normal = tmap.walk(p.from()[1], p.from()[0], p.to()[1], p.to()[0]);
                List<double[]> tmapLine = normal.path().stream().map(c -> new double[] {c.lon(), c.lat()}).toList();

                // 두 길을 모두 덮는 범위의 시설을 한 번 읽어 같은 기준으로 센다
                List<double[]> both = new ArrayList<>(tmapLine);
                both.addAll(safe.geometry());
                List<Poi> near = pois.within(BBox.around(both, 200), RouteService.POI_KINDS);
                List<Counts> c = counter.countAlong(List.of(tmapLine, safe.geometry()), near);

                Row row = new Row(p, normal.distanceM(), safe.distanceM(), c.get(0).byGroup(), c.get(1).byGroup());
                rows.add(row);
                o.line(csv(row));
            } catch (RouteException e) {
                failed++;
                o.line("%d,우리 길 실패 %s", p.no(), e.error());
            } catch (TmapUnavailableException e) {
                failed++;
                o.line("%d,TMAP 실패 %s", p.no(), e.getMessage());
            }
            Thread.sleep(300); // TMAP에 한꺼번에 몰리지 않게
        }
        summary(o, rows, failed);
        o.print();
    }

    static String csv(Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.pair().no()).append(',')
                .append(r.pair().from()[0]).append(' ').append(r.pair().from()[1]).append(',')
                .append(r.pair().to()[0]).append(' ').append(r.pair().to()[1]).append(',')
                .append(r.tmapM()).append(',').append(r.safeM()).append(',')
                .append("%.2f".formatted((double) r.safeM() / r.tmapM()));
        for (FacilityGroup g : FacilityGroup.values()) {
            sb.append(',').append(r.tmap().getOrDefault(g, 0)).append(',').append(r.safe().getOrDefault(g, 0));
        }
        return sb.toString();
    }

    /** 발표에 옮겨 쓸 숫자: 평균 거리, 몇 % 더 걸었나, 시설이 몇 개 더 많았나, 몇 쌍에서 더 많았나 */
    static void summary(Out o, List<Row> rows, int failed) {
        o.line("");
        o.line("■ 합계 (성공 %d쌍, 실패 %d쌍)", rows.size(), failed);
        if (rows.isEmpty()) {
            return;
        }
        double tmapM = rows.stream().mapToInt(Row::tmapM).average().orElse(0);
        double safeM = rows.stream().mapToInt(Row::safeM).average().orElse(0);
        o.line("  평균 거리: TMAP %.0fm, 안전 %.0fm (%+.1f%%)", tmapM, safeM, (safeM / tmapM - 1) * 100);
        for (FacilityGroup g : FacilityGroup.values()) {
            double t = rows.stream().mapToInt(r -> r.tmap().getOrDefault(g, 0)).average().orElse(0);
            double s = rows.stream().mapToInt(r -> r.safe().getOrDefault(g, 0)).average().orElse(0);
            long more = rows.stream().filter(r -> r.safe().getOrDefault(g, 0) > r.tmap().getOrDefault(g, 0)).count();
            long less = rows.stream().filter(r -> r.safe().getOrDefault(g, 0) < r.tmap().getOrDefault(g, 0)).count();
            o.line("  %s: 평균 TMAP %.1f → 안전 %.1f (%+.1f) | 더 많음 %d쌍, 같음 %d쌍, 더 적음 %d쌍",
                    g.key(), t, s, s - t, more, rows.size() - more - less, less);
        }
    }
}
