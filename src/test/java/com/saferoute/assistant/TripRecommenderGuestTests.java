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
}
