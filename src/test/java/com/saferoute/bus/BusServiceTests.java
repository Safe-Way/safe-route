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

import org.junit.jupiter.api.Test;

/** 가짜 DB·가짜 광주버스정보로 정류장 묶기·정렬·실패·기억해 두기를 확인 (인터넷·DB 없이 돈다) */
class BusServiceTests {

    private static final double LAT = 35.1401;
    private static final double LON = 126.9336;
    private static final BusStop A = new BusStop(1230, "조선대", "남광주", 35.1405, 126.9330, 60);
    private static final BusStop B = new BusStop(1231, "조선대", "산수오거리", 35.1406, 126.9331, 70);
    private static final BusArrival LATE = new BusArrival("01", 14, 10, false, "무등산국립공원", "전대동창회관");
    private static final BusArrival SOON = new BusArrival("15", 3, 1, true, "광주역", "조선대");
    private static final BusArrival UNKNOWN = new BusArrival("07", null, null, false, "상무지구", "");

    private final BusStopRepository repo = mock(BusStopRepository.class);
    private final GjBisClient bis = mock(GjBisClient.class);
    private final MovableClock clock = new MovableClock();
    private final BusService service = new BusService(repo, bis, clock, Executors.newFixedThreadPool(2));

    @Test
    void stopsKeepDistanceOrderAndBusesSoonestFirst() {
        when(repo.near(LAT, LON, BusService.RADIUS_M, BusService.MAX_STOPS)).thenReturn(List.of(A, B));
        when(bis.arrivals(1230)).thenReturn(List.of(LATE, UNKNOWN, SOON));
        when(bis.arrivals(1231)).thenReturn(List.of());

        BusNearbyResponse r = service.nearby(LAT, LON);

        assertThat(r.stops()).extracting(StopArrivals::id).containsExactly(1230, 1231);
        assertThat(r.stops().get(0).arrivals()).containsExactly(SOON, LATE, UNKNOWN);
        assertThat(r.stops().get(0).available()).isTrue();
        assertThat(r.stops().get(1).arrivals()).isEmpty();
        assertThat(r.stops().get(1).available()).isTrue();
    }

    @Test
    void oneStopFailingKeepsTheOthers() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(A, B));
        when(bis.arrivals(1230)).thenThrow(new BusUnavailableException("고장", null));
        when(bis.arrivals(1231)).thenReturn(List.of(SOON));

        BusNearbyResponse r = service.nearby(LAT, LON);

        assertThat(r.stops().get(0).available()).isFalse();
        assertThat(r.stops().get(0).arrivals()).isEmpty();
        assertThat(r.stops().get(1).arrivals()).containsExactly(SOON);
    }

    @Test
    void slowStopDoesNotHoldTheAnswer() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(A));
        when(bis.arrivals(1230)).thenAnswer(i -> {
            Thread.sleep(5_000);
            return List.of(SOON);
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
    void sameStopWithin30SecondsAsksOnce() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(A));
        when(bis.arrivals(1230)).thenReturn(List.of(SOON));

        service.nearby(LAT, LON);
        clock.move(Duration.ofSeconds(20));
        BusNearbyResponse again = service.nearby(LAT, LON);

        assertThat(again.stops().get(0).arrivals()).containsExactly(SOON);
        verify(bis, times(1)).arrivals(eq(1230));
    }

    @Test
    void asksAgainAfter30Seconds() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(A));
        when(bis.arrivals(1230)).thenReturn(List.of(SOON));

        service.nearby(LAT, LON);
        clock.move(Duration.ofSeconds(31));
        service.nearby(LAT, LON);

        verify(bis, times(2)).arrivals(eq(1230));
    }

    @Test
    void failureIsNotKept() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(A));
        when(bis.arrivals(1230)).thenThrow(new BusUnavailableException("고장", null)).thenReturn(List.of(SOON));

        service.nearby(LAT, LON);
        BusNearbyResponse second = service.nearby(LAT, LON);

        assertThat(second.stops().get(0).available()).isTrue();
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
