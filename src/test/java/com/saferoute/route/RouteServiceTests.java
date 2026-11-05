package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;


import tools.jackson.databind.json.JsonMapper;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.PoiSource;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/** 요청 → 8.3 응답 모양, 오류 코드 (DB 없이 가짜 도로망·시설로) */
class RouteServiceTests {

    /** 밝은 길(S–N–T) 위에 등 6개, CCTV 2곳(3대), 비상벨 1개. 어두운 지름길에는 아무것도 없다 */
    static List<Poi> brightSideFacilities() {
        List<Poi> f = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double t = (i + 1) / 7.0;
            double lon = i < 3 ? 126.93000 + 0.0011 * t * 2 : 126.93220 - 0.0011 * (1 - t) * 2;
            double lat = LAT + 0.0006 * (i < 3 ? t * 2 : (1 - t) * 2);
            f.add(TestRoutes.plain("light", i, lon, lat, 1));
        }
        f.add(TestRoutes.plain("cctv", 10, 126.93110, 35.14060, 2));
        f.add(TestRoutes.plain("cctv", 11, 126.93060, 35.14030, 1));
        f.add(TestRoutes.outdoorBell(20, 126.93160, 35.14030));
        return f;
    }

    static PoiSource facilities(List<Poi> list) {
        return TestRoutes.pois(list);
    }

    static RouteService service(PoiSource pois, int hour) {
        return TestRoutes.service(diamond(0.0006), pois, hour);
    }

    static RouteRequest request(String night) {
        return new RouteRequest(new Place(LAT, 126.93000, "출발"), new Place(LAT, 126.93220, "도착"), night);
    }

    @Test
    void nightGivesTwoRoutesAndExplainsTheDifference() {
        RouteResult r = service(facilities(brightSideFacilities()), 21).route(request("auto"));

        assertThat(r.conditions().night()).isTrue();
        assertThat(r.conditions().nightSource()).isEqualTo("auto");
        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");

        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);
        assertThat(safe.distanceM()).isGreaterThan(shortest.distanceM());
        assertThat(safe.distanceM()).isLessThanOrEqualTo((int) Math.ceil(shortest.distanceM() * 1.3));
        assertThat(shortest.durationMin()).isEqualTo(Math.round(shortest.distanceM() / 80f));
        assertThat(safe.counts()).containsEntry("light", 6).containsEntry("cctv", 3).containsEntry("bell", 1);
        // 출발·도착 가까이의 등 2개, CCTV 1곳, 비상벨은 지름길에서도 반경 안이다
        assertThat(shortest.counts()).containsEntry("light", 2).containsEntry("cctv", 1).containsEntry("bell", 1);

        assertThat(r.explanation().extraM()).isEqualTo(safe.distanceM() - shortest.distanceM());
        assertThat(r.explanation().diff()).containsEntry("light", 4).containsEntry("cctv", 2).containsEntry("bell", 0);
        assertThat(r.explanation().text())
                .isEqualTo("%dm 더 돌지만 가로등 +4개, CCTV +2대".formatted(r.explanation().extraM()));
        assertThat(r.explanation().extraMin()).isZero(); // 200m·240m 모두 3분

        assertThat(r.facilities()).isNotEmpty().allMatch(m -> !m.on().equals("shortest"));
        // 시설마다 지나는 경로에서의 자리 (FR-30): on에 든 경로만 있고, 경로 길이 안
        assertThat(r.facilities()).allSatisfy(m -> {
            assertThat(m.along().keySet()).isEqualTo(m.on().equals("both") ? java.util.Set.of("shortest", "safe")
                    : java.util.Set.of(m.on()));
            assertThat(m.along().get("safe").atM()).isBetween(0, safe.distanceM());
            assertThat(m.along().get("safe").side()).isIn("left", "right");
        });
        // 밤: 등이 양 끝에만 있는 최단(곧은 길)은 가운데가 어둡고, 등이 줄지은 안전 경로는 어두운 구간이 짧다 (FR-31)
        assertThat(shortest.dark()).isNotEmpty().allSatisfy(d -> {
            assertThat(d.toM() - d.fromM()).isGreaterThanOrEqualTo(50);
            assertThat(d.fromM()).isGreaterThanOrEqualTo(0);
            assertThat(d.toM()).isLessThanOrEqualTo(shortest.distanceM());
        });
        assertThat(safe.dark().stream().mapToInt(d -> d.toM() - d.fromM()).sum())
                .isLessThan(shortest.dark().stream().mapToInt(d -> d.toM() - d.fromM()).sum());
        // 비상벨(126.93160, 35.14030)은 북쪽으로 돌아가는 안전 경로의 동쪽 끝 근처, 경로 아래(오른쪽)에 있다
        RouteResult.Along bell = r.facilities().get(0).along().get("safe");
        assertThat(bell.atM()).isGreaterThan(safe.distanceM() / 2);
        // 아이콘은 비상벨·CCTV가 등보다 앞에 온다
        assertThat(r.facilities().get(0).type()).isEqualTo("EMERGENCY_BELL");
        assertThat(r.dataQuality().status()).isEqualTo("OK");
        assertThat(r.dataQuality().dataDate()).isEqualTo("2026-10-28");
        // 경로 선은 [경도, 위도]이고 요청한 출발점에서 시작한다
        assertThat(safe.geometry().get(0)).containsExactly(126.93000, LAT);
    }

    @Test
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
    void nightOnWorksInDaytimeForDemo() {
        RouteResult r = service(facilities(brightSideFacilities()), 12).route(request("on"));

        assertThat(r.conditions().night()).isTrue();
        assertThat(r.conditions().nightSource()).isEqualTo("manual");
        assertThat(r.routes()).hasSize(2);
    }

    @Test
    void nightOffByUserSaysSo() {
        RouteResult r = service(facilities(brightSideFacilities()), 21).route(request("off"));

        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().text()).isEqualTo("밤 기준을 꺼서 가장 짧은 길을 안내합니다");
    }

    @Test
    void responseSerializesToApiShape() {
        RouteResult r = service(facilities(brightSideFacilities()), 21).route(request(null));

        String json = JsonMapper.builder().build().writeValueAsString(r);
        System.out.println(json);

        assertThat(json).contains("\"kind\":\"shortest\"", "\"geometry\":[[126.93", "\"counts\":{\"light\":",
                "\"dataQuality\":{\"status\":\"OK\"", "\"on\":\"safe\"");
    }

    @Test
    void errorCodes() {
        RouteService s = service(facilities(List.of()), 21);

        assertError(() -> s.route(new RouteRequest(new Place(LAT, 126.93000, null), new Place(LAT, 126.93020, null), null)),
                RouteError.SAME_POINT);
        assertError(() -> s.route(new RouteRequest(new Place(LAT, 126.93000, null), new Place(35.20, 126.93000, null), null)),
                RouteError.TOO_FAR);
        // 도로망을 넣은 범위(조선대 근처 작은 사각형) 밖
        assertError(() -> s.route(new RouteRequest(new Place(LAT, 126.93000, null), new Place(35.15, 126.94, null), null)),
                RouteError.OUT_OF_AREA);

        PoiSource broken = new PoiSource() {
            @Override
            public List<Poi> within(BBox box, Set<String> kinds) {
                throw new DataAccessResourceFailureException("DB 꺼짐");
            }

            @Override
            public Set<LocalDate> holidays() {
                return Set.of();
            }

            @Override
            public LocalDate dataDate() {
                return null;
            }
        };
        assertError(() -> service(broken, 21).route(request("auto")), RouteError.DB_UNAVAILABLE);
    }

    @Test
    void dataGapIsReportedNotCalledSafe() {
        EdgePenalty gap = new EdgePenalty(Map.of(), true);
        RoadGraph graph = new RoadGraph(Map.of(S, S_P, T, T_P), List.of(edge(1, S, T, S_P, T_P, gap)));
        RouteService s = TestRoutes.service(graph, facilities(List.of()), 21);

        RouteResult r = s.route(request("auto"));

        assertThat(r.dataQuality().status()).isEqualTo("NO_DATA");
        assertThat(r.dataQuality().message()).isEqualTo("이 지역은 안전 데이터가 부족합니다");
    }

    @Test
    void missingOrBrokenInputIsRejectedAsBadRequest() {
        RouteService s = service(facilities(List.of()), 21);

        assertThatThrownBy(() -> s.route(new RouteRequest(null, new Place(LAT, 126.93220, null), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.route(new RouteRequest(new Place(null, 126.93, null), new Place(LAT, 126.93220, null), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.route(request("dark"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pointTooFarFromAnyRoadIsNoRoute() {
        RouteService s = TestRoutes.service(diamond(0.0006), facilities(List.of()), 21, props("route.snap-max-m=5"));

        // 도로망 범위 안이지만 가장 가까운 노드에서 약 60m 떨어진 출발점 (마름모 한가운데)
        assertError(() -> s.route(new RouteRequest(new Place(LAT + 0.0003, 126.93055, null), new Place(LAT, 126.93220, null), null)),
                RouteError.NO_ROUTE);
    }

    @Test
    void isolatedPieceIsSkippedWhenSnapping() {
        // S–M–T 도로망 옆에 다른 길과 이어지지 않은 계단 조각(출발점 바로 옆). 조각이 아니라 큰 도로망에 붙인다
        double[] a = {126.92995, LAT + 0.0002};
        double[] b = {126.92990, LAT + 0.0003};
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, 8L, a, 9L, b), List.of(
                edge(1, S, M, S_P, M_P, EdgePenalty.NONE), edge(2, M, T, M_P, T_P, EdgePenalty.NONE),
                edge(3, 8, 9, a, b, EdgePenalty.NONE)));

        RouteResult r = TestRoutes.service(g, facilities(List.of()), 12).route(
                new RouteRequest(new Place(a[1], a[0], null), new Place(LAT, 126.93220, null), null));

        assertThat(r.routes()).hasSize(1);
        assertThat(r.routes().get(0).distanceM()).isGreaterThan(200);
    }

    @Test
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
    void facilityMarksAreCappedWithHelpAndBellFirst() {
        RouteService s = TestRoutes.service(diamond(0.0006), facilities(brightSideFacilities()), 21,
                props("route.max-facilities=2"));

        RouteResult r = s.route(request("auto"));

        assertThat(r.facilities()).extracting(RouteResult.FacilityMark::type).containsExactly("EMERGENCY_BELL", "CCTV");
    }

    @Test
    void partialDataGapOnOneRoute() {
        double[] nP = {126.93110, LAT + 0.0006};
        EdgePenalty gap = new EdgePenalty(Map.of(), true);
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                edge(10, S, M, S_P, M_P, DARK), edge(11, M, T, M_P, T_P, DARK),
                edge(12, S, N, S_P, nP, gap), edge(13, T, N, T_P, nP, gap)));

        // 북쪽 길에만 등이 있어 밤에는 북쪽(데이터 부족)으로 간다
        RouteResult r = TestRoutes.service(g, facilities(brightSideFacilities()), 21).route(request("auto"));

        assertThat(r.dataQuality().status()).isEqualTo("PARTIAL");
        assertThat(r.dataQuality().message()).isNotNull();
    }

    @Test
    void extraMinutesAlwaysMatchDurationDifference() {
        // 여러 우회 정도에서 카드의 "(n분) 더"와 두 경로 시간 차가 같아야 한다
        for (double north : new double[] {0.0003, 0.0004, 0.0005, 0.0006, 0.0007}) {
            RouteResult r = TestRoutes.service(diamond(north), facilities(List.of()), 21).route(request("on"));
            if (r.routes().size() == 2) {
                assertThat(r.explanation().extraMin())
                        .isEqualTo(r.routes().get(1).durationMin() - r.routes().get(0).durationMin());
            }
        }
    }

    @Test
    void sunsetIsHourMinute() {
        assertThat(service(facilities(List.of()), 21).route(request("auto")).conditions().sunset()).matches("\\d{2}:\\d{2}");
    }

    @Test
    void errorStatusesMatchRequirements() {
        // 요구사항 8.3 오류 표
        assertThat(RouteError.SAME_POINT.httpStatus()).isEqualTo(400);
        assertThat(RouteError.TOO_FAR.httpStatus()).isEqualTo(400);
        assertThat(RouteError.OUT_OF_AREA.httpStatus()).isEqualTo(422);
        assertThat(RouteError.NO_ROUTE.httpStatus()).isEqualTo(404);
        assertThat(RouteError.DB_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(new RouteException(RouteError.TOO_FAR).getMessage()).isEqualTo("걸어가기엔 멀어요");
    }

    @Test
    void gridEndToEndWithinTwoSeconds() {
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

    private static void assertError(Runnable call, RouteError expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(expected));
    }
}
