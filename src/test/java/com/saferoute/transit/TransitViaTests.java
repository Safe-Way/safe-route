package com.saferoute.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.bus.BusArrival;
import com.saferoute.bus.BusNearbyResponse;
import com.saferoute.bus.BusService;
import com.saferoute.bus.StopArrivals;
import com.saferoute.common.ApiErrorHandler;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.subway.SubwayNearbyResponse;
import com.saferoute.subway.SubwayService;

/** /api/transit 들를 곳(via): 앞 구간 + 들르기 + 뒤 구간 (11-09 결정 ②) */
class TransitViaTests {

    private final OdsayClient odsay = mock(OdsayClient.class);
    private final RouteService route = mock(RouteService.class);
    private final BusService bus = mock(BusService.class);
    private final SubwayService subway = mock(SubwayService.class);
    private final TransitService service = new TransitService(odsay, route, bus, subway,
            Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneId.of("Asia/Seoul")));

    private static final RouteRequest.Place HOME = new RouteRequest.Place(35.1440, 126.9320, "회사");
    private static final RouteRequest.Place SHOP = new RouteRequest.Place(35.1470, 126.9190, "다이소 충장로점");
    private static final RouteRequest.Place GOAL = new RouteRequest.Place(35.1600, 126.8800, "집");

    /** 걷기 3분 → 버스 12분 → 걷기 2분 = ODsay 20분 */
    private static List<OdsayPath> bus() {
        return List.of(new OdsayPath(20, 1250, List.of(
                new OdsayPath.Seg(3, 200, 3, null, null, List.of(), null, 0),
                new OdsayPath.Seg(2, 3100, 12, new OdsayPath.Place("조선대학교", 35.1420, 126.9301),
                        new OdsayPath.Place("문화전당역", 35.1470, 126.9190), List.of("첨단09"), null, 6),
                new OdsayPath.Seg(3, 120, 2, null, null, List.of(), null, 0))));
    }

    private static RouteResult.Route safe(int meters, int minutes) {
        RouteResult.Route r = mock(RouteResult.Route.class);
        when(r.kind()).thenReturn("safe");
        when(r.distanceM()).thenReturn(meters);
        when(r.durationMin()).thenReturn(minutes);
        when(r.geometry()).thenReturn(List.of(new double[] {126.9, 35.1}));
        return r;
    }

    private void stubLive() {
        RouteResult walk = new RouteResult(null, List.of(safe(300, 5)), null, List.of(), null);
        when(route.route(any())).thenReturn(walk);
        when(bus.nearby(anyDouble(), anyDouble())).thenReturn(new BusNearbyResponse(List.of(
                new StopArrivals(11, "조선대학교", "산수", 35.1420, 126.9301, 5, true,
                        List.of(new BusArrival(2, "첨단09", 7, 3, true, "첨단", "a"))))));
        when(subway.nearby(anyDouble(), anyDouble())).thenReturn(new SubwayNearbyResponse("timetable", "평일", List.of()));
    }

    private TransitResult search(List<RouteRequest.Place> via) {
        return service.search(new RouteRequest(HOME, GOAL, "off", "adult", null), via);
    }

    @Test
    void putsStopLegBetweenFrontAndBack() {
        stubLive();
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(bus());
        TransitResult r = search(List.of(SHOP));
        assertThat(r.paths()).hasSize(1);
        List<TransitResult.Leg> legs = r.paths().get(0).legs();
        assertThat(legs).extracting(TransitResult.Leg::type)
                .containsExactly("walk", "bus", "walk", "stop", "walk", "bus", "walk");
        TransitResult.Leg stop = legs.get(3);
        assertThat(stop.from().name()).isEqualTo("다이소 충장로점");
        assertThat(stop.minutes()).isZero();
        // 앞 버스는 곧 오는 버스를 붙이고, 뒤 버스는 언제 탈지 몰라서 빼고 대략 시간만
        assertThat(legs.get(1).buses()).isNotEmpty();
        assertThat(legs.get(5).buses()).isEmpty();
        assertThat(legs.get(5).liveAvailable()).isFalse();
        assertThat(legs.get(5).minutes()).isEqualTo(12);
        // 걷기는 우리 길(5분) 3번 + 버스 12분 2번, ODsay 걷기 5분씩 빼고 더함
        assertThat(r.paths().get(0).totalMin()).isEqualTo((20 - 5 + 10) * 2);
        assertThat(r.paths().get(0).fare()).isEqualTo(2500);
        assertThat(r.note()).contains("다 봤어요");
        verify(odsay).search(HOME.lat(), HOME.lon(), SHOP.lat(), SHOP.lon());
        verify(odsay).search(SHOP.lat(), SHOP.lon(), GOAL.lat(), GOAL.lon());
    }

    @Test
    void closeHalfIsWalkOnly() {
        stubLive();
        when(odsay.search(HOME.lat(), HOME.lon(), SHOP.lat(), SHOP.lon())).thenThrow(TransitException.tooClose());
        when(odsay.search(SHOP.lat(), SHOP.lon(), GOAL.lat(), GOAL.lon())).thenReturn(bus());
        TransitResult r = search(List.of(SHOP));
        assertThat(r.paths().get(0).legs()).extracting(TransitResult.Leg::type)
                .containsExactly("walk", "stop", "walk", "bus", "walk");
        assertThat(r.paths().get(0).legs().get(0).safeWalk()).isTrue();
        assertThat(r.paths().get(0).fare()).isEqualTo(1250);
    }

    @Test
    void onlyOneStopByTransit() {
        assertThatThrownBy(() -> search(List.of(SHOP, SHOP))).isInstanceOf(TransitException.class)
                .hasMessageContaining("한 곳까지");
        verify(odsay, never()).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void noViaIsSameAsBefore() {
        stubLive();
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(bus());
        TransitResult r = search(List.of());
        assertThat(r.paths().get(0).legs()).extracting(TransitResult.Leg::type).doesNotContain("stop");
        verify(odsay, times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void controllerReadsVia() throws Exception {
        stubLive();
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(bus());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransitController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
        mvc.perform(post("/api/transit").contentType(MediaType.APPLICATION_JSON).content("""
                        {"origin":{"lat":35.144,"lon":126.932},"destination":{"lat":35.16,"lon":126.88},
                         "via":[{"lat":35.147,"lon":126.919,"name":"다이소"}],"mode":"adult"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths[0].legs[3].type").value("stop"))
                .andExpect(jsonPath("$.paths[0].legs[3].from.name").value("다이소"));
        verify(odsay, times(2)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}
