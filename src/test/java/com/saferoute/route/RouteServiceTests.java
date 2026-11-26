package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.PoiSource;
import com.saferoute.route.data.PreferenceSource;
import com.saferoute.route.data.RoadGraphSource;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/** 요청 → 8.3 응답 모양: 낮·밤 기본 흐름, 두 경로와 차이 설명, 넓혀 다시 찾기, 건너는 자리 (DB 없이 가짜 도로망·시설로) */
@DisplayName("경로 요청 기본 흐름")
class RouteServiceTests {

    static PoiSource facilities(List<Poi> list) {
        return TestRoutes.pois(list);
    }

    static RouteService service(PoiSource pois, int hour) {
        return TestRoutes.service(diamond(0.0006), pois, hour);
    }

    static RouteRequest request(String night) {
        return new RouteRequest(new Place(LAT, 126.93000, "출발"), new Place(LAT, 126.93220, "도착"), night);
    }

    /** 밤 어른, 밝은 북쪽 길(약 1.2배)과 어두운 지름길 */
    static RouteResult night() {
        return service(facilities(brightSideFacilities()), 21).route(request("auto"));
    }

    @Test
    @DisplayName("밤에는 최단·안전 두 경로를 주고 안전 경로는 우회 한도 안이다")
    void nightGivesShortestAndSafeRoutesWithinTheDetourLimit() {
        // 선은 요청 출발점에서 [경도, 위도]로 시작한다 (route.yml adult.max-detour)
        RouteResult r = night();

        assertThat(r.conditions().night()).isTrue();
        assertThat(r.conditions().nightSource()).isEqualTo("auto");
        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);
        assertThat(safe.distanceM()).isGreaterThan(shortest.distanceM());
        assertThat(safe.distanceM()).isLessThanOrEqualTo((int) Math.ceil(shortest.distanceM() * 1.3));
        assertThat(shortest.durationMin()).isEqualTo(Math.round(shortest.distanceM() / 80f));
        // 경로 선은 [경도, 위도]이고 요청한 출발점에서 시작한다
        assertThat(safe.geometry().get(0)).containsExactly(126.93000, LAT);
    }

    @Test
    @DisplayName("두 경로 거리에는 출발·도착점에서 길까지 붙인 거리도 더한다")
    void bothRouteDistancesIncludeTheWalkToTheRoad() {
        // RouteResultAssembler.assemble
        // 마름모 도로망 아래 지도 범위를 넓히는 점 99를 두고, 출발·도착을 S·T에서 남쪽 10m로 옮기면 두 경로 모두 20m 늘어난다
        RouteService s = TestRoutes.service(TestRoutes.plus(diamond(0.0006), Map.of(99L, north(S_P, -100))),
                facilities(brightSideFacilities()), 21);
        double lat = LAT - 10 / M_PER_LAT;
        RouteResult on = s.route(request("auto"));
        RouteResult off = s.route(new RouteRequest(new Place(lat, 126.93000, "출발"), new Place(lat, 126.93220, "도착"), "auto"));

        assertThat(off.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        for (int i = 0; i < 2; i++) {
            assertThat(off.routes().get(i).distanceM() - on.routes().get(i).distanceM()).isBetween(19, 21);
        }
        assertThat(off.explanation().extraM()).isEqualTo(on.explanation().extraM());
    }

    @Test
    @DisplayName("경사로 값이 같은 계단이 이어지면 한 구간으로 묶고 바뀌거나 끊기면 나눈다")
    void stairsJoinWhileTheRampValueStaysTheSame() {
        // RouteResultAssembler.stairs
        // 동쪽 50m 길 다섯: 계단(경사로 있음) 둘, 계단(없음), 보행로, 계단(모름)
        Map<Long, double[]> n = new HashMap<>();
        for (long i = 0; i <= 5; i++) {
            n.put(i, xy(50 * i, 0));
        }
        List<EdgePenalty> kinds = List.of(new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), true),
                new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), true), new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), false),
                EdgePenalty.NONE, STAIRS);
        List<RoadEdge> edges = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            edges.add(edge(i + 1, i, i + 1, n.get((long) i), n.get((long) i + 1), kinds.get(i)));
        }
        double[] to = n.get(5L);
        RouteResult r = TestRoutes.service(new RoadGraph(n, edges), TestRoutes.pois(List.of()), 12).route(new RouteRequest(
                new Place(S_P[1], S_P[0], null), new Place(to[1], to[0], null), "off", "adult"));

        assertThat(r.routes().get(0).stairs()).containsExactly(new RouteResult.Stair(0, 100, true),
                new RouteResult.Stair(100, 150, false), new RouteResult.Stair(200, 250, null));
    }

    @Test
    @DisplayName("밤 설명은 지나는 시설 수 차이와 더 걷는 거리로 말한다")
    void nightExplainsTheFacilityDifference() {
        // FR-06
        RouteResult r = night();
        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);

        assertThat(safe.counts()).containsEntry("light", 6).containsEntry("cctv", 3).containsEntry("bell", 1);
        // 출발·도착 가까이의 등 2개, CCTV 1곳, 비상벨은 지름길에서도 반경 안이다
        assertThat(shortest.counts()).containsEntry("light", 2).containsEntry("cctv", 1).containsEntry("bell", 1);
        assertThat(r.explanation().extraM()).isEqualTo(safe.distanceM() - shortest.distanceM());
        assertThat(r.explanation().diff()).containsEntry("light", 4).containsEntry("cctv", 2).containsEntry("bell", 0);
        assertThat(r.explanation().text())
                .isEqualTo("%dm 더 돌지만 가로등 +4개, CCTV +2대".formatted(r.explanation().extraM()));
        assertThat(r.explanation().extraMin()).isZero(); // 200m·240m 모두 3분
    }

    @Test
    @DisplayName("지도 시설마다 지나는 경로에서의 자리와 왼쪽·오른쪽을 준다")
    void facilityMarksSayWhereOnEachRouteTheyAre() {
        // 시설마다 지나는 경로에서의 자리 (FR-30): on에 든 경로만 있고, 경로 길이 안
        RouteResult r = night();
        RouteResult.Route safe = r.routes().get(1);

        assertThat(r.facilities()).isNotEmpty().allMatch(m -> !m.on().equals("shortest"));
        assertThat(r.facilities()).allSatisfy(m -> {
            assertThat(m.along().keySet()).isEqualTo(m.on().equals("both") ? Set.of("shortest", "safe") : Set.of(m.on()));
            assertThat(m.along().get("safe").atM()).isBetween(0, safe.distanceM());
            assertThat(m.along().get("safe").side()).isIn("left", "right");
        });
        // 비상벨(126.93160, 35.14030)은 북쪽으로 돌아가는 안전 경로의 동쪽 끝 근처에 있다
        RouteResult.FacilityMark bell = r.facilities().stream().filter(m -> m.type().equals("EMERGENCY_BELL")).findFirst()
                .orElseThrow();
        assertThat(bell.along().get("safe").atM()).isGreaterThan(safe.distanceM() / 2);
        // 시계 방향으로 S–N–T를 도는 안전 경로에서 마름모 안쪽(남쪽)은 오른쪽
        assertThat(bell.along().get("safe").side()).isEqualTo("right");
        // 세부 종류가 가로등이 아닌 등은 보안등
        assertThat(r.facilities()).filteredOn(m -> m.type().endsWith("LIGHT")).isNotEmpty()
                .allSatisfy(m -> assertThat(m.type()).isEqualTo("SECURITY_LIGHT"));
    }

    @Test
    @DisplayName("밤에 등이 적은 지름길은 안전 경로보다 어두운 구간이 길다")
    void unlitShortcutHasLongerDarkStretches() {
        // 밤: 등이 양 끝에만 있는 최단(곧은 길)은 가운데가 어둡고, 등이 줄지은 안전 경로는 어두운 구간이 짧다 (FR-31)
        RouteResult r = night();
        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);

        assertThat(shortest.dark()).isNotEmpty().allSatisfy(d -> {
            assertThat(d.toM() - d.fromM()).isGreaterThanOrEqualTo(50);
            assertThat(d.fromM()).isGreaterThanOrEqualTo(0);
            assertThat(d.toM()).isLessThanOrEqualTo(shortest.distanceM());
        });
        assertThat(safe.dark().stream().mapToInt(d -> d.toM() - d.fromM()).sum())
                .isLessThan(shortest.dark().stream().mapToInt(d -> d.toM() - d.fromM()).sum());
    }

    @Test
    @DisplayName("꺾는 곳은 경로 안에 있고 45도보다 크게 꺾이며 방향은 왼쪽·오른쪽이다")
    void turnsAreInsideTheRouteAndSharperThan45Degrees() {
        // 꺾는 곳은 경로 길이 안에 있고 방향은 left·right (FR-34)
        assertThat(night().routes()).allSatisfy(rt -> assertThat(rt.turns()).allSatisfy(t -> {
            assertThat(t.atM()).isBetween(0, rt.distanceM());
            assertThat(t.direction()).isIn("left", "right");
            assertThat(t.angle()).isGreaterThan(45);
        }));
    }

    @Test
    @DisplayName("어른 낮은 가장 짧은 길 하나만 주고 낮이라 말하며 어두운 구간을 알리지 않는다")
    void dayGivesShortestRouteAndSaysItIsDay() {
        RouteResult r = service(facilities(brightSideFacilities()), 12).route(request("auto"));

        assertThat(r.conditions().night()).isFalse();
        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("safe");
        assertThat(r.explanation().text()).isEqualTo("지금은 밤이 아니라 가장 짧은 길을 안내합니다");
        assertThat(r.explanation().extraM()).isZero();
        // 낮에는 어두운 구간을 알리지 않는다
        assertThat(r.routes().get(0).dark()).isEmpty();
    }

    @Test
    @DisplayName("night=on이면 낮에도 밤 기준으로 찾는다")
    void nightOnWorksInDaytimeForDemo() {
        // nightSource manual
        RouteResult r = service(facilities(brightSideFacilities()), 12).route(request("on"));

        assertThat(r.conditions().night()).isTrue();
        assertThat(r.conditions().nightSource()).isEqualTo("manual");
        assertThat(r.routes()).hasSize(2);
    }

    @Test
    @DisplayName("night=off면 밤에도 가장 짧은 길 하나를 주고 기준을 껐다고 말한다")
    void nightOffByUserSaysSo() {
        RouteResult r = service(facilities(brightSideFacilities()), 21).route(request("off"));

        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().text()).isEqualTo("밤 기준을 꺼서 가장 짧은 길을 안내합니다");
    }

    @Test
    @DisplayName("응답 JSON은 요구사항 8.3 API 모양으로 나간다")
    void responseSerializesToApiShape() {
        // kind·geometry·counts·dataQuality 등
        RouteResult r = service(facilities(brightSideFacilities()), 21).route(request(null));

        String json = JsonMapper.builder().build().writeValueAsString(r);

        assertThat(json).contains("\"kind\":\"shortest\"", "\"geometry\":[[126.93", "\"counts\":{\"light\":",
                "\"dataQuality\":{\"status\":\"OK\"", "\"on\":\"safe\"");
    }

    @Test
    @DisplayName("데이터 품질은 늘 OK이고 기준일만 준다")
    void dataQualityIsAlwaysOkWithDataDate() {
        // 데이터 부족 알림은 없앴다 (2026-11-06). 기준일만 준다
        RouteResult r = service(facilities(List.of()), 21).route(request("auto"));

        assertThat(r.dataQuality().status()).isEqualTo("OK");
        assertThat(r.dataQuality().message()).isNull();
        assertThat(r.dataQuality().dataDate()).isEqualTo("2026-10-28");
    }

    @Test
    @DisplayName("밝은 길이 한도를 넘으면 가장 짧은 길로 가고 너무 돌아서라 말한다")
    void cappedDetourGivesShortestAndSaysSo() {
        // 밝은 길이 약 1.2배인데 한도가 1.1 → 한도 안에서는 밝은 길을 고를 수 없다
        RouteService s = TestRoutes.service(diamond(0.0006), facilities(brightSideFacilities()), 21,
                props("route.profiles.adult.max-detour=1.1"));

        RouteResult r = s.route(request("auto"));

        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("safe");
        assertThat(r.routes().get(0).distanceM()).isEqualTo(200);
        assertThat(r.explanation().text()).isEqualTo("더 안전한 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다");
    }

    @Test
    @DisplayName("설명의 더 걷는 분은 두 경로 시간 차와 늘 같다")
    void extraMinutesAlwaysMatchDurationDifference() {
        // 여러 우회 정도에서 카드의 "(n분) 더"와 두 경로 시간 차가 같아야 한다.
        // 시설이 없으면 안전 경로가 최단과 같아 길이 1개만 나오므로, 밝은 쪽 시설을 두고 실제로 2개 나온 경우를 센다
        int checked = 0;
        for (double north : new double[] {0.0003, 0.0004, 0.0005, 0.0006, 0.0007}) {
            RouteResult r = TestRoutes.service(diamond(north), facilities(brightSideFacilities()), 21).route(request("on"));
            if (r.routes().size() == 2) {
                assertThat(r.explanation().extraMin()).as("north " + north)
                        .isEqualTo(r.routes().get(1).durationMin() - r.routes().get(0).durationMin());
                checked++;
            }
        }
        assertThat(checked).as("두 경로가 나온 경우").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("응답 일몰 시각은 HH:mm 형식이다")
    void sunsetIsHourMinute() {
        assertThat(service(facilities(List.of()), 21).route(request("auto")).conditions().sunset()).matches("\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("1만 노드 격자에서 밤 길찾기가 2초 안에 끝난다")
    void gridEndToEndWithinTwoSeconds() {
        // 100×100 격자(간격 30m, 약 3km×3km, 1만 노드)
        // 시간 시험이라 컴퓨터가 몹시 바쁘면 드물게 실패할 수 있음
        Random rnd = new Random(3);
        RoadGraph g = grid(100, 30, (r, c) -> rnd.nextDouble() < 0.3);
        double[] from = g.point(0);
        double[] to = g.point(70L * 100 + 70);
        RouteService s = TestRoutes.service(g, facilities(List.of()), 21);

        long started = System.nanoTime();
        RouteResult r = s.route(new RouteRequest(new Place(from[1], from[0], null), new Place(to[1], to[0], null), "on"));
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(r.routes()).isNotEmpty();
        assertThat(ms).isLessThan(2000);
    }

    /** 범위 안 노드와, 두 끝이 모두 범위 안인 길만 주는 도로망 (DB가 사각형으로 읽는 것과 같게) */
    static RoadGraphSource clipped(RoadGraph all) {
        BBox area = BBox.around(all.nodes().values().stream().toList(), 0);
        return new RoadGraphSource() {
            @Override
            public boolean covers(double lon, double lat) {
                return area.contains(lon, lat);
            }

            @Override
            public RoadGraph load(BBox box) {
                Map<Long, double[]> nodes = new HashMap<>();
                all.nodes().forEach((id, p) -> {
                    if (box.contains(p[0], p[1])) {
                        nodes.put(id, p);
                    }
                });
                List<RoadEdge> edges = all.graph().edgeSet().stream()
                        .filter(e -> nodes.containsKey(e.from()) && nodes.containsKey(e.to())).toList();
                return new RoadGraph(nodes, edges);
            }
        };
    }

    @Test
    @DisplayName("읽은 범위에서 길이 안 이어지면 범위를 넓혀 다시 찾는다")
    void retriesWithWiderAreaWhenTheOnlyWayGoesAround() {
        // A와 B 사이에 바로 가는 길이 없고, 북쪽 1.5km 다리로 돌아가야 한다 (읽는 범위 500m 밖)
        double[] a = {126.93, LAT};
        double[] b = {126.934, LAT};
        double[] an = north(a, 1500);
        double[] bn = north(b, 1500);
        RoadGraph g = new RoadGraph(Map.of(1L, a, 2L, an, 3L, bn, 4L, b), List.of(
                edge(1, 1, 2, a, an, EdgePenalty.NONE), edge(2, 2, 3, an, bn, EdgePenalty.NONE),
                edge(3, 3, 4, bn, b, EdgePenalty.NONE)));
        RouteService s = TestRoutes.service(clipped(g), facilities(List.of()), at(12), props(), PreferenceSource.NONE, null);

        RouteResult r = s.route(new RouteRequest(new Place(a[1], a[0], null), new Place(b[1], b[0], null), "auto", "adult"));

        assertThat(r.routes().get(0).distanceM()).isBetween(3300, 3400);
    }

    @Test
    @DisplayName("출발을 길에 붙여도 건너는 자리는 선 위 건너는 노드 번호를 가리킨다")
    void crossAtPointsAtTheCrossingNodeInGeometryEvenWithSnappedStart() {
        // 서–가운데–동 큰 길 한 줄. 인도가 왼쪽에서 오른쪽으로 바뀌는 가운데 노드에서 건넌다 (specs/014)
        // 서쪽 끝은 북쪽으로 20m 올려 둔다: 도로망 범위(노드 상자) 안에 길 밖 출발을 둘 자리
        double[] w = north(new double[] {126.93000, LAT}, 20);
        double[] o = {126.93110, LAT};
        double[] e = {126.93220, LAT};
        RoadGraph g = new RoadGraph(Map.of(1L, w, 2L, o, 3L, e), List.of(
                road(1, 1, 2, w, o, "primary", "left"), road(2, 2, 3, o, e, "primary", "right")));
        double[] cw = {126.93118, LAT};
        RoadGraphSource roads = new InMemoryRoadGraphSource(g) {
            @Override
            public List<double[]> crosswalkPoints(BBox box) {
                return List.of(cw);
            }
        };
        RouteService s = TestRoutes.service(roads, facilities(List.of()), at(12), props(), PreferenceSource.NONE, null);
        // 출발은 서쪽 길 한가운데에서 북쪽으로 5m: 응답 첫 점은 요청 출발, 길 위 발이 geometry 1번
        double[] start = north(new double[] {126.93055, LAT}, 15);

        RouteResult r = s.route(new RouteRequest(new Place(start[1], start[0], "출발"), new Place(LAT, e[0], "도착"), "auto"));

        RouteResult.Route safe = safe(r);
        assertThat(safe.crossAt()).singleElement().satisfies(c -> {
            assertThat(Geo.distance(safe.geometry().get(c.at()), o)).isLessThan(0.01);
            assertThat(c.at()).isEqualTo(2);
            assertThat(c.lon()).isEqualTo(cw[0]);
            assertThat(c.kind()).isEqualTo("crossing");
        });
        assertThat(safe.crossings()).isEmpty();
        String json = JsonMapper.builder().build().writeValueAsString(safe);
        assertThat(json).contains("\"crossAt\":[{\"at\":2,\"lat\":" + LAT + ",\"lon\":" + cw[0]
                + ",\"kind\":\"crossing\",\"from\":null,\"to\":null}]");
    }

    @Test
    @DisplayName("횡단보도·어두운 구간은 그린 방향과 상관없이 걷는 방향으로 잰다")
    void crosswalkStretchAndDarkStretchAreMeasuredInWalkingOrder() {
        // 요청 출발점부터 (FR-31, FR-34)
        // S → M 100m 보통 길(M→S로 그림), M → T 100m 횡단보도. 등이 없어 밤에는 전부 어둡다
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P), List.of(
                edge(1, M, S, M_P, S_P, EdgePenalty.NONE),
                edge(2, M, T, M_P, T_P, new EdgePenalty(Map.of(com.saferoute.route.engine.graph.Penalty.CROSSWALK, 1.0)))));
        RouteResult r = TestRoutes.service(g, facilities(List.of()), 21).route(
                new RouteRequest(new Place(LAT, S_P[0], null), new Place(LAT, T_P[0], null), "auto"));

        RouteResult.Route route = safe(r);
        double sm = Geo.distance(S_P, M_P);
        assertThat(route.crossings()).singleElement().satisfies(c -> {
            assertThat(c.fromM()).isEqualTo((int) Math.round(sm));
            assertThat(c.toM()).isEqualTo(route.distanceM());
        });
        assertThat(route.dark()).singleElement().satisfies(d -> {
            assertThat(d.fromM()).isZero();
            assertThat(d.toM()).isCloseTo(route.distanceM(), org.assertj.core.data.Offset.offset(1));
        });
    }

    @Test
    @DisplayName("횡단보도 없이 큰 길을 건너면 한 번만 주의를 알린다")
    void crossingBigRoadBetweenSidewalksWithoutCrosswalkWarnsOnce() {
        // 남쪽 인도 → 골목 → 큰 길(both) → 골목 → 북쪽 인도, 횡단보도 없음 (w20)
        List<double[]> pts = List.of(xy(-100, -8), xy(-50, -8), xy(-50, 0), xy(50, 0), xy(50, 8), xy(150, 8));
        String[] kinds = {"footway", "residential", "primary", "residential", "footway"};
        Map<Long, double[]> nodes = new HashMap<>();
        List<RoadEdge> edges = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            nodes.put((long) i, pts.get(i));
            nodes.put((long) i + 1, pts.get(i + 1));
            edges.add(road(i, i, i + 1, pts.get(i), pts.get(i + 1), kinds[i], null));
        }
        RouteService s = TestRoutes.service(new RoadGraph(nodes, edges), facilities(List.of()), 12);
        double[] a = pts.get(0);
        double[] b = pts.get(pts.size() - 1);

        RouteResult r = s.route(new RouteRequest(new Place(a[1], a[0], "출발"), new Place(b[1], b[0], "도착"), "auto"));

        assertThat(safe(r).crossAt()).isEmpty();
        assertThat(r.explanation().notes()).containsOnlyOnce(RouteResult.Note.warn("crossing", "횡단보도 없이 길을 건너요"));
    }
}
