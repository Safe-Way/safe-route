package com.saferoute.route;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

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
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;

/**
 * 팀 DB(광주 전체 도로망 + poi)에서 고정 경로 3개를 모드·시각별로 실제로 계산해 본다. 임의 경로 검사는 RouteRandomCheck.
 * 비 오는 날(rain on) 시연 길도 계산한다 (2026-11-09). 외부 날씨는 부르지 않는다: 날씨 없는 엔진에 rain을 on·off로 준다.
 * 실행: ./mvnw test -Dtest=RouteLiveCheck -Dlive
 */
class RouteLiveCheck {

    record Trip(String name, double[] from, double[] to) {
    }

    static final List<Trip> TRIPS = List.of(
            new Trip("문화전당 → 조선대 정문", new double[] {126.9204, 35.1466}, new double[] {126.9306, 35.1408}),
            new Trip("충장로 → 동구청", new double[] {126.9150, 35.1490}, new double[] {126.9230, 35.1460}),
            new Trip("남광주역 → 학동", new double[] {126.9182, 35.1479}, new double[] {126.9290, 35.1320}));

    /** 비 오는 날 시연 길: 문화전당역 3번 출구(점 -3001603) → 금남로4가역 1번 출구(점 -3001501). road_node_excluded 좌표 */
    static final Trip RAIN_DEMO = new Trip("문화전당역 3번 출구 → 금남로4가역 1번 출구",
            new double[] {126.9191749, 35.147011}, new double[] {126.9150448, 35.1502497});

    static final String[] MODES = {"adult", "child", "senior", "wheelchair"};

    @Test
    void live() throws Exception {
        assumeTrue(System.getProperty("live") != null, "-Dlive를 줄 때만 실행");
        Map<String, String> env = TestSupport.readEnv();
        JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(env.get("DB_URL"), env.get("DB_USERNAME"),
                env.get("DB_PASSWORD"), true));
        RouteProperties props = TestRoutes.props();
        MysqlRoadGraphSource roads = new MysqlRoadGraphSource(db);
        MysqlPoiSource pois = new MysqlPoiSource(db);
        RouteScorer scorer = new RouteScorer(props);
        RouteResultAssembler assembler = new RouteResultAssembler(new FacilityCounter(props), props);

        Out o = new Out("팀 DB 경로 확인 (2026-11-04 수요일)");
        for (int hour : new int[] {12, 21}) {
            ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            RouteService service = new RouteService(roads, pois, new NightJudge(), scorer, assembler, props,
                    Clock.fixed(now.toInstant(), now.getZone()));
            o.line("");
            o.line("■ %d시", hour);
            for (Trip t : TRIPS) {
                o.line("  %s", t.name());
                for (String mode : MODES) {
                    long started = System.nanoTime();
                    try {
                        RouteResult r = service.route(new RouteRequest(new Place(t.from()[1], t.from()[0], null),
                                new Place(t.to()[1], t.to()[0], null), "auto", mode, null, "off"));
                        long ms = (System.nanoTime() - started) / 1_000_000;
                        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
                        RouteResult.Route shortest = r.routes().get(0);
                        o.line("    %-10s %s 최단 %dm / 안전 %dm (%dms) | %s | 데이터 %s", mode,
                                r.conditions().night() ? "밤" : "낮", shortest.distanceM(), safe.distanceM(), ms,
                                r.explanation().text(), r.dataQuality().status());
                    } catch (RouteException e) {
                        o.line("    %-10s 실패 %s", mode, e.error());
                    }
                }
            }
        }
        rainyDemo(o, roads, pois, scorer, assembler, props);
        o.print();
    }

    /** 비 오는 날 시연 길: 안전 경로가 지하(금남지하상가)로 가는지, 지붕 있는 길 길이와 알림 */
    static void rainyDemo(Out o, MysqlRoadGraphSource roads, MysqlPoiSource pois, RouteScorer scorer,
            RouteResultAssembler assembler, RouteProperties props) {
        o.line("");
        o.line("■ 비 오는 날 (rain on) %s", RAIN_DEMO.name());
        for (int hour : new int[] {12, 21}) {
            ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            RouteService service = new RouteService(roads, pois, new NightJudge(), scorer, assembler, props,
                    Clock.fixed(now.toInstant(), now.getZone()));
            o.line("  %d시", hour);
            for (String mode : MODES) {
                long started = System.nanoTime();
                try {
                    RouteResult r = service.route(new RouteRequest(new Place(RAIN_DEMO.from()[1], RAIN_DEMO.from()[0], null),
                            new Place(RAIN_DEMO.to()[1], RAIN_DEMO.to()[0], null), "auto", mode, null, "on"));
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
                    RouteResult.Route shortest = r.routes().get(0);
                    o.line("    %-10s %s 최단 %dm / 안전 %dm (%dms) | %s | covered.roof 최단 %dm·안전 %dm | 지붕 기준 %s(%s)",
                            mode, r.conditions().night() ? "밤" : "낮", shortest.distanceM(), safe.distanceM(), ms,
                            r.explanation().summary(), shortest.covered().getOrDefault("roof", 0),
                            safe.covered().getOrDefault("roof", 0), r.conditions().roof(), r.conditions().roofSource());
                    r.explanation().notes().forEach(n -> o.line("      [%s %s] %s", n.level(), n.kind(), n.text()));
                } catch (RouteException e) {
                    o.line("    %-10s 실패 %s", mode, e.error());
                }
            }
        }
    }
}
