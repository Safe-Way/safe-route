package com.saferoute.bus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 노선마다 지나는 정류장 (11-13 "수완동으로 가는 버스"). bus_line 표에는 기점·종점만 있어서,
 * 광주버스정보 lineStationInfo를 노선마다(127개) 한 번씩 불러 기억해 둔다.
 * 서버가 켜지면 뒤에서 한 번, 그 뒤로 매일 새벽 4시 10분(한국 시각)에 다시 받는다. DB에는 저장하지 않는다.
 * 다 받기 전이나 받기에 실패하면 지나는 버스는 비어 있고, 기점·종점으로만 찾는다.
 */
@Component
@EnableScheduling
public class BusLineStops {

    private static final Logger log = LoggerFactory.getLogger(BusLineStops.class);

    /** 말한 곳에서 이 거리 안에 정류장이 있으면 그 노선이 "지나간다"고 본다 */
    static final int NEAR_M = 400;
    /** 돌려줄 노선 수 */
    static final int MAX = 5;

    private final GjBisClient bis;
    private final BusLineRepository lines;
    private final boolean loadOnStart;

    private volatile Map<Integer, List<LineStop>> stops = Map.of();

    public BusLineStops(GjBisClient bis, BusLineRepository lines,
                        @Value("${bus.line-stops.load:true}") boolean loadOnStart) {
        this.bis = bis;
        this.lines = lines;
        this.loadOnStart = loadOnStart;
    }

    /** 노선 하나와, 말한 곳 쪽에서 지나는 정류장 (기점·종점으로만 찾았으면 null) */
    public record Hit(BusLine line, LineStop via) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadInBackground() {
        if (loadOnStart) {
            Thread t = new Thread(this::reload, "bus-line-stops");
            t.setDaemon(true);
            t.start();
        }
    }

    @Scheduled(cron = "${bus.line-stops.cron:0 10 4 * * *}", zone = "Asia/Seoul")
    public void reload() {
        try {
            List<Integer> ids = lines.allIds();
            Map<Integer, List<LineStop>> fresh = new HashMap<>();
            int failed = 0;
            for (int id : ids) {
                try {
                    fresh.put(id, List.copyOf(bis.lineStops(id)));
                } catch (BusUnavailableException e) {
                    failed++;
                    if (failed == 3 && fresh.isEmpty()) {
                        log.warn("노선 정류장 받기 멈춤 (처음 3개 모두 실패): {}", e.getMessage());
                        return; // 키가 없거나 서비스가 막힘 → 옛 기억 그대로
                    }
                }
            }
            if (!fresh.isEmpty()) {
                stops = Map.copyOf(fresh);
            }
            log.info("노선 정류장 받음: 노선 {}개 중 {}개 (실패 {})", ids.size(), fresh.size(), failed);
        } catch (DataAccessException e) {
            log.warn("노선 정류장 받기 실패 (노선 표): {}", e.getMostSpecificCause().getMessage());
        }
    }

    /** 테스트에서 받아 온 셈 치고 넣는다 */
    void put(Map<Integer, List<LineStop>> s) {
        stops = Map.copyOf(s);
    }

    /** 받아 둔 노선 수 (0이면 아직 못 받음) */
    public int loaded() {
        return stops.size();
    }

    /**
     * place 로 가는 버스. ① 그곳(lat·lon) 400m 안이나 이름이 place 로 시작하는 정류장을 지나는 노선 (가까운 순)
     * ② 그다음 기점·종점 이름에 place 가 든 노선. 최대 5개.
     *
     * @param place 말한 곳 ("수완동"이면 "수완"으로 줄여 이름을 본다)
     * @param lat   그곳 좌표 (모르면 null → 이름으로만)
     * @throws DataAccessException 노선 표를 못 읽을 때
     */
    public List<Hit> to(String place, Double lat, Double lon) {
        String stem = com.saferoute.place.BusLineQuery.stem(place);
        Map<Integer, LineStop> via = new LinkedHashMap<>();
        Map<Integer, Double> dist = new HashMap<>();
        for (Map.Entry<Integer, List<LineStop>> e : stops.entrySet()) {
            LineStop best = null;
            double bestM = Double.MAX_VALUE;
            for (LineStop s : e.getValue()) {
                double m = lat == null ? Double.MAX_VALUE : meters(lat, lon, s.lat(), s.lon());
                boolean named = s.name().replaceAll("\\s+", "").startsWith(stem);
                if (m <= NEAR_M || named) {
                    double score = named ? Math.min(m, NEAR_M) : m;
                    if (score < bestM) {
                        bestM = score;
                        best = s;
                    }
                }
            }
            if (best != null) {
                via.put(e.getKey(), best);
                dist.put(e.getKey(), bestM);
            }
        }
        List<Integer> passing = new ArrayList<>(via.keySet());
        passing.sort(Comparator.comparingDouble((Integer id) -> dist.get(id)).thenComparing(id -> id));
        List<Hit> out = new ArrayList<>();
        for (BusLine l : lines.byIds(passing.stream().limit(MAX).toList())) {
            out.add(new Hit(l, via.get(l.id())));
        }
        if (out.size() < MAX) {
            for (BusLine l : lines.endingAt(stem)) {
                if (out.size() < MAX && out.stream().noneMatch(h -> h.line().id() == l.id())) {
                    out.add(new Hit(l, null));
                }
            }
        }
        return out;
    }

    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
