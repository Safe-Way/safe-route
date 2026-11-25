package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.PreferenceSource;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.PoiSource;
import com.saferoute.route.data.RoadGraphSource;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
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

    /** 위도 1도의 길이(m). 같은 경도에서 위도만 바꾸면 Geo.distance가 정확히 이 비율이다 */
    static final double M_PER_LAT = Geo.distance(0, 0, 0, 1);

    /** 어두운 길: 길 성질은 없고, 등이 없는 쪽이라 어둡다 (밝기는 poi로 계산) */
    static final EdgePenalty DARK = EdgePenalty.NONE;
    static final EdgePenalty STAIRS = new EdgePenalty(Map.of(Penalty.STAIRS, 1.0));
    static final EdgePenalty CURB = new EdgePenalty(Map.of(Penalty.CURB, 1.0));
    /** A파트가 그린 지하 길처럼 지붕 있고 지하 */
    static final EdgePenalty UNDER = new EdgePenalty(Map.of(Penalty.COVERED, 1.0, Penalty.UNDERGROUND, 1.0));

    /** S_P에서 동쪽 약 365m. shortcutOrAround의 도착 */
    static final double[] E_P = {126.934, LAT};

    private TestRoutes() {
    }

    /** 밝은 길 꼭짓점 N의 위도를 정해 우회 정도를 바꾼다 (0.0004면 약 1.09배, 0.0006이면 약 1.2배, 0.0015면 약 1.9배) */
    static RoadGraph diamond(double northLatOffset) {
        return diamond(northLatOffset, DARK);
    }

    /** diamond에서 지름길 첫 길 S–M(10)만 성질 shortcutPenalty */
    static RoadGraph diamond(double northLatOffset, EdgePenalty shortcutPenalty) {
        double[] nP = {126.93110, LAT + northLatOffset};
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(M, M_P);
        nodes.put(T, T_P);
        nodes.put(N, nP);
        List<RoadEdge> edges = new ArrayList<>();
        edges.add(edge(10, S, M, S_P, M_P, shortcutPenalty));
        edges.add(edge(11, M, T, M_P, T_P, DARK));
        edges.add(edge(12, S, N, S_P, nP, EdgePenalty.NONE));
        // 일부러 거꾸로 저장한 길: 걷는 방향과 반대로 저장돼 있어도 선이 이어져야 한다
        edges.add(edge(13, T, N, T_P, nP, EdgePenalty.NONE));
        return new RoadGraph(nodes, edges);
    }

    /** 성질 p인 지름길 S_P–E_P(약 365m)와, 가운데에서 북쪽 northM으로 돌아가는 보통 길 (노드 1 S_P, 2 E_P, 3 꼭짓점) */
    static RoadGraph shortcutOrAround(EdgePenalty p, double northM) {
        double[] n = north(new double[] {126.932, LAT}, northM);
        return new RoadGraph(Map.of(1L, S_P, 2L, E_P, 3L, n), List.of(edge(1, 1, 2, S_P, E_P, p),
                edge(2, 1, 3, S_P, n, EdgePenalty.NONE), edge(3, 3, 2, n, E_P, EdgePenalty.NONE)));
    }

    /**
     * S–M–T 직선 길 + 북쪽 6km에 길과 이어지지 않은 노드 99.
     * 99는 서비스 지역(노드 전체를 감싼 사각형)을 북쪽으로 넓히는 용도이고, 붙이기는 가장 큰 덩어리에만 하므로 99에는 안 붙는다
     */
    static RoadGraph lineWithFarNode() {
        return new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, 99L, north(S_P, 6000)), List.of(
                edge(1, S, M, S_P, M_P, EdgePenalty.NONE), edge(2, M, T, M_P, T_P, EdgePenalty.NONE)));
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

    /** g에 노드와 길을 더한 새 도로망 */
    static RoadGraph plus(RoadGraph g, Map<Long, double[]> nodes, RoadEdge... edges) {
        Map<Long, double[]> n = new LinkedHashMap<>(g.nodes());
        n.putAll(nodes);
        List<RoadEdge> e = new ArrayList<>(g.graph().edgeSet());
        e.addAll(List.of(edges));
        return new RoadGraph(n, e);
    }

    static RoadEdge edge(long id, long from, long to, double[] a, double[] b, EdgePenalty p) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), "footway", line, p);
    }

    /** 길 종류(highway)와 인도 태그(sidewalk)가 있는 곧은 길 */
    static RoadEdge road(long id, long from, long to, double[] a, double[] b, String highway, String sidewalk) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE, sidewalk);
    }

    /** S에서 동쪽 eastM, 북쪽 northM(m) 떨어진 점 {경도, 위도} */
    static double[] xy(double eastM, double northM) {
        return north(east(S_P, eastM), northM);
    }

    /** p에서 정북으로 m만큼 간 점 */
    static double[] north(double[] p, double m) {
        return new double[] {p[0], p[1] + m / M_PER_LAT};
    }

    /** p에서 정동으로 m만큼 간 점 */
    static double[] east(double[] p, double m) {
        return new double[] {p[0] + m / (M_PER_LAT * Math.cos(Math.toRadians(p[1]))), p[1]};
    }

    // 경곗값 검사용. 이름에 쪽(이하·미만)을 넣지 않아 <, <= 규칙 어디에나 쓴다

    /** 거리 경계 바로 아래 (m) */
    static double justBelow(double limitM) {
        return limitM - 0.01;
    }

    /** 거리 경계 바로 위 (m) */
    static double justAbove(double limitM) {
        return limitM + 0.01;
    }

    /** 안내하는 경로가 지름길(위도 LAT)에서 북쪽으로 돌아가는지 */
    static boolean north(RouteResult r) {
        return safe(r).geometry().stream().anyMatch(p -> p[1] > LAT + 0.0001);
    }

    /** 부른 결과의 오류 종류. 오류 없이 끝나면 null */
    static RouteError errorOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RouteException e) {
            return e.error();
        }
    }

    static void assertError(Runnable call, RouteError expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(expected));
    }

    static void assertNoRoute(Runnable call) {
        assertError(call, RouteError.NO_ROUTE);
    }

    /** NO_ROUTE이고 문장이 message */
    static void assertNoRoute(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RouteException.class, e -> {
            assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    /** 안내하는 경로 (응답 routes의 마지막) */
    static RouteResult.Route safe(RouteResult r) {
        return r.routes().get(r.routes().size() - 1);
    }

    /** 스프링 없이 실제 부품을 이어 만든 RouteService. 시각은 2026-10-30 hour시(서울)로 고정 */
    static RouteService service(RoadGraph graph, PoiSource pois, int hour) {
        return service(graph, pois, hour, props());
    }

    static RouteService service(RoadGraph graph, PoiSource pois, int hour, RouteProperties props) {
        return service(graph, pois, at(hour), props);
    }

    static RouteService service(RoadGraph graph, PoiSource pois, ZonedDateTime now, RouteProperties props) {
        return service(new InMemoryRoadGraphSource(graph), pois, now, props, PreferenceSource.NONE, null);
    }

    /** 도로망 출처·사람 선호·날씨까지 정하는 RouteService. weather가 null이면 날씨를 묻지 않는다 */
    static RouteService service(RoadGraphSource roads, PoiSource pois, ZonedDateTime now, RouteProperties props,
            PreferenceSource preferences, WeatherDustService weather) {
        return new RouteService(roads, pois, new NightJudge(), new RouteScorer(props),
                new RouteResultAssembler(new FacilityCounter(props), props),
                props, Clock.fixed(now.toInstant(), now.getZone()), preferences, weather);
    }

    /** 2026-10-30(금) hour시 서울 */
    static ZonedDateTime at(int hour) {
        return ZonedDateTime.of(2026, 10, 30, hour, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    }

    /** 고정 목록 poi와 진행 중인 행사·돌발상황 */
    static PoiSource pois(List<Poi> list, List<RoadEvent> events) {
        return pois(list, events, List.of());
    }

    /** 고정 목록 poi, 진행 중인 행사·돌발상황, 지금 유효한 신고 (신고는 DB에 넣지 않고 이것으로 시험한다) */
    static PoiSource pois(List<Poi> list, List<RoadEvent> events, List<RoadReport> reports) {
        PoiSource base = pois(list);
        return new PoiSource() {
            @Override
            public List<Poi> within(BBox box, Set<String> kinds) {
                return base.within(box, kinds);
            }

            @Override
            public Set<LocalDate> holidays() {
                return base.holidays();
            }

            @Override
            public LocalDate dataDate() {
                return base.dataDate();
            }

            @Override
            public List<RoadEvent> events(BBox box, java.time.LocalDateTime at) {
                return events.stream().filter(e -> box.contains(e.lon(), e.lat())).toList();
            }

            @Override
            public List<RoadReport> reports(BBox box) {
                return reports.stream().filter(r -> box.contains(r.lon(), r.lat())).toList();
            }
        };
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

    /** diamond(0.0006)의 밝은 길(S–N–T) 위에 등 6개, CCTV 2곳(3대), 비상벨 1개. 어두운 지름길에는 아무것도 없다 */
    static List<Poi> brightSideFacilities() {
        List<Poi> f = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double t = (i + 1) / 7.0;
            double lon = i < 3 ? 126.93000 + 0.0011 * t * 2 : 126.93220 - 0.0011 * (1 - t) * 2;
            double lat = LAT + 0.0006 * (i < 3 ? t * 2 : (1 - t) * 2);
            f.add(plain("light", i, lon, lat, 1));
        }
        f.add(plain("cctv", 10, 126.93110, 35.14060, 2));
        f.add(plain("cctv", 11, 126.93060, 35.14030, 1));
        f.add(outdoorBell(20, 126.93160, 35.14030));
        return f;
    }

    static final List<String> ALL_MODES = List.of("adult", "child", "senior", "wheelchair");

    /** at 자리의 신고. 피할 모드·비용만 더할 모드는 뷰가 종류로 정하는 목록과 같게 만든다 (migration 032) */
    static RoadReport report(String type, double[] at) {
        List<String> avoid = switch (type) {
            case "obstacle", "accident", "construction" -> ALL_MODES;
            case "curb", "steep", "broken" -> List.of("senior", "wheelchair");
            default -> List.of();
        };
        List<String> cost = switch (type) {
            case "curb", "steep", "broken" -> List.of("adult", "child");
            case "obstacle", "accident", "construction" -> List.of();
            default -> ALL_MODES;
        };
        return new RoadReport(3, type, at[0], at[1], LocalDate.of(2026, 11, 16), avoid, cost);
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
