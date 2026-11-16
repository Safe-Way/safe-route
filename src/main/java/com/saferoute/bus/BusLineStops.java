package com.saferoute.bus;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노선마다 지나는 정류장 (11-13 "수완동으로 가는 버스"). bus_line 표에는 기점·종점만 있어서,
 * 광주버스정보 lineStationInfo로 미리 받아 둔 파일(resources/bus/line_stops.json)을 서버가 켜질 때 읽는다.
 * 서버는 이 API를 부르지 않는다: 하루 부를 수 있는 횟수가 적어서(11-13 터미널에서 한도 초과 확인)
 * 배포마다 127번씩 부르면 버스 도착 정보까지 막힐 수 있다. 파일은 노선이 바뀔 때 scripts/fetch_line_stops.py로 다시 만든다.
 * 파일이 없으면 지나는 버스는 비어 있고, 기점·종점으로만 찾는다.
 */
@Component
public class BusLineStops {

    private static final Logger log = LoggerFactory.getLogger(BusLineStops.class);

    static final String FILE = "bus/line_stops.json";
    /** 말한 곳에서 이 거리 안에 정류장이 있으면 그 노선이 "지나간다"고 본다 */
    static final int NEAR_M = 400;
    /** NEAR_M 안에 하나도 없으면 한 번 더 넓혀 본다 (긴 거리·넓은 동네 이름) */
    static final int WIDER_M = 800;
    /** 돌려줄 노선 수 */
    static final int MAX = 5;

    private final BusLineRepository lines;
    private final Map<Integer, List<LineStop>> stops;
    private final String fetchedAt;

    @Autowired
    public BusLineStops(BusLineRepository lines) {
        this(lines, read(FILE));
    }

    BusLineStops(BusLineRepository lines, Loaded loaded) {
        this.lines = lines;
        this.stops = loaded.stops();
        this.fetchedAt = loaded.fetchedAt();
    }

    /** 노선 하나와, 말한 곳 쪽에서 지나는 정류장 (기점·종점으로만 찾았으면 null) */
    public record Hit(BusLine line, LineStop via) {
    }

    record Loaded(Map<Integer, List<LineStop>> stops, String fetchedAt) {
    }

    /**
     * {"fetchedAt":"2026-11-14","lines":{"28":[[순서, 정류장번호, "이름", 위도, 경도], …], …}}
     * 를 읽는다. 없거나 깨졌으면 빈 것
     */
    static Loaded read(String path) {
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            log.info("노선 정류장 파일 없음 ({}): 지나가는 버스 없이 기점·종점으로만 찾음", path);
            return new Loaded(Map.of(), null);
        }
        try (InputStream in = res.getInputStream()) {
            Loaded l = parse(JsonMapper.builder().build().readTree(in));
            log.info("노선 정류장 파일 읽음: 노선 {}개 ({} 받음)", l.stops().size(), l.fetchedAt());
            return l;
        } catch (IOException | RuntimeException e) {
            log.warn("노선 정류장 파일 읽기 실패: {}", e.getMessage());
            return new Loaded(Map.of(), null);
        }
    }

    static Loaded parse(JsonNode root) {
        Map<Integer, List<LineStop>> out = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("lines").properties()) {
            List<LineStop> list = new ArrayList<>();
            for (JsonNode s : e.getValue()) {
                if (s.size() >= 5 && s.get(3).isNumber() && s.get(4).isNumber()) {
                    list.add(new LineStop(s.get(0).asInt(), s.get(1).asInt(), s.get(2).asString(""),
                            s.get(3).asDouble(), s.get(4).asDouble()));
                }
            }
            out.put(Integer.valueOf(e.getKey()), List.copyOf(list));
        }
        String at = root.path("fetchedAt").asString("");
        return new Loaded(Map.copyOf(out), at.isEmpty() ? null : at);
    }

    /** 읽어 둔 노선 수 (0이면 파일 없음) */
    public int loaded() {
        return stops.size();
    }

    /** 그 노선이 서는 정류장 (순서대로). 파일에 없으면 빈 목록 */
    public List<LineStop> stopsOf(int lineId) {
        return stops.getOrDefault(lineId, List.of());
    }

    /**
     * 노선도 (11-13): 기점에서 종점(to)까지 가는 쪽 정류장만. 파일 목록은 갔다가 돌아오는 길까지 이어져 있어서
     * 처음으로 종점 이름이 나오는 곳에서 자른다. 종점 이름을 못 찾으면 전부
     */
    public List<LineStop> route(int lineId, String to) {
        List<LineStop> all = stopsOf(lineId);
        if (to != null && !to.isBlank()) {
            String end = to.replaceAll("\\s+", "");
            for (int i = 1; i < all.size(); i++) {
                if (all.get(i).name().replaceAll("\\s+", "").startsWith(end)) {
                    return all.subList(0, i + 1);
                }
            }
        }
        return all;
    }

    /** 그 노선이 서는 정류장 중 (lat, lon)에서 가장 가까운 곳. 없으면 null */
    public LineStop nearestStop(int lineId, double lat, double lon) {
        LineStop best = null;
        double bestM = Double.MAX_VALUE;
        for (LineStop s : stopsOf(lineId)) {
            double m = meters(lat, lon, s.lat(), s.lon());
            if (m < bestM) {
                bestM = m;
                best = s;
            }
        }
        return best;
    }

    /** 파일을 받은 날 ("2026-11-14"). 모르면 null */
    public String fetchedAt() {
        return fetchedAt;
    }

    /**
     * place 로 가는 버스. ① 그곳(lat·lon) 400m 안(없으면 800m)이나 이름이 place 로 시작하는 정류장을 지나는 노선 (가까운 순)
     * ② 그다음 기점·종점 이름에 place 가 든 노선. 최대 5개.
     *
     * @param place 말한 곳 ("수완동"이면 "수완"으로 줄여 이름을 본다)
     * @param lat   그곳 좌표 (모르면 null → 이름으로만)
     * @throws org.springframework.dao.DataAccessException 노선 표를 못 읽을 때
     */
    public List<Hit> to(String place, Double lat, Double lon) {
        String stem = com.saferoute.place.BusLineQuery.stem(place);
        Map<Integer, LineStop> via = new LinkedHashMap<>();
        Map<Integer, Double> dist = new HashMap<>();
        List<String> names = new ArrayList<>(com.saferoute.place.BusLineQuery.stopNames(place));
        names.add(stem);
        passing(names, lat, lon, NEAR_M, via, dist);
        if (via.isEmpty() && lat != null) {
            passing(names, lat, lon, WIDER_M, via, dist);
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

    /** radius 안이나 이름이 names 중 하나로 시작하는 정류장을 지나는 노선마다 가장 가까운 정류장 */
    private void passing(List<String> names, Double lat, Double lon, int radius, Map<Integer, LineStop> via,
                         Map<Integer, Double> dist) {
        for (Map.Entry<Integer, List<LineStop>> e : stops.entrySet()) {
            LineStop best = null;
            double bestM = Double.MAX_VALUE;
            for (LineStop s : e.getValue()) {
                double m = lat == null ? Double.MAX_VALUE : meters(lat, lon, s.lat(), s.lon());
                String bare = s.name().replaceAll("\\s+", "");
                boolean named = names.stream().anyMatch(bare::startsWith);
                if (m <= radius || named) {
                    double score = named ? Math.min(m, radius) : m;
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
    }

    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
