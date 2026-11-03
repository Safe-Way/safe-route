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
import com.saferoute.route.data.MysqlFacilitySource;
import com.saferoute.route.data.MysqlPoiSource;
import com.saferoute.route.data.MysqlRoadGraphSource;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;

/**
 * 팀 DB(문화전당 반경 2km 도로망 + poi)로 모드·시각별 경로를 실제로 계산해 본다. 속도도 잰다.
 * 실행: ./mvnw test -Dtest=RouteLiveCheck -Dlive
 */
class RouteLiveCheck {

    record Trip(String name, double[] from, double[] to) {
    }

    static final List<Trip> TRIPS = List.of(
            new Trip("문화전당 → 조선대 정문", new double[] {126.9204, 35.1466}, new double[] {126.9306, 35.1408}),
            new Trip("충장로 → 동구청", new double[] {126.9150, 35.1490}, new double[] {126.9230, 35.1460}),
            new Trip("남광주역 → 학동", new double[] {126.9182, 35.1479}, new double[] {126.9290, 35.1320}));

    static final String[] MODES = {"adult", "child", "senior", "wheelchair"};

    @Test
    void live() throws Exception {
        assumeTrue(System.getProperty("live") != null, "-Dlive를 줄 때만 실행");
        Map<String, String> env = TestSupport.readEnv();
        JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(env.get("DB_URL"), env.get("DB_USERNAME"),
                env.get("DB_PASSWORD"), true));
        RouteProperties props = TestRoutes.props();
        MysqlFacilitySource facilities = new MysqlFacilitySource(db);
        MysqlRoadGraphSource roads = new MysqlRoadGraphSource(db);
        RouteScorer scorer = new RouteScorer(new MysqlPoiSource(db), props);
        RouteResultAssembler assembler = new RouteResultAssembler(new FacilityCounter(facilities, props), facilities, props);

        Out o = new Out("팀 DB 경로 확인 (2026-11-04 수요일)");
        for (int hour : new int[] {12, 21}) {
            ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            RouteService service = new RouteService(roads, new NightJudge(), scorer, assembler, props,
                    Clock.fixed(now.toInstant(), now.getZone()));
            o.line("");
            o.line("■ %d시", hour);
            for (Trip t : TRIPS) {
                o.line("  %s", t.name());
                for (String mode : MODES) {
                    long started = System.nanoTime();
                    try {
                        RouteResult r = service.route(new RouteRequest(new Place(t.from()[1], t.from()[0], null),
                                new Place(t.to()[1], t.to()[0], null), "auto", mode));
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
        o.print();
    }
}
