package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.PlaceSearchException;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteResult;
import com.saferoute.walk.WalkSpeed;
import com.saferoute.walk.WalkSpeedService;

/**
 * 말로 들를 곳 묶기, 걷기 (11-09 결정 ①, 11-10).
 * 1) 들를 곳마다 카카오에서 후보 3곳 (출발과 도착 사이 가운데에서 가까운 순)
 * 2) 순서까지 바꿔 보며 곧은 거리 합이 가장 작은 조합을 고른다 (3곳이어도 6순서 × 27조합 = 162가지라 바로 끝남)
 * 3) 고른 조합만 실제 길을 찾는다 (원일님 엔진, 구간마다). 바로 가는 길도 한 번 찾아 "N분 더 걸려요"를 센다
 * 사용자가 순서를 말했으면 그 순서 그대로. 안 말했고 순서를 바꿨으면 말한 순서의 길도 찾아 "N분 덜 걸려요"를 센다.
 * 좌표는 저장하지 않는다.
 */
@Component
public class StopPlanner {

    private static final Logger log = LoggerFactory.getLogger(StopPlanner.class);
    static final int CANDIDATES = 3;

    private final KakaoLocalClient kakao;
    private final RouteService routes;
    private final WalkSpeedService walkSpeed;

    public StopPlanner(KakaoLocalClient kakao, RouteService routes, WalkSpeedService walkSpeed) {
        this.kakao = kakao;
        this.routes = routes;
        this.walkSpeed = walkSpeed;
    }

    /** 들르는 곳 하나 (label 은 사용자가 말한 것 "택배", name 은 찾은 가게 "GS25 충장점") */
    public record Stop(String label, String name, String address, double lat, double lon) {
    }

    /** 걷는 구간 하나 (출발 → 첫 들를 곳 → … → 도착) */
    public record Leg(String to, int distanceM, int minutes) {
    }

    /**
     * 결과.
     *
     * @param stops     고른 들를 곳 (가는 차례대로)
     * @param legs      구간별 거리·시간
     * @param minutes   다 합친 시간(분). 걸음 속도를 잰 적이 있으면 내 속도로 셈
     * @param distanceM 다 합친 거리(m)
     * @param directMin 들르지 않고 바로 갈 때 시간. 못 셌으면 null
     * @param savedMin  말한 순서 대신 바꾼 순서로 가서 줄어든 시간. 순서를 안 바꿨거나 줄지 않으면 null
     * @param request   [이 길로 출발] 때 앱이 보낼 값 (/api/route 모양 + stops 들를 곳 차례대로, 앱 feat/route-stops 와 같은 칸)
     * @param failText  못 찾았을 때 할 말. 찾았으면 null
     */
    public record Plan(List<Stop> stops, List<Leg> legs, int minutes, int distanceM, Integer directMin,
                       Integer savedMin, Map<String, Object> request, String failText) {

        static Plan fail(String text) {
            return new Plan(List.of(), List.of(), 0, 0, null, null, Map.of(), text);
        }
    }

    /** 무엇을 들를지 */
    public record Ask(String guestId, double lat, double lon, Conversation.Place dest, List<Conversation.Stop> stops,
                      boolean ordered, String mode, String night) {
    }

    public Plan plan(Ask a) {
        double midLat = (a.lat() + a.dest().lat()) / 2;
        double midLon = (a.lon() + a.dest().lon()) / 2;
        // 1) 후보
        List<List<Stop>> cands = new ArrayList<>();
        for (Conversation.Stop s : a.stops()) {
            List<Stop> c = new ArrayList<>();
            try {
                for (com.saferoute.place.Place p : kakao.search(s.query(), midLat, midLon)) {
                    if (AssistantTools.inGwangju(new Conversation.Place(p.name(), null, p.lat(), p.lon()))) {
                        c.add(new Stop(s.label(), p.name(), p.address(), p.lat(), p.lon()));
                    }
                    if (c.size() == CANDIDATES) {
                        break;
                    }
                }
            } catch (PlaceSearchException e) {
                log.warn("들를 곳 검색 실패: {}", e.getMessage());
                return Plan.fail("지금은 장소 검색이 잘 안 돼요. 잠시 뒤에 다시 말씀해 주세요.");
            }
            if (c.isEmpty()) {
                return Plan.fail("가는 길에 들를 '" + s.label() + "'을(를) 찾지 못했어요. 다른 이름으로 말씀해 주시겠어요?");
            }
            cands.add(c);
        }
        // 2) 곧은 거리로 고르기
        List<Stop> best = shortest(a.lat(), a.lon(), a.dest(), cands, !a.ordered());
        List<Stop> spoken = a.ordered() ? best : shortest(a.lat(), a.lon(), a.dest(), cands, false);

        // 3) 실제 길 (고른 조합만)
        WalkSpeed speed = speed(a.guestId(), a.mode());
        Walked w;
        try {
            w = walk(a, best, speed);
        } catch (RouteException e) {
            return Plan.fail(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("들를 곳 길 찾기 실패: {}", e.getClass().getSimpleName());
            return Plan.fail("지금은 길을 찾을 수 없어요.");
        }
        Integer direct = null;
        try {
            direct = walk(a, List.of(), speed).minutes();
        } catch (RuntimeException e) {
            log.info("바로 가는 길 못 셈: {}", e.getClass().getSimpleName());
        }
        Integer saved = null;
        if (!sameOrder(best, spoken)) {
            try {
                int m = walk(a, spoken, speed).minutes();
                saved = m > w.minutes() ? m - w.minutes() : null;
            } catch (RuntimeException e) {
                log.info("말한 순서 길 못 셈: {}", e.getClass().getSimpleName());
            }
        }
        RouteRequest req = new RouteRequest(new RouteRequest.Place(a.lat(), a.lon(), "지금 위치"),
                new RouteRequest.Place(a.dest().lat(), a.dest().lon(), a.dest().name()), a.night(), a.mode(),
                TripRecommender.blankToNull(a.guestId()));
        Map<String, Object> request = new LinkedHashMap<>(TripRecommender.request(req));
        List<Map<String, Object>> via = new ArrayList<>();
        for (Stop s : best) {
            via.add(Map.of("lat", s.lat(), "lon", s.lon(), "name", s.name()));
        }
        request.put("stops", List.copyOf(via));
        return new Plan(best, w.legs(), w.minutes(), w.distanceM(), direct, saved, request, null);
    }

    /**
     * 출발 → 들를 곳들 → 도착 곧은 거리 합이 가장 작은 조합. reorder 가 false 면 말한 순서 그대로 후보만 고른다.
     */
    static List<Stop> shortest(double lat, double lon, Conversation.Place dest, List<List<Stop>> cands,
            boolean reorder) {
        int n = cands.size();
        List<int[]> orders = new ArrayList<>();
        permute(new int[n], new boolean[n], 0, orders, reorder);
        List<Stop> best = null;
        double bestM = Double.MAX_VALUE;
        for (int[] order : orders) {
            int[] pick = new int[n];
            while (true) {
                List<Stop> route = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    route.add(cands.get(order[i]).get(pick[i]));
                }
                double m = length(lat, lon, dest, route);
                if (m < bestM - 0.5) { // 같으면 먼저 본 것 (말한 순서, 가까운 후보)
                    bestM = m;
                    best = route;
                }
                int i = n - 1;
                while (i >= 0 && ++pick[i] >= cands.get(order[i]).size()) {
                    pick[i] = 0;
                    i--;
                }
                if (i < 0) {
                    break;
                }
            }
        }
        return best == null ? List.of() : List.copyOf(best);
    }

    private static void permute(int[] cur, boolean[] used, int at, List<int[]> out, boolean reorder) {
        if (!reorder) {
            int[] id = new int[cur.length];
            for (int i = 0; i < id.length; i++) {
                id[i] = i;
            }
            out.add(id);
            return;
        }
        if (at == cur.length) {
            out.add(cur.clone());
            return;
        }
        for (int i = 0; i < cur.length; i++) {
            if (!used[i]) {
                used[i] = true;
                cur[at] = i;
                permute(cur, used, at + 1, out, true);
                used[i] = false;
            }
        }
    }

    static double length(double lat, double lon, Conversation.Place dest, List<Stop> stops) {
        double m = 0;
        double fLat = lat;
        double fLon = lon;
        for (Stop s : stops) {
            m += AssistantTools.straightM(fLat, fLon, s.lat(), s.lon());
            fLat = s.lat();
            fLon = s.lon();
        }
        return m + AssistantTools.straightM(fLat, fLon, dest.lat(), dest.lon());
    }

    private static boolean sameOrder(List<Stop> a, List<Stop> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).label().equals(b.get(i).label())) {
                return false;
            }
        }
        return true;
    }

    private record Walked(List<Leg> legs, int minutes, int distanceM) {
    }

    /**
     * 구간마다 원일님 엔진으로 걷는 길을 찾아 더한다 (구간마다 맞춤 길 safe, 없으면 첫 길).
     * 엔진에 경유지(w10 via)가 들어오면 한 번 부르기로 바꾼다.
     */
    private Walked walk(Ask a, List<Stop> stops, WalkSpeed speed) {
        List<Leg> legs = new ArrayList<>();
        int minutes = 0;
        int meters = 0;
        double fLat = a.lat();
        double fLon = a.lon();
        String fName = "지금 위치";
        List<double[]> points = new ArrayList<>();
        for (Stop s : stops) {
            points.add(new double[] {s.lat(), s.lon()});
        }
        points.add(new double[] {a.dest().lat(), a.dest().lon()});
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            String toName = i < stops.size() ? stops.get(i).name() : a.dest().name();
            RouteResult r = routes.route(new RouteRequest(new RouteRequest.Place(fLat, fLon, fName),
                    new RouteRequest.Place(p[0], p[1], toName), a.night(), a.mode(),
                    TripRecommender.blankToNull(a.guestId())));
            RouteResult.Route pick = r.routes().stream().filter(x -> "safe".equals(x.kind())).findFirst()
                    .orElse(r.routes().get(0));
            boolean personal = speed != null && speed.personal() && speed.speedMMin() > 0;
            int m = personal ? (int) Math.ceil(pick.distanceM() / speed.speedMMin()) : pick.durationMin();
            legs.add(new Leg(toName, pick.distanceM(), m));
            minutes += m;
            meters += pick.distanceM();
            fLat = p[0];
            fLon = p[1];
            fName = toName;
        }
        return new Walked(List.copyOf(legs), minutes, meters);
    }

    private WalkSpeed speed(String guestId, String mode) {
        if (guestId == null || guestId.isBlank()) {
            return null;
        }
        try {
            return walkSpeed.get(guestId, mode);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
