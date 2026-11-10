package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.walk.WalkSpeedService;

/**
 * 들를 곳 고르기와 길 찾기. 가짜 엔진은 곧은 거리를 그대로 걷는 거리로, 1분에 50m 로 셈.
 * 출발은 동쪽, 집은 서쪽. 편의점은 출발 가까이, 다이소는 집 가까이 있다.
 */
class StopPlannerTests {

    static final double LAT = 35.1480;
    static final double LON = 126.9260;
    static final Conversation.Place HOME = new Conversation.Place("집", null, 35.1480, 126.9100);

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final RouteService routes = mock(RouteService.class);
    final StopPlanner planner = new StopPlanner(kakao, routes, mock(WalkSpeedService.class));

    StopPlannerTests() {
        when(kakao.search(eq("다이소"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "다이소 서쪽점", "광주 동구", 35.1481, 126.9120, 100),
                new Place("2", "다이소 북쪽점", "광주 동구", 35.1600, 126.9180, 300)));
        when(kakao.search(eq("편의점"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("3", "GS25 동쪽점", "광주 동구", 35.1479, 126.9240, 80),
                new Place("4", "CU 남쪽점", "광주 동구", 35.1380, 126.9180, 200)));
        when(routes.route(any())).thenAnswer(inv -> fakeEngine(inv.getArgument(0)));
    }

    /** 곧은 거리로 엔진 흉내. 들를 곳이 있으면 차례대로 거치고 stops[].atM 을 붙인다 (w10 모양) */
    static RouteResult fakeEngine(RouteRequest r) {
        List<RouteRequest.Place> pts = new java.util.ArrayList<>();
        pts.add(r.origin());
        if (r.stops() != null) {
            pts.addAll(r.stops());
        }
        pts.add(r.destination());
        int m = 0;
        List<RouteResult.StopMark> marks = new java.util.ArrayList<>();
        for (int i = 1; i < pts.size(); i++) {
            m += AssistantTools.straightM(pts.get(i - 1).lat(), pts.get(i - 1).lon(), pts.get(i).lat(), pts.get(i).lon());
            if (i < pts.size() - 1) {
                marks.add(new RouteResult.StopMark(pts.get(i).name(), pts.get(i).lat(), pts.get(i).lon(), m));
            }
        }
        RouteResult.Route safe = new RouteResult.Route("safe", m, (int) Math.ceil(m / 50.0), List.of(), Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, List.copyOf(marks));
        return new RouteResult(null, List.of(safe), null, List.of(), null);
    }

    StopPlanner.Plan plan(boolean ordered) {
        return planner.plan(new StopPlanner.Ask(null, LAT, LON, HOME, List.of(new Conversation.Stop("다이소", "다이소"),
                new Conversation.Stop("편의점", "택배")), ordered, "adult", "off"));
    }

    @Test
    void picksShorterOrderAndSaysHowMuchItSaves() {
        StopPlanner.Plan p = plan(false);
        assertThat(p.failText()).isNull();
        assertThat(p.stops()).extracting(StopPlanner.Stop::name).containsExactly("GS25 동쪽점", "다이소 서쪽점");
        assertThat(p.legs()).hasSize(3);
        assertThat(p.minutes()).isEqualTo(p.legs().stream().mapToInt(StopPlanner.Leg::minutes).sum());
        assertThat(p.directMin()).isNotNull().isLessThanOrEqualTo(p.minutes());
        assertThat(p.savedMin()).isNotNull().isPositive();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> via = (List<Map<String, Object>>) p.request().get("stops");
        assertThat(via).extracting(v -> v.get("name")).containsExactly("GS25 동쪽점", "다이소 서쪽점");
        assertThat(p.request()).containsKeys("origin", "destination", "mode", "night");
        // 고른 길 1번(들를 곳 같이) + 바로 가는 길 1 + 말한 순서 1 (w10 전에는 구간마다라 7번)
        verify(routes, times(3)).route(any());
        assertThat(p.legs()).extracting(StopPlanner.Leg::to).containsExactly("GS25 동쪽점", "다이소 서쪽점", "집");
        assertThat(p.distanceM()).isEqualTo(p.legs().stream().mapToInt(StopPlanner.Leg::distanceM).sum());
    }

    @Test
    void spokenOrderStaysWhenOrdered() {
        StopPlanner.Plan p = plan(true);
        assertThat(p.stops()).extracting(StopPlanner.Stop::label).containsExactly("다이소", "택배");
        assertThat(p.savedMin()).isNull();
        verify(routes, times(2)).route(any());
    }

    @Test
    void searchesAroundTheMiddleOfTheWay() {
        plan(true);
        verify(kakao).search("다이소", (LAT + HOME.lat()) / 2, (LON + HOME.lon()) / 2);
    }

    @Test
    void missingShopIsSaid() {
        when(kakao.search(eq("다이소"), anyDouble(), anyDouble())).thenReturn(List.of());
        StopPlanner.Plan p = plan(false);
        assertThat(p.failText()).contains("다이소", "찾지 못했어요");
        verify(routes, times(0)).route(any());
    }

    @Test
    void stopsGoToEngineInOneCall() {
        plan(true);
        verify(routes).route(org.mockito.ArgumentMatchers.argThat(r -> r.stops() != null && r.stops().size() == 2
                && r.stops().get(0).name().equals("다이소 서쪽점")));
    }

    @Test
    void oldEngineWithoutStopMarksFallsBackToLegs() {
        // 엔진 답에 stops 가 없으면(w10 전 서버) 예전처럼 구간마다
        org.mockito.Mockito.doAnswer(inv -> {
            RouteResult full = fakeEngine(inv.getArgument(0));
            RouteResult.Route x = full.routes().get(0);
            RouteResult.Route bare = new RouteResult.Route("safe", x.distanceM(), x.durationMin(), List.of(), Map.of(),
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, List.of());
            return new RouteResult(null, List.of(bare), null, List.of(), null);
        }).when(routes).route(any());
        StopPlanner.Plan p = plan(true);
        assertThat(p.legs()).hasSize(3);
        verify(routes, times(5)).route(any()); // 한 번 + 구간 3 + 바로 1
    }

    @Test
    void shortestTriesAllOrders() {
        List<List<StopPlanner.Stop>> c = List.of(
                List.of(new StopPlanner.Stop("a", "a1", null, 35.1480, 126.9110)),
                List.of(new StopPlanner.Stop("b", "b1", null, 35.1480, 126.9250)));
        assertThat(StopPlanner.shortest(LAT, LON, HOME, c, true)).extracting(StopPlanner.Stop::name)
                .containsExactly("b1", "a1");
        assertThat(StopPlanner.shortest(LAT, LON, HOME, c, false)).extracting(StopPlanner.Stop::name)
                .containsExactly("a1", "b1");
    }
}
