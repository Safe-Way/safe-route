package com.saferoute.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.saferoute.bus.BusArrival;
import com.saferoute.bus.BusNearbyResponse;
import com.saferoute.bus.BusService;
import com.saferoute.bus.StopArrivals;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.subway.StationTrains;
import com.saferoute.subway.SubwayNearbyResponse;
import com.saferoute.subway.SubwayService;
import com.saferoute.subway.SubwayTrain;

class TransitServiceTests {

    private final OdsayClient odsay = mock(OdsayClient.class);
    private final RouteService route = mock(RouteService.class);
    private final BusService bus = mock(BusService.class);
    private final SubwayService subway = mock(SubwayService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final TransitService service = new TransitService(odsay, route, bus, subway, clock);

    private static final RouteRequest.Place HOME = new RouteRequest.Place(35.1440, 126.9320, "집");
    private static final RouteRequest.Place GOAL = new RouteRequest.Place(35.1380, 126.9310, "병원");

    /** 걷기 4분 → 버스 12분 → 걷기 2분 → 지하철 11분 → 걷기 4분 = ODsay 38분 (기다리는 시간 5분 포함) */
    private static List<OdsayPath> busThenSubway(String subwayStart) {
        return List.of(new OdsayPath(38, 1250, List.of(
                new OdsayPath.Seg(3, 250, 4, null, null, List.of(), null, 0),
                new OdsayPath.Seg(2, 3100, 12, new OdsayPath.Place("조선대학교", 35.1420, 126.9301),
                        new OdsayPath.Place("문화전당역", 35.1470, 126.9190), List.of("첨단09"), null, 6,
                        List.of(new OdsayPath.Place("조선대학교", 35.1420, 126.9301),
                                new OdsayPath.Place("산수오거리", 35.1450, 126.9250),
                                new OdsayPath.Place("문화전당역", 35.1470, 126.9190))),
                new OdsayPath.Seg(3, 120, 2, null, null, List.of(), null, 0),
                new OdsayPath.Seg(1, 5200, 11, new OdsayPath.Place(subwayStart, 35.1465, 126.9195),
                        new OdsayPath.Place("남광주", 35.1390, 126.9300), List.of("광주 1호선"), "녹동", 5),
                new OdsayPath.Seg(3, 250, 4, null, null, List.of(), null, 0))));
    }

    /** 길찾기 결과. Route 는 B 가 칸을 늘려 가므로 직접 만들지 않고 쓰는 칸만 흉내 낸다 */
    private static RouteResult walkResult(int meters, int minutes) {
        return new RouteResult(null, List.of(route("shortest", meters - 20, minutes), route("safe", meters, minutes)),
                null, List.of(), null);
    }

    private static RouteResult.Route route(String kind, int meters, int minutes) {
        RouteResult.Route r = mock(RouteResult.Route.class);
        when(r.kind()).thenReturn(kind);
        when(r.distanceM()).thenReturn(meters);
        when(r.durationMin()).thenReturn(minutes);
        when(r.geometry()).thenReturn(List.of(new double[] {126.9, 35.1}));
        return r;
    }

    private static StationTrains station(String name, Boolean wheelchair) {
        return new StationTrains(1, name, 35.1465, 126.9195, 10, null, null, null, null,
                List.of(new SubwayTrain("평동행", "평동", "12:05", 5, null, 2),
                        new SubwayTrain("녹동행", "녹동", "12:03", 3, null, 1),
                        new SubwayTrain("녹동행", "소태", "12:13", 13, null, 4)),
                new StationTrains.ElevatorExits(wheelchair == Boolean.FALSE ? 0 : 2, null, 0, 4, wheelchair));
    }

    private void stubLive(Boolean wheelchair) {
        RouteResult walk = walkResult(300, 5); // 흉내 낸 Route 를 먼저 만든다 (when 안에서 만들면 Mockito 가 헷갈림)
        when(route.route(any())).thenReturn(walk);
        when(bus.nearby(anyDouble(), anyDouble())).thenReturn(new BusNearbyResponse(List.of(
                new StopArrivals(10, "조선대학교", "남광주", 35.1421, 126.9302, 30, true,
                        List.of(new BusArrival(1, "봉선37", 2, 1, false, "x", "y"))),
                new StopArrivals(11, "조선대학교", "산수", 35.1420, 126.9301, 5, true,
                        List.of(new BusArrival(2, "첨단09", 7, 3, true, "첨단", "a"),
                                new BusArrival(3, "봉선37", 1, 1, false, "x", "y"),
                                new BusArrival(2, "첨단09", 20, 9, false, "첨단", "b"))))));
        when(subway.nearby(anyDouble(), anyDouble()))
                .thenReturn(new SubwayNearbyResponse("timetable", "평일", List.of(station("문화전당", wheelchair))));
    }

    @Test
    void buildsLegsWithSafeWalkBusArrivalsAndTrains() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);

        TransitResult r = service.search(new RouteRequest(HOME, GOAL, null, "senior"));

        assertThat(r.paths()).hasSize(1);
        TransitResult.TransitPath p = r.paths().get(0);
        assertThat(p.legs()).extracting(TransitResult.Leg::type)
                .containsExactly("walk", "bus", "walk", "subway", "walk");
        // ODsay 걷기 10분을 우리 걷기 5분 × 3 = 15분으로 바꿈: 38 - 10 + 15
        assertThat(p.totalMin()).isEqualTo(43);
        assertThat(p.walkM()).isEqualTo(900);
        assertThat(p.transfers()).isEqualTo(1);
        assertThat(p.fare()).isEqualTo(1250);

        TransitResult.Leg firstWalk = p.legs().get(0);
        assertThat(firstWalk.safeWalk()).isTrue();
        assertThat(firstWalk.from().name()).isEqualTo("집");
        assertThat(firstWalk.to().name()).isEqualTo("조선대학교");

        TransitResult.Leg busLeg = p.legs().get(1);
        assertThat(busLeg.liveAvailable()).isTrue();
        // 첨단09가 오는 정류장(길 건너 아닌 쪽)을 골라 그 버스만, 빨리 오는 순
        assertThat(busLeg.buses()).extracting(BusArrival::remainMin).containsExactly(7, 20);
        // 지나는 정류장: 타는 곳부터 내리는 곳까지 차례대로
        assertThat(busLeg.stops()).extracting(TransitResult.Stop::name)
                .containsExactly("조선대학교", "산수오거리", "문화전당역");
        assertThat(busLeg.stops().get(1).lat()).isEqualTo(35.1450);
        assertThat(firstWalk.stops()).isEmpty();

        TransitResult.Leg subwayLeg = p.legs().get(3);
        assertThat(subwayLeg.trains()).extracting(SubwayTrain::direction).containsOnly("녹동행");
        assertThat(subwayLeg.way()).isEqualTo("녹동");
        assertThat(subwayLeg.stops()).isEmpty();
        assertThat(r.hiddenForWheelchair()).isZero();
    }

    @Test
    void walkLegsCarryGuestIdForPersonalPreferences() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);

        service.search(new RouteRequest(HOME, GOAL, null, "senior", "guest-1"));

        // 걷는 구간 길찾기마다 같은 게스트 번호를 넘겨야 원일님 엔진이 계단·우회·쉼 간격 선호를 씀
        ArgumentCaptor<RouteRequest> sent = ArgumentCaptor.forClass(RouteRequest.class);
        verify(route, atLeastOnce()).route(sent.capture());
        assertThat(sent.getAllValues()).isNotEmpty().allMatch(r -> "guest-1".equals(r.guestId()));
    }

    @Test
    void walkFallsBackToOdsayWhenRouteFails() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);
        when(route.route(any())).thenThrow(new RouteException(RouteError.SAME_POINT));

        TransitResult.TransitPath p = service.search(new RouteRequest(HOME, GOAL, null, null)).paths().get(0);

        assertThat(p.legs().get(0).safeWalk()).isFalse();
        assertThat(p.legs().get(0).distanceM()).isEqualTo(250);
        assertThat(p.totalMin()).isEqualTo(38);
    }

    @Test
    void walkFallsBackToOdsayWhenEngineFailsUnexpectedly() {
        // DB가 잠깐 끊기는 등 뜻밖의 오류도 대중교통 전체를 실패시키지 않고 그 걷는 구간만 ODsay 값으로 (11-16)
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);
        when(route.route(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        TransitResult.TransitPath p = service.search(new RouteRequest(HOME, GOAL, null, null)).paths().get(0);

        assertThat(p.legs().get(0).safeWalk()).isFalse();
        assertThat(p.legs().get(0).distanceM()).isEqualTo(250);
        assertThat(p.totalMin()).isEqualTo(38);
    }

    @Test
    void wheelchairHidesPathThroughStationWithoutElevatorExit() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("양동시장"));
        stubLive(false);

        TransitResult r = service.search(new RouteRequest(HOME, GOAL, null, "wheelchair"));

        assertThat(r.paths()).isEmpty();
        assertThat(r.hiddenForWheelchair()).isEqualTo(1);
        assertThat(r.note()).contains("엘리베이터");
    }

    @Test
    void otherModesKeepThatPath() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("양동시장"));
        stubLive(false);
        assertThat(service.search(new RouteRequest(HOME, GOAL, null, "senior")).paths()).hasSize(1);
    }

    @Test
    void sameTripUsesRememberedOdsayAnswer() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);

        service.search(new RouteRequest(HOME, GOAL, null, null));
        service.search(new RouteRequest(new RouteRequest.Place(35.14402, 126.93203, "집"), GOAL, null, null));

        verify(odsay, times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void liveInfoFailureStillGivesPath() {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        RouteResult walk = walkResult(300, 5); // 흉내 낸 Route 를 먼저 만든다 (when 안에서 만들면 Mockito 가 헷갈림)
        when(route.route(any())).thenReturn(walk);
        when(bus.nearby(anyDouble(), anyDouble())).thenThrow(new RuntimeException("bis down"));
        when(subway.nearby(anyDouble(), anyDouble())).thenThrow(new RuntimeException("db down"));

        TransitResult.TransitPath p = service.search(new RouteRequest(HOME, GOAL, null, null)).paths().get(0);

        assertThat(p.legs().get(1).liveAvailable()).isFalse();
        assertThat(p.legs().get(1).buses()).isEmpty();
        assertThat(p.legs().get(3).liveAvailable()).isFalse();
    }

    @Test
    void namesMatchWithoutStationSuffix() {
        assertThat(TransitService.norm("문화전당역")).isEqualTo(TransitService.norm("문화 전당"));
    }

    @Test
    void rainAndEngineRouteGoToWalkLegs() {
        // 원일님 #137 부탁: 요청 rain(on)을 걷는 구간 길찾기에 넘기고, 엔진이 준 경로를 Leg.walkRoute 에 담는다
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);
        com.saferoute.situation.WeatherDustService weather = mock(com.saferoute.situation.WeatherDustService.class);
        TransitService withWeather = new TransitService(odsay, route, bus, subway, clock, weather);

        TransitResult r = withWeather.search(new RouteRequest(HOME, GOAL, null, "senior", null, "on"));

        ArgumentCaptor<RouteRequest> sent = ArgumentCaptor.forClass(RouteRequest.class);
        verify(route, atLeastOnce()).route(sent.capture());
        assertThat(sent.getAllValues()).extracting(RouteRequest::rain).containsOnly("on");
        verify(weather, times(0)).judge(anyDouble(), anyDouble()); // on 이면 날씨를 묻지 않음
        TransitResult.Leg firstWalk = r.paths().get(0).legs().get(0);
        assertThat(firstWalk.walkRoute()).isNotNull();
        assertThat(firstWalk.walkRoute().kind()).isEqualTo("safe");
    }

    @Test
    void autoRainAsksWeatherOnceForAllWalkLegs() {
        // rain 이 없으면(auto) 걷는 구간마다 묻지 않고 출발지 날씨를 한 번만 물어 on·off 로 넘긴다
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(busThenSubway("문화전당"));
        stubLive(true);
        com.saferoute.situation.WeatherDustService weather = mock(com.saferoute.situation.WeatherDustService.class);
        when(weather.judge(anyDouble(), anyDouble()))
                .thenReturn(new com.saferoute.situation.WeatherDust(true, false, false, 12.0, true, false, true));
        TransitService withWeather = new TransitService(odsay, route, bus, subway, clock, weather);

        withWeather.search(new RouteRequest(HOME, GOAL, null, "senior"));

        verify(weather, times(1)).judge(anyDouble(), anyDouble());
        ArgumentCaptor<RouteRequest> sent = ArgumentCaptor.forClass(RouteRequest.class);
        verify(route, atLeastOnce()).route(sent.capture());
        assertThat(sent.getAllValues()).extracting(RouteRequest::rain).containsOnly("on");
    }

    @Test
    void wrongRainIsBadRequest() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.search(
                new RouteRequest(HOME, GOAL, null, "senior", null, "maybe")))
                .isInstanceOf(TransitException.class);
    }
}
