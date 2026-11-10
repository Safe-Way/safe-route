package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.saferoute.report.ReportRepository;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.transit.TransitService;
import com.saferoute.walk.WalkSpeedService;

/** 비서가 길을 찾을 때 게스트 번호를 원일님 엔진과 대중교통에 넘기는지 (w02 개인 선호) */
class TripRecommenderGuestTests {

    final RouteService routes = mock(RouteService.class);
    final TransitService transit = mock(TransitService.class);
    final TripRecommender rec = new TripRecommender(routes, transit, mock(WeatherDustService.class),
            mock(ReportRepository.class), mock(WalkSpeedService.class), mock(MemoryRepository.class),
            mock(TripScorer.class), 30);
    final WeatherDust clear = new WeatherDust(false, false, false, 15.0, true, false, true);
    final Conversation.Place goal = new Conversation.Place("문화전당", null, 35.1468, 126.9198, null);

    RouteRequest sentToEngine(String guestId) {
        when(routes.route(any())).thenThrow(new RouteException(RouteError.NO_ROUTE));
        when(transit.search(any())).thenThrow(new RuntimeException("시험"));
        rec.recommend(new TripRecommender.Ask(guestId, 35.1400, 126.9300, goal, "senior", "off", clear, null, null));
        ArgumentCaptor<RouteRequest> c = ArgumentCaptor.forClass(RouteRequest.class);
        verify(routes).route(c.capture());
        ArgumentCaptor<RouteRequest> t = ArgumentCaptor.forClass(RouteRequest.class);
        verify(transit).search(t.capture());
        assertThat(t.getValue().guestId()).isEqualTo(c.getValue().guestId());
        return c.getValue();
    }

    @Test
    void passesGuestIdToRouteAndTransit() {
        RouteRequest r = sentToEngine("00000000-0000-4000-8000-000000000123");
        assertThat(r.guestId()).isEqualTo("00000000-0000-4000-8000-000000000123");
        assertThat(r.mode()).isEqualTo("senior");
    }

    @Test
    void blankGuestIdMeansModeDefaults() {
        assertThat(sentToEngine("  ").guestId()).isNull();
    }

    @Test
    void assistantWeatherGoesToEngineAsRain() {
        // 원일님 #137 부탁: 비서가 받은 날씨(시험 날씨 "비")를 엔진 rain 으로. 안 넘기면 엔진은 맑은 날로 계산
        WeatherDust rainy = new WeatherDust(true, false, false, 12.0, true, false, true);
        when(routes.route(any())).thenThrow(new RouteException(RouteError.NO_ROUTE));
        when(transit.search(any())).thenThrow(new RuntimeException("시험"));
        rec.recommend(new TripRecommender.Ask(null, 35.1400, 126.9300, goal, "senior", "off", rainy, null, null));
        ArgumentCaptor<RouteRequest> c = ArgumentCaptor.forClass(RouteRequest.class);
        verify(routes).route(c.capture());
        assertThat(c.getValue().rain()).isEqualTo("on");
        ArgumentCaptor<RouteRequest> t = ArgumentCaptor.forClass(RouteRequest.class);
        verify(transit).search(t.capture());
        assertThat(t.getValue().rain()).isEqualTo("on");
    }

    @Test
    void clearWeatherIsRainOff() {
        assertThat(sentToEngine(null).rain()).isEqualTo("off");
    }

    @Test
    void mySpeedKeepsStopsAndEngineRoute() {
        // 걸음 속도로 분을 다시 셀 때 지나는 정류장·엔진 경로가 빠지지 않게
        com.saferoute.route.api.RouteResult.Route engine = mock(com.saferoute.route.api.RouteResult.Route.class);
        com.saferoute.transit.TransitResult.Stop a = new com.saferoute.transit.TransitResult.Stop("집", 35.14, 126.93);
        com.saferoute.transit.TransitResult.Stop b = new com.saferoute.transit.TransitResult.Stop("정류장", 35.15, 126.93);
        com.saferoute.transit.TransitResult.Leg walk = new com.saferoute.transit.TransitResult.Leg("walk", 300, 5, a, b,
                java.util.List.of(), null, null, true, null, null, null, null, null, java.util.List.of(b), engine);
        com.saferoute.walk.WalkSpeed slow = new com.saferoute.walk.WalkSpeed("senior", 50.0, 60.0, 3, true);
        com.saferoute.transit.TransitResult.Leg out = TripRecommender.mySpeed(java.util.List.of(walk), slow).get(0);
        assertThat(out.minutes()).isEqualTo(6);
        assertThat(out.walkRoute()).isSameAs(engine);
        assertThat(out.stops()).containsExactly(b);
    }
}
