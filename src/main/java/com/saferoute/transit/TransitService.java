package com.saferoute.transit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.saferoute.bus.BusArrival;
import com.saferoute.bus.BusService;
import com.saferoute.bus.StopArrivals;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.subway.StationTrains;
import com.saferoute.subway.SubwayService;
import com.saferoute.subway.SubwayTrain;

/**
 * 대중교통 길찾기 (FR-37).
 * 1) ODsay 로 버스·지하철 경로를 받는다 (같은 출발·도착은 30분 기억해서 하루 한도를 아낀다)
 * 2) 휠체어 모드면 엘리베이터로 못 들어가는 역(예: 양동시장, 녹동)을 지나는 경로를 뺀다
 * 3) 걷는 구간을 우리 /api/route 맞춤 경로로 바꾼다 (결정 26)
 * 4) 버스 구간엔 광주 버스 도착 정보, 지하철 구간엔 시간표 다음 열차를 붙인다
 * 출발·도착 좌표는 저장하지 않고 로그에도 남기지 않는다. 기억해 두는 곳은 서버 메모리뿐이고 30분 뒤 사라진다.
 */
@Service
public class TransitService {

    private static final Logger log = LoggerFactory.getLogger(TransitService.class);

    static final int MAX_PATHS = 3;
    static final Duration CACHE_TTL = Duration.ofMinutes(30);
    static final int CACHE_MAX = 200;
    static final int MAX_BUSES = 3;
    static final int MAX_TRAINS = 2;
    static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");

    private final OdsayClient odsay;
    private final RouteService route;
    private final BusService bus;
    private final SubwayService subway;
    private final Clock clock;

    private record Cached(List<OdsayPath> paths, Instant at) {
    }

    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_MAX;
        }
    };

    public TransitService(OdsayClient odsay, RouteService route, BusService bus, SubwayService subway, Clock clock) {
        this.odsay = odsay;
        this.route = route;
        this.bus = bus;
        this.subway = subway;
        this.clock = clock;
    }

    public TransitResult search(RouteRequest req) {
        if (req == null) {
            throw TransitException.bad("요청 내용이 비어 있어요");
        }
        RouteRequest.Place from = checkPlace(req.origin(), "origin");
        RouteRequest.Place to = checkPlace(req.destination(), "destination");
        String mode = mode(req.mode());

        List<OdsayPath> all = paths(from, to);
        Lookups look = new Lookups(req.guestId());
        List<OdsayPath> usable = new ArrayList<>();
        int hidden = 0;
        for (OdsayPath p : all) {
            if ("wheelchair".equals(mode) && !wheelchairOk(p, look)) {
                hidden++;
            } else {
                usable.add(p);
            }
        }
        List<TransitResult.TransitPath> out = new ArrayList<>();
        for (OdsayPath p : usable.subList(0, Math.min(MAX_PATHS, usable.size()))) {
            out.add(build(p, from, to, req.night(), mode, look));
        }
        String note = null;
        if (out.isEmpty() && hidden > 0) {
            note = "엘리베이터로 들어갈 수 없는 역을 지나는 길만 있어요";
        } else if (hidden > 0) {
            note = "엘리베이터로 들어갈 수 없는 역을 지나는 길 " + hidden + "개는 뺐어요";
        }
        return new TransitResult(out, hidden, note);
    }

    // ---------- 1) ODsay + 기억 ----------

    private List<OdsayPath> paths(RouteRequest.Place from, RouteRequest.Place to) {
        // 소수 셋째 자리(약 100m)로 묶는다. 걷는 구간은 실제 좌표로 다시 계산하므로 조금 어긋나도 괜찮다
        String key = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", from.lat(), from.lon(), to.lat(), to.lon());
        Instant now = clock.instant();
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && c.at().plus(CACHE_TTL).isAfter(now)) {
                return c.paths();
            }
        }
        List<OdsayPath> fresh = odsay.search(from.lat(), from.lon(), to.lat(), to.lon());
        synchronized (cache) {
            cache.put(key, new Cached(fresh, now));
        }
        return fresh;
    }

    // ---------- 2) 휠체어 ----------

    /** 지하철 구간의 타는 역·내리는 역 중 엘리베이터로 못 들어가는 역(wheelchairEntry=false)이 있으면 false. 모르면 통과 */
    private boolean wheelchairOk(OdsayPath p, Lookups look) {
        for (OdsayPath.Seg s : p.segs()) {
            if (s.type() != OdsayPath.SUBWAY) {
                continue;
            }
            for (OdsayPath.Place at : new OdsayPath.Place[] {s.start(), s.end()}) {
                StationTrains st = look.station(at).orElse(null);
                if (st != null && st.elevatorExits() != null
                        && Boolean.FALSE.equals(st.elevatorExits().wheelchairEntry())) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------- 3), 4) 구간 만들기 ----------

    private TransitResult.TransitPath build(OdsayPath p, RouteRequest.Place from, RouteRequest.Place to,
                                            String night, String mode, Lookups look) {
        List<OdsayPath.Seg> segs = p.segs();
        List<TransitResult.Leg> legs = new ArrayList<>();
        int odsayWalkMin = 0;
        int ourWalkMin = 0;
        int walkM = 0;
        int rides = 0;
        for (int i = 0; i < segs.size(); i++) {
            OdsayPath.Seg s = segs.get(i);
            if (s.type() == OdsayPath.WALK) {
                TransitResult.Stop a = i == 0 ? stop(from) : stop(segs.get(i - 1).end());
                TransitResult.Stop b = i == segs.size() - 1 ? stop(to) : stop(segs.get(i + 1).start());
                if (a == null || b == null || s.distanceM() <= 0 && i > 0 && i < segs.size() - 1) {
                    odsayWalkMin += s.minutes();
                    continue; // 같은 역 안에서 갈아타기 등 걷지 않는 구간
                }
                TransitResult.Leg leg = walk(s, a, b, night, mode, look);
                odsayWalkMin += s.minutes();
                ourWalkMin += leg.minutes();
                walkM += leg.distanceM();
                legs.add(leg);
            } else if (s.type() == OdsayPath.BUS || s.type() == OdsayPath.SUBWAY) {
                rides++;
                legs.add(s.type() == OdsayPath.BUS ? busLeg(s, look) : subwayLeg(s, look));
            }
        }
        int total = Math.max(1, p.totalMin() - odsayWalkMin + ourWalkMin);
        return new TransitResult.TransitPath(total, p.fare(), Math.max(0, rides - 1), walkM, List.copyOf(legs));
    }

    private TransitResult.Leg walk(OdsayPath.Seg s, TransitResult.Stop a, TransitResult.Stop b, String night,
                                   String mode, Lookups look) {
        RouteResult.Route safe = look.walk(a, b, night, mode);
        if (safe == null) {
            return new TransitResult.Leg("walk", s.distanceM(), s.minutes(), a, b, List.of(), null, null,
                    false, null, null, null, null, null);
        }
        return new TransitResult.Leg("walk", safe.distanceM(), safe.durationMin(), a, b, List.of(), null, null,
                true, safe.geometry(), safe.sidewalks(), null, null, null);
    }

    private TransitResult.Leg busLeg(OdsayPath.Seg s, Lookups look) {
        Optional<StopArrivals> stop = look.busStop(s.start(), s.lines());
        List<BusArrival> buses = stop.map(st -> st.arrivals().stream()
                .filter(a -> a.lineName() != null && s.lines().contains(a.lineName().trim()))
                .sorted(Comparator.comparing(a -> a.remainMin() == null ? Integer.MAX_VALUE : a.remainMin()))
                .limit(MAX_BUSES).toList()).orElse(List.of());
        boolean live = stop.map(StopArrivals::available).orElse(false);
        return new TransitResult.Leg("bus", s.distanceM(), s.minutes(), stop(s.start()), stop(s.end()), s.lines(),
                null, s.stationCount(), null, null, null, live, buses, null, stops(s));
    }

    private TransitResult.Leg subwayLeg(OdsayPath.Seg s, Lookups look) {
        Optional<StationTrains> st = look.station(s.start());
        List<SubwayTrain> trains = st.map(x -> trainsToward(x.trains(), s.way())).orElse(List.of());
        return new TransitResult.Leg("subway", s.distanceM(), s.minutes(), stop(s.start()), stop(s.end()), s.lines(),
                s.way(), s.stationCount(), null, null, null, st.isPresent(), null, trains, stops(s));
    }

    /** 지나는 정류장·역을 앱에 줄 모양으로 */
    private static List<TransitResult.Stop> stops(OdsayPath.Seg s) {
        return s.stops().stream().map(p -> new TransitResult.Stop(p.name(), p.lat(), p.lon())).toList();
    }

    /** ODsay 방향(예: 녹동)과 맞는 열차만. 방향을 모르거나 맞는 게 없으면 모든 방향 */
    static List<SubwayTrain> trainsToward(List<SubwayTrain> trains, String way) {
        if (trains == null) {
            return List.of();
        }
        if (way != null && !way.isBlank()) {
            String w = way.replace("방면", "").trim();
            List<SubwayTrain> match = trains.stream()
                    .filter(t -> t.direction() != null && t.direction().startsWith(w)).limit(MAX_TRAINS).toList();
            if (!match.isEmpty()) {
                return match;
            }
        }
        return trains.stream().limit(MAX_TRAINS * 2L).toList();
    }

    // ---------- 한 요청 안에서 같은 조회를 되풀이하지 않게 ----------

    /** 한 요청 안에서만 쓰는 기억 (같은 역·정류장·걷는 길을 여러 경로가 같이 씀) */
    private final class Lookups {
        /** 걷는 구간 길찾기에 넘기는 게스트 번호 (있으면 그 사람의 계단·우회·쉼 간격 선호가 들어감). 없으면 null */
        private final String guestId;
        private final Map<String, Optional<StationTrains>> stations = new HashMap<>();
        private final Map<String, Optional<StopArrivals>> stops = new HashMap<>();
        private final Map<String, Optional<RouteResult.Route>> walks = new HashMap<>();

        Lookups(String guestId) {
            this.guestId = guestId == null || guestId.isBlank() ? null : guestId;
        }

        Optional<StationTrains> station(OdsayPath.Place at) {
            if (at == null) {
                return Optional.empty();
            }
            return stations.computeIfAbsent(key(at.lat(), at.lon()) + at.name(), k -> {
                try {
                    List<StationTrains> near = subway.nearby(at.lat(), at.lon()).stations();
                    return pick(near, at.name(), StationTrains::name, StationTrains::distanceM);
                } catch (RuntimeException e) {
                    log.warn("대중교통: 지하철 시간표를 못 붙임 {}", e.getClass().getSimpleName());
                    return Optional.empty();
                }
            });
        }

        Optional<StopArrivals> busStop(OdsayPath.Place at, List<String> lines) {
            if (at == null) {
                return Optional.empty();
            }
            return stops.computeIfAbsent(key(at.lat(), at.lon()) + at.name(), k -> {
                try {
                    List<StopArrivals> near = bus.nearby(at.lat(), at.lon()).stops();
                    // 같은 이름이 길 건너에도 있어서, 탈 버스가 오는 정류장을 먼저 고른다
                    List<StopArrivals> withLine = near.stream()
                            .filter(st -> st.arrivals().stream()
                                    .anyMatch(a -> a.lineName() != null && lines.contains(a.lineName().trim())))
                            .toList();
                    Optional<StopArrivals> hit = pick(withLine, at.name(), StopArrivals::name, StopArrivals::distanceM);
                    return hit.isPresent() ? hit : pick(near, at.name(), StopArrivals::name, StopArrivals::distanceM);
                } catch (RuntimeException e) {
                    log.warn("대중교통: 버스 도착 정보를 못 붙임 {}", e.getClass().getSimpleName());
                    return Optional.empty();
                }
            });
        }

        RouteResult.Route walk(TransitResult.Stop a, TransitResult.Stop b, String night, String mode) {
            String k = key(a.lat(), a.lon()) + ">" + key(b.lat(), b.lon());
            return walks.computeIfAbsent(k, x -> {
                try {
                    RouteResult r = route.route(new RouteRequest(new RouteRequest.Place(a.lat(), a.lon(), a.name()),
                            new RouteRequest.Place(b.lat(), b.lon(), b.name()), night, mode, guestId));
                    // 맞춤 경로는 "safe" (최단과 같으면 하나만 옴)
                    return r.routes().stream().filter(rt -> "safe".equals(rt.kind())).findFirst()
                            .or(() -> r.routes().stream().findFirst());
                } catch (RouteException e) {
                    // 너무 가까움(SAME_POINT)·길 없음 등: ODsay 걷기 값 그대로 쓴다
                    return Optional.empty();
                }
            }).orElse(null);
        }
    }

    /** 이름이 같은 것 중 가장 가까운 것, 없으면 그냥 가장 가까운 것 (같은 좌표에서 찾았으므로 대개 첫 번째) */
    static <T> Optional<T> pick(List<T> list, String name, java.util.function.Function<T, String> nameOf,
                                java.util.function.ToIntFunction<T> dist) {
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        String want = norm(name);
        Comparator<T> near = Comparator.comparingInt(dist);
        return list.stream().filter(x -> !want.isEmpty() && norm(nameOf.apply(x)).equals(want)).min(near)
                .or(() -> list.stream().min(near));
    }

    /** "문화전당역" = "문화전당", 띄어쓰기 무시 */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace(" ", "").trim();
        return t.endsWith("역") ? t.substring(0, t.length() - 1) : t;
    }

    private static String key(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);
    }

    private static TransitResult.Stop stop(OdsayPath.Place p) {
        return p == null ? null : new TransitResult.Stop(p.name(), p.lat(), p.lon());
    }

    private static TransitResult.Stop stop(RouteRequest.Place p) {
        return new TransitResult.Stop(p.name(), p.lat(), p.lon());
    }

    private static RouteRequest.Place checkPlace(RouteRequest.Place p, String name) {
        if (p == null || p.lat() == null || p.lon() == null) {
            throw TransitException.bad(name + " 의 lat, lon 이 필요해요");
        }
        if (p.lat() < 33 || p.lat() > 39 || p.lon() < 124 || p.lon() > 132) {
            throw TransitException.bad(name + " 좌표가 우리나라 밖이에요");
        }
        return p;
    }

    /** 비면 adult (길찾기와 같음) */
    private static String mode(String m) {
        String key = m == null || m.isBlank() ? "adult" : m.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(key)) {
            throw TransitException.bad("mode 는 adult, child, senior, wheelchair 중 하나여야 해요");
        }
        return key;
    }
}
