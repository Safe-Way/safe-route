package com.saferoute.route;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.PoiSource;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 테스트용 작은 도로망과 설정.
 *
 * <pre>
 *            N (밝은 길)
 *          /   \
 *   S ---- M ---- T      S–M–T: 어두운 지름길 약 200m
 * </pre>
 * 조선대 근처 좌표다. 경도 0.0011도 ≈ 100m, 위도 0.0006도 ≈ 67m.
 */
final class TestRoutes {

    static final long S = 1, M = 2, T = 3, N = 4;
    static final double LAT = 35.14000;
    static final double[] S_P = {126.93000, LAT};
    static final double[] M_P = {126.93110, LAT};
    static final double[] T_P = {126.93220, LAT};

    static final EdgePenalty DARK = new EdgePenalty(
            Map.of(Penalty.LIGHT, 1.0, Penalty.CCTV, 1.0, Penalty.HELP, 1.0), false);

    private TestRoutes() {
    }

    /** 밝은 길 꼭짓점 N의 위도를 정해 우회 정도를 바꾼다 (0.0006이면 약 1.2배, 0.0015면 약 1.9배) */
    static RoadGraph diamond(double northLatOffset) {
        double[] nP = {126.93110, LAT + northLatOffset};
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(M, M_P);
        nodes.put(T, T_P);
        nodes.put(N, nP);
        List<RoadEdge> edges = new ArrayList<>();
        edges.add(edge(10, S, M, S_P, M_P, DARK));
        edges.add(edge(11, M, T, M_P, T_P, DARK));
        edges.add(edge(12, S, N, S_P, nP, EdgePenalty.NONE));
        // 일부러 거꾸로 저장한 길: 걷는 방향과 반대로 저장돼 있어도 선이 이어져야 한다
        edges.add(edge(13, T, N, T_P, nP, EdgePenalty.NONE));
        return new RoadGraph(nodes, edges);
    }

    /**
     * n×n 격자 도로망. 칸 간격은 spacingM(m), 노드 번호는 행×n+열, (0,0)이 남서쪽 끝.
     * dark(행, 열)이 참인 노드에 닿는 길은 어두운 길이다.
     */
    static RoadGraph grid(int n, double spacingM, java.util.function.BiPredicate<Integer, Integer> dark) {
        double dLat = spacingM / 111_195.0;
        double dLon = spacingM / (111_195.0 * Math.cos(Math.toRadians(LAT)));
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                nodes.put((long) r * n + c, new double[] {126.93 + c * dLon, LAT + r * dLat});
            }
        }
        List<RoadEdge> edges = new ArrayList<>();
        long id = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                long a = (long) r * n + c;
                if (c + 1 < n) {
                    boolean d = dark.test(r, c) || dark.test(r, c + 1);
                    edges.add(edge(id++, a, a + 1, nodes.get(a), nodes.get(a + 1), d ? DARK : EdgePenalty.NONE));
                }
                if (r + 1 < n) {
                    boolean d = dark.test(r, c) || dark.test(r + 1, c);
                    edges.add(edge(id++, a, a + n, nodes.get(a), nodes.get(a + n), d ? DARK : EdgePenalty.NONE));
                }
            }
        }
        return new RoadGraph(nodes, edges);
    }

    static RoadEdge edge(long id, long from, long to, double[] a, double[] b, EdgePenalty p) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), "footway", line, p);
    }

    /** 스프링 없이 실제 부품을 이어 만든 RouteService. 시각은 2026-10-30 hour시(서울)로 고정 */
    static RouteService service(RoadGraph graph, PoiSource pois, int hour) {
        return service(graph, pois, hour, props());
    }

    static RouteService service(RoadGraph graph, PoiSource pois, int hour, RouteProperties props) {
        return service(graph, pois, at(hour), props);
    }

    static RouteService service(RoadGraph graph, PoiSource pois, ZonedDateTime now, RouteProperties props) {
        return new RouteService(new InMemoryRoadGraphSource(graph), pois, new NightJudge(), new RouteScorer(props),
                new RouteResultAssembler(new FacilityCounter(props), props),
                props, Clock.fixed(now.toInstant(), now.getZone()));
    }

    /** 2026-10-30(금) hour시 서울 */
    static ZonedDateTime at(int hour) {
        return ZonedDateTime.of(2026, 10, 30, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    }

    /** 고정 목록 poi (공휴일 없음) */
    static PoiSource pois(List<Poi> list) {
        return new PoiSource() {
            @Override
            public List<Poi> within(BBox box, Set<String> kinds) {
                return list.stream().filter(p -> box.contains(p.lon(), p.lat()) && kinds.contains(p.kind())).toList();
            }

            @Override
            public Set<LocalDate> holidays() {
                return Set.of();
            }

            @Override
            public LocalDate dataDate() {
                return LocalDate.of(2026, 10, 28);
            }
        };
    }

    /** 시간과 상관없는 시설 (등·CCTV·편의점 등). count는 등 개수·카메라 대수 */
    static Poi plain(String kind, long id, double lon, double lat, int count) {
        return new Poi(kind, null, null, lon, lat, null, null, null, null, null, null, null, null, null, id, count);
    }

    /** 24시간 이용할 수 있는 길가 비상벨 (밤 도움 시설) */
    static Poi outdoorBell(long id, double lon, double lat) {
        return new Poi("bell", "가로변", 0, lon, lat, 0, 24 * 60, "매일", null, null, null, null, null, null, id, 1);
    }

    /**
     * src/main/resources/route.yml을 읽은 설정. overrides로 일부 값을 바꿀 수 있다.
     * 예: props("route.profiles.adult.max-detour=1.1")
     */
    static RouteProperties props(String... overrides) {
        try {
            var env = new StandardEnvironment();
            new YamlPropertySourceLoader().load("route", new ClassPathResource("route.yml"))
                    .forEach(env.getPropertySources()::addFirst);
            Map<String, Object> extra = new LinkedHashMap<>();
            for (String o : overrides) {
                String[] kv = o.split("=", 2);
                extra.put(kv[0], kv[1]);
            }
            env.getPropertySources().addFirst(new MapPropertySource("test", extra));
            return new Binder(ConfigurationPropertySources.get(env))
                    .bind("route", RouteProperties.class).get();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
