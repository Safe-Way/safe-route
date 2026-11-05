package com.saferoute.bus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 내 주변 정류장과 곧 오는 버스 (2차, 저상버스).
 * <ul>
 *   <li>DB에서 반경 500m 안 정류장을 가까운 순으로 최대 5곳 찾는다</li>
 *   <li>정류장마다 광주버스정보에 동시에 묻고, 최대 3초 기다린다. 늦거나 실패한 정류장만 available=false</li>
 *   <li>같은 정류장은 30초 동안 받은 값을 다시 쓴다 (도착 시간은 금방 바뀌어서 짧게)</li>
 *   <li>사용자 위치는 로그에 쓰지 않는다 (NFR-07)</li>
 * </ul>
 */
@Service
public class BusService {

    static final int RADIUS_M = 500;
    static final int MAX_STOPS = 5;
    static final Duration WAIT = Duration.ofSeconds(3);
    static final Duration KEEP = Duration.ofSeconds(30);
    /** 버스 위치는 15초 기억 (정류장 하나 지나는 데 보통 1분 안팎이라 짧게) */
    static final Duration KEEP_LOCATION = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(BusService.class);
    private static final Comparator<BusArrival> SOONEST = Comparator.comparing(BusArrival::remainMin,
            Comparator.nullsLast(Comparator.naturalOrder()));

    private final BusStopRepository stops;
    private final GjBisClient bis;
    private final Clock clock;
    private final ExecutorService pool;
    private final Map<Integer, Saved> saved = new ConcurrentHashMap<>();
    private final Map<Integer, SavedLine> savedLines = new ConcurrentHashMap<>();

    @Autowired
    public BusService(BusStopRepository stops, GjBisClient bis, Clock clock) {
        this(stops, bis, clock, Executors.newFixedThreadPool(MAX_STOPS, r -> {
            Thread t = new Thread(r, "bus-arrive");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 테스트용 입구: 일꾼 줄(pool)을 바꿔 끼운다 */
    BusService(BusStopRepository stops, GjBisClient bis, Clock clock, ExecutorService pool) {
        this.stops = stops;
        this.bis = bis;
        this.clock = clock;
        this.pool = pool;
    }

    /**
     * @throws org.springframework.dao.DataAccessException DB가 고장 났을 때 (창구가 503으로 바꾼다)
     */
    public BusNearbyResponse nearby(double lat, double lon) {
        List<BusStop> found = stops.near(lat, lon, RADIUS_M, MAX_STOPS);
        List<CompletableFuture<StopArrivals>> asks = new ArrayList<>();
        for (BusStop s : found) {
            asks.add(ask(s));
        }
        List<StopArrivals> out = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            out.add(await(asks.get(i), found.get(i)));
        }
        return new BusNearbyResponse(out);
    }

    private CompletableFuture<StopArrivals> ask(BusStop s) {
        Saved kept = saved.get(s.id());
        if (kept != null && clock.instant().isBefore(kept.at().plus(KEEP))) {
            return CompletableFuture.completedFuture(withArrivals(s, true, kept.arrivals()));
        }
        return CompletableFuture.supplyAsync(() -> {
            List<BusArrival> list = new ArrayList<>(bis.arrivals(s.id()));
            list.sort(SOONEST);
            saved.put(s.id(), new Saved(List.copyOf(list), clock.instant()));
            return withArrivals(s, true, list);
        }, pool).orTimeout(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static StopArrivals await(CompletableFuture<StopArrivals> f, BusStop s) {
        try {
            return f.join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("버스 도착 정보를 못 받았어요 (정류장 {}): {}", s.id(), cause.getClass().getSimpleName());
            return withArrivals(s, false, List.of());
        }
    }

    private static StopArrivals withArrivals(BusStop s, boolean available, List<BusArrival> arrivals) {
        return new StopArrivals(s.id(), s.name(), s.nextStop(), s.lat(), s.lon(), s.distanceM(), available,
                arrivals);
    }

    /**
     * 한 노선 위 버스들이 지금 있는 정류장과 그 좌표. 광주버스정보가 실패하면 available=false + 빈 목록.
     *
     * @throws org.springframework.dao.DataAccessException DB가 고장 났을 때 (창구가 503으로 바꾼다)
     */
    public BusLocationResponse locations(int lineId) {
        SavedLine kept = savedLines.get(lineId);
        if (kept != null && clock.instant().isBefore(kept.at().plus(KEEP_LOCATION))) {
            return kept.value();
        }
        List<GjBisClient.BusOnLine> buses;
        try {
            buses = bis.locations(lineId);
        } catch (BusUnavailableException e) {
            log.warn("버스 위치를 못 받았어요 (노선 {}): {}", lineId, e.getMessage());
            return new BusLocationResponse(lineId, false, List.of());
        }
        Set<Integer> stopIds = new LinkedHashSet<>();
        for (GjBisClient.BusOnLine b : buses) {
            stopIds.add(b.stopId());
        }
        Map<Integer, BusStop> where = stops.byIds(stopIds);
        List<BusLocation> out = new ArrayList<>();
        for (GjBisClient.BusOnLine b : buses) {
            BusStop s = where.get(b.stopId());
            out.add(new BusLocation(b.busId(), b.lowFloor(), b.stopId(), s == null ? null : s.name(),
                    s == null ? null : s.lat(), s == null ? null : s.lon(), b.seq()));
        }
        out.sort(Comparator.comparingInt(BusLocation::seq));
        BusLocationResponse res = new BusLocationResponse(lineId, true, List.copyOf(out));
        savedLines.put(lineId, new SavedLine(res, clock.instant()));
        return res;
    }

    private record SavedLine(BusLocationResponse value, Instant at) {
    }

    private record Saved(List<BusArrival> arrivals, Instant at) {
    }
}
