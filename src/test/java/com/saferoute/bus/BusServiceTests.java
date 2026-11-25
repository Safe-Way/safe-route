package com.saferoute.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 가짜 DB·가짜 광주버스정보로 정류장 묶기·정렬·실패·기억해 두기를 확인 (인터넷·DB 없이 돈다) */
class BusServiceTests {

    private static final double LAT = 35.1401;
    private static final double LON = 126.9336;
    private static final BusStop STOP_NAMGWANGJU = new BusStop(1230, "조선대", "남광주", 35.1405, 126.9330, 60);
    private static final BusStop STOP_SANSU = new BusStop(1231, "조선대", "산수오거리", 35.1406, 126.9331, 70);
    private static final BusArrival BUS_01 = new BusArrival(2, "01", 14, 10, false, "무등산국립공원", "전대동창회관");
    private static final BusArrival BUS_15 = new BusArrival(15, "15", 3, 1, true, "광주역", "조선대");
    private static final BusArrival BUS_07 = new BusArrival(7, "07", null, null, false, "상무지구", "");

    private final BusStopRepository repo = mock(BusStopRepository.class);
    private final GjBisClient busApi = mock(GjBisClient.class);
    private final MovableClock clock = new MovableClock();
    private final BusService service = new BusService(repo, busApi, clock, Executors.newFixedThreadPool(2));

    @Test
    void stopsKeepDistanceOrderAndBusesSoonestFirst() {
        when(repo.near(LAT, LON, BusService.RADIUS_M, BusService.MAX_STOPS)).thenReturn(List.of(STOP_NAMGWANGJU, STOP_SANSU));
        when(busApi.arrivals(1230)).thenReturn(List.of(BUS_01, BUS_07, BUS_15));
        when(busApi.arrivals(1231)).thenReturn(List.of());

        BusNearbyResponse r = service.nearby(LAT, LON);

        assertThat(r.stops()).extracting(StopArrivals::id).containsExactly(1230, 1231);
        assertThat(r.stops().get(0).arrivals()).containsExactly(BUS_15, BUS_01, BUS_07);
        assertThat(r.stops().get(0).available()).isTrue();
        assertThat(r.stops().get(1).arrivals()).isEmpty();
        assertThat(r.stops().get(1).available()).isTrue();
    }

    @Test
    void oneStopFailingKeepsTheOthers() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(STOP_NAMGWANGJU, STOP_SANSU));
        when(busApi.arrivals(1230)).thenThrow(new BusUnavailableException("고장", null));
        when(busApi.arrivals(1231)).thenReturn(List.of(BUS_15));

        BusNearbyResponse r = service.nearby(LAT, LON);

        assertThat(r.stops().get(0).available()).isFalse();
        assertThat(r.stops().get(0).arrivals()).isEmpty();
        assertThat(r.stops().get(1).arrivals()).containsExactly(BUS_15);
    }

    @Test
    void slowStopDoesNotHoldTheAnswer() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(STOP_NAMGWANGJU));
        when(busApi.arrivals(1230)).thenAnswer(i -> {
            Thread.sleep(5_000);
            return List.of(BUS_15);
        });

        long started = System.nanoTime();
        BusNearbyResponse r = service.nearby(LAT, LON);
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(r.stops().get(0).available()).isFalse();
        assertThat(waitedMs).isLessThan(4_500);
    }

    @Test
    void noStopNearbyIsEmpty() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(service.nearby(LAT, LON).stops()).isEmpty();
    }

    @Test
    @DisplayName("같은 정류장을 30초 안(20초 뒤)에 다시 물으면 버스 API를 또 부르지 않고 기억한 답을 준다")
    void sameStopCallsApiOnce() {
        int stopId = 1230;
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(STOP_NAMGWANGJU));
        when(busApi.arrivals(stopId)).thenReturn(List.of(BUS_15));

        service.nearby(LAT, LON);
        clock.move(Duration.ofSeconds(20)); // 진짜로 기다리지 않고 시계만 20초 앞으로
        BusNearbyResponse secondReply = service.nearby(LAT, LON);

        assertThat(secondReply.stops().get(0).arrivals()).containsExactly(BUS_15);
        verify(busApi, times(1)).arrivals(eq(stopId)); // 두 번 물었지만 API는 한 번만
    }

    @Test
    void asksAgainAfter30Seconds() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(STOP_NAMGWANGJU));
        when(busApi.arrivals(1230)).thenReturn(List.of(BUS_15));

        service.nearby(LAT, LON);
        clock.move(Duration.ofSeconds(31));
        service.nearby(LAT, LON);

        verify(busApi, times(2)).arrivals(eq(1230));
    }

    @Test
    void failureIsNotKept() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(STOP_NAMGWANGJU));
        when(busApi.arrivals(1230)).thenThrow(new BusUnavailableException("고장", null)).thenReturn(List.of(BUS_15));

        service.nearby(LAT, LON);
        BusNearbyResponse second = service.nearby(LAT, LON);

        assertThat(second.stops().get(0).available()).isTrue();
    }

    // ---- 버스 위치 (locations) ----

    private static final GjBisClient.BusOnLine FAR = new GjBisClient.BusOnLine("775293", 1205, true, 30);
    private static final GjBisClient.BusOnLine NEAR = new GjBisClient.BusOnLine("775240", 852, false, 17);
    private static final GjBisClient.BusOnLine NOT_IN_TABLE = new GjBisClient.BusOnLine("775299", 99999, false, 40);

    @Test
    void locationsGetStopCoordinatesInLineOrder() {
        when(busApi.locations(2)).thenReturn(List.of(FAR, NOT_IN_TABLE, NEAR));
        when(repo.byIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.Map.of(
                852, new BusStop(852, "전대동창회관", "전남대", 35.17, 126.91, 0),
                1205, new BusStop(1205, "조선대", "남광주", 35.14, 126.93, 0)));

        BusLocationResponse r = service.locations(2);

        assertThat(r.available()).isTrue();
        assertThat(r.buses()).containsExactly(
                new BusLocation("775240", false, 852, "전대동창회관", 35.17, 126.91, 17),
                new BusLocation("775293", true, 1205, "조선대", 35.14, 126.93, 30),
                new BusLocation("775299", false, 99999, null, null, null, 40));
    }

    @Test
    void locationFailureIsUnavailableAndNotKept() {
        when(busApi.locations(2)).thenThrow(new BusUnavailableException("고장", null)).thenReturn(List.of());

        assertThat(service.locations(2).available()).isFalse();
        assertThat(service.locations(2).available()).isTrue();
    }

    @Test
    void locationsKept15Seconds() {
        when(busApi.locations(2)).thenReturn(List.of());

        service.locations(2);
        clock.move(Duration.ofSeconds(10));
        service.locations(2);
        verify(busApi, times(1)).locations(2);

        clock.move(Duration.ofSeconds(6));
        service.locations(2);
        verify(busApi, times(2)).locations(2);
    }

    /** 시간을 마음대로 옮길 수 있는 시계 */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T00:00:00Z");

        void move(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
