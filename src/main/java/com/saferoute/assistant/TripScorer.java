package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.saferoute.bus.BusArrival;
import com.saferoute.route.RouteProperties;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.transit.TransitResult;

/**
 * 추천 규칙. 후보마다 점수를 매겨 1등과 이유를 정한다. AI는 여기에 끼지 않는다 (점수는 늘 이 규칙대로).
 * 점수는 "분"으로 센다: 걸리는 시간 + 부담(날씨 속 걷는 시간, 불편 신고, 갈아타기, 피할 행사·돌발, 엔진 기준 밖을 걷는 길). 낮을수록 좋다.
 * 팀 설계상 점수 규칙은 B(원일님)가 다듬는다 (2026-11-09 다듬음):
 * <ul>
 *   <li>엔진 기준: 추천기·대중교통 창구가 엔진에서 받아 실어 준 걷는 길(walkRoute)마다
 *       밤 어두운 길, 어린이·노인 낮 기준 밖 길이, 노인 계단, 휠체어 비포장을 부담으로 더한다. 버스·지하철 타는 동안은 안 셈</li>
 *   <li>대중교통 시간: 버스 타는 시간을 BIS 실시간(남은 분 ÷ 남은 정거장 × 구간 정거장 수)으로, 정류장에서 기다리는 시간도 더한다</li>
 *   <li>휠체어는 일반 버스 금지: 버스 구간마다 내가 그 정류장에 닿은 뒤(갈아타면 2분 넘게 뒤) 오는 저상버스가 있어야 한다. 실시간 목록을 못 받으면 빼지 않고 "확인할 수 없어요"</li>
 *   <li>비 오는 날 젖는 시간: 걷는 시간 중 엔진이 준 지붕 아래 구간(covered.roof, 지하 포함)을 뺀다 (2026-11-09)</li>
 * </ul>
 */
@Component
public class TripScorer {

    /** 불편 신고 한 건을 몇 분만큼 싫게 볼지 */
    static final int REPORT_MIN = 5;
    /** 갈아타기 한 번을 몇 분만큼 싫게 볼지 (어르신·휠체어는 두 배) */
    static final int TRANSFER_MIN = 5;
    /** 걷는 구간 시간을 모를 때(대중교통 구간 목록이 없을 때) 밖에 있는 시간을 셀 속도 (m/분) */
    static final int OUTSIDE_SPEED = 80;
    /** 엔진 기준 밖을 걷는 길이를 분으로 바꾸는 속도 (m/분, 진행표 11-09 "길이 ÷ 80") */
    static final int BURDEN_SPEED = 80;
    /** 노인이 피하지 못한 계단 한 곳의 부담(분) */
    static final int STAIRS_MIN = 3;
    /** 휠체어가 지나는 비포장·재질 모르는 길 100m의 부담(분) */
    static final int UNPAVED_MIN_PER_100M = 5;
    /** 갈아타는 버스는 내가 정류장에 닿고 이 분보다 뒤에 와야 탈 수 있다고 본다 (도착 시각 추정이 어긋날 수 있어서) */
    static final int TRANSFER_MARGIN_MIN = 2;
    /** 이 모드가 피하는 행사·돌발(원일님 avoid = true) 하나를 몇 분만큼 싫게 볼지 */
    static final int EVENT_MIN = 10;
    /** 평소 대중교통을 좋아하는 사람(prefer_transit 2)이면 대중교통을 몇 분만큼 좋게 볼지, 걷기를 좋아하면(0) 반대 */
    static final int HABIT_MIN = 3;
    /** 휠체어·어르신이 날씨 속에 밖에 있는 시간을 몇 배로 볼지 (비 오는 날 휠체어 22분 걷기를 고르던 문제, 점검 1·30) */
    static final int HEAVY_WEATHER_X = 2;
    static final int MAX_REASONS = 3;

    /**
     * 지금 상황 중 점수에 쓰는 것.
     *
     * @param habit 날씨가 같을 때 지난번 고른 것 (transit, walk). 없으면 null
     */
    /** 밤 대중교통에서 정류장까지 걷는 길도 밝을 때의 이유 (because) */
    static final String NIGHT_STOP_BRIGHT = "정류장까지도 가로등 많은 밝은 길";

    /** 그 이유를 말로 (점검 18번: 밤이라 무서운데 대중교통을 고르면 밝은 길 얘기가 빠졌음) */
    static final String NIGHT_STOP_SAY = "밤이라 정류장까지 가는 길도 가로등이 많은 밝은 길로 골랐어요.";

    /** 밤 대중교통인데 정류장까지 가는 길이 밝다고 못 할 때의 이유 앞부분 (뒤에 "약 N m 있어요") */
    static final String NIGHT_STOP_DARK = "밤이라 정류장까지 가는 길에 어두운 곳이";

    /** "밤이라 정류장까지 가는 길에 어두운 곳이 약 650m 있어요" (10m 단위) */
    static String nightStopDark(int darkM) {
        return NIGHT_STOP_DARK + " 약 " + Math.max(10, Math.round(darkM / 10.0) * 10) + "m 있어요";
    }

    public record Context(String mode, boolean rain, boolean heat, boolean cold, boolean dustBad, boolean night,
                          boolean personalSpeed, Integer preferTransit, String habit, String bodyNote) {

        boolean badWeather() {
            return rain || heat || cold || dustBad;
        }
    }

    /** 점수 매긴 결과 */
    public record Scored(Trip best, List<Trip> others, List<String> because) {
    }

    /**
     * 후보 하나를 엔진·실시간으로 따져 본 값.
     *
     * @param minutes  점수에 쓸 걸리는 시간 (대중교통은 걷기 + 기다림 + BIS 타는 시간, 정보가 없으면 후보 시간 그대로)
     * @param burden   엔진 기준 부담(분)
     * @param darkM    밤에 어두운 길을 걷는 길이(m). 낮이면 0
     * @param feasible 휠체어: 실시간 목록에 탈 저상버스가 없는 버스 구간이 있으면 false (목록을 못 받았으면 true)
     */
    record Look(double minutes, double burden, int darkM, boolean feasible) {
    }

    /** 모드별 낮 기준 비중 (어린이 보호구역 3 : 도움 1 등). 없으면(시험) 낮 기준 부담 0 */
    private final RouteProperties props;

    @Autowired
    public TripScorer(RouteProperties props) {
        this.props = props;
    }

    /** 비중 없이 (시험) */
    public TripScorer() {
        this(null);
    }

    public Scored score(List<Trip> trips, Context ctx) {
        Map<Trip, Look> looks = new java.util.IdentityHashMap<>();
        trips.forEach(t -> looks.put(t, look(t, ctx)));
        // 휠체어가 탈 수 없는 버스 경로는 뺀다. 다 빠지면(걷는 길도 못 찾음) 원래 후보로 두고 이유에 저상버스 없음을 알림
        List<Trip> sorted = new ArrayList<>(trips.stream().filter(t -> looks.get(t).feasible()).toList());
        if (sorted.isEmpty()) {
            sorted = new ArrayList<>(trips);
        }
        sorted.sort(Comparator.comparingDouble(t -> points(t, ctx, looks.get(t))));
        Trip best = sorted.get(0);
        return new Scored(best, List.copyOf(sorted.subList(1, sorted.size())), reasons(best, sorted, ctx, looks));
    }

    double points(Trip t, Context ctx) {
        return points(t, ctx, look(t, ctx));
    }

    double points(Trip t, Context ctx, Look look) {
        double p = look.minutes() + look.burden();
        boolean heavy = "senior".equals(ctx.mode()) || "wheelchair".equals(ctx.mode());
        if (ctx.badWeather() || ctx.bodyNote() != null) {
            // 날씨 속·아픈 몸으로 걷는 시간을 한 번 더 센다. 휠체어·어르신이 날씨 속이면 그만큼 더
            double outside = outsideMinutes(t, ctx.rain());
            p += heavy && ctx.badWeather() ? outside * HEAVY_WEATHER_X : outside;
        }
        // 턱·공사 신고는 휠체어·어르신에게 더 큰 부담 (갈아타기와 같은 두 배)
        p += t.reports() * REPORT_MIN * (heavy ? 2 : 1);
        p += t.transfers() * TRANSFER_MIN * (heavy ? 2 : 1);
        p += avoided(t).size() * EVENT_MIN;
        if (ctx.preferTransit() != null && ctx.preferTransit() != 1) {
            boolean likes = (ctx.preferTransit() == 2) == "transit".equals(t.type());
            p += likes ? -HABIT_MIN : HABIT_MIN;
        }
        if (ctx.habit() != null && ctx.habit().equals(t.type())) {
            p -= HABIT_MIN;
        }
        return p;
    }

    /**
     * 엔진 기준 부담과 대중교통 실시간 시간·휠체어 가능 여부.
     * 걷는 길은 추천기·대중교통 창구가 엔진에서 받아 실어 준 경로(walkRoute)를 본다. 없으면(엔진이 길을 못 찾음) 부담 0
     */
    Look look(Trip t, Context ctx) {
        if ("walk".equals(t.type())) {
            RouteResult.Route rt = t.walkRoute();
            return new Look(t.minutes(), rt == null ? 0 : burden(rt, ctx.night(), ctx.mode(), profile(ctx)),
                    rt == null || !ctx.night() ? 0 : darkM(rt), true);
        }
        if (t.legs().isEmpty()) {
            return new Look(t.minutes(), 0, 0, true);
        }
        double burden = 0;
        int dark = 0;
        for (TransitResult.Leg l : t.legs()) {
            RouteResult.Route rt = l.walkRoute();
            if (rt != null) {
                burden += burden(rt, ctx.night(), ctx.mode(), profile(ctx));
                dark += ctx.night() ? darkM(rt) : 0;
            }
        }
        Timeline tl = timeline(t.legs(), ctx.mode());
        return new Look(tl.minutes(), burden, dark, tl.feasible());
    }

    /**
     * 걷기 경로 하나의 엔진 기준 부담(분). 엔진이 길을 고를 때 본 것 중 고른 뒤에도 남은 것:
     * 밤 어두운 길, 어린이·노인 낮 기준(보호구역·도움, 쉼 곳) 밖 길이, 노인 계단, 휠체어 비포장·재질 모름.
     * 길이는 ÷ 80 해서 분으로, 어린이·어르신은 2배
     */
    static double burden(RouteResult.Route rt, boolean night, String mode, RouteProperties.Profile profile) {
        double x = "child".equals(mode) || "senior".equals(mode) ? 2 : 1;
        double b = 0;
        if (night) {
            b += x * darkM(rt) / BURDEN_SPEED;
        } else if (profile != null && profile.day().hasWeights()) {
            double sum = 0;
            double missing = 0;
            for (Map.Entry<Criterion, Double> w : profile.day().weights().entrySet()) {
                int in = rt.covered() == null ? 0 : rt.covered().getOrDefault(w.getKey().name().toLowerCase(), 0);
                missing += w.getValue() * Math.max(0, rt.distanceM() - in);
                sum += w.getValue();
            }
            b += x * missing / sum / BURDEN_SPEED;
        }
        if ("senior".equals(mode) && rt.stairs() != null) {
            b += rt.stairs().size() * STAIRS_MIN;
        }
        if ("wheelchair".equals(mode)) {
            b += (rt.unpavedM() + rt.surfaceUnknownM()) / 100.0 * UNPAVED_MIN_PER_100M;
        }
        return b;
    }

    static int darkM(RouteResult.Route rt) {
        return rt.dark() == null ? 0 : rt.dark().stream().mapToInt(d -> d.toM() - d.fromM()).sum();
    }

    /**
     * 대중교통 구간을 차례로 지나며 걸리는 시간과 휠체어 가능 여부.
     * <ul>
     *   <li>걷기·지하철·들르기: 구간 시간 그대로</li>
     *   <li>버스: 내가 정류장에 닿은 시각 뒤에 오는 버스(첫 버스는 닿은 뒤, 갈아타면 2분 넘게 뒤)를 실시간 목록에서 골라
     *       기다림을 더하고, 타는 시간은 그 버스의 남은 분 ÷ 남은 정거장 × 구간 정거장 수 (모르면 구간 시간).
     *       휠체어는 저상버스만. 실시간 목록에 탈 저상버스가 없으면 휠체어는 못 탐(목록을 못 받았으면 확인 못함), 다른 모드는 기다림 없이 구간 시간</li>
     * </ul>
     */
    record Timeline(double minutes, boolean feasible) {
    }

    static Timeline timeline(List<TransitResult.Leg> legs, String mode) {
        boolean wheelchair = "wheelchair".equals(mode);
        double t = 0;
        boolean rode = false;
        boolean feasible = true;
        for (TransitResult.Leg l : legs) {
            if (!"bus".equals(l.type())) {
                t += l.minutes();
                rode |= "subway".equals(l.type());
                continue;
            }
            boolean transfer = rode;
            double after = transfer ? t + TRANSFER_MARGIN_MIN : t;
            BusArrival bus = l.buses() == null ? null : l.buses().stream()
                    .filter(a -> a.remainMin() != null && (!wheelchair || a.lowFloor()))
                    .filter(a -> transfer ? a.remainMin() > after : a.remainMin() >= after)
                    .min(Comparator.comparingInt(BusArrival::remainMin)).orElse(null);
            if (bus == null) {
                // shortcut: 목록 버스가 모두 내가 닿기 전에 지나가면 다음 버스까지(배차 간격)를 몰라 기다림 0으로 셈.
                // 대중교통 응답에 배차 간격이 생기면 그만큼 더하기
                // 휠체어: 실시간 버스 목록이 있는데 탈 저상버스가 없을 때만 뺀다. 목록을 못 받았으면 확인 못함으로 남김
                feasible &= !wheelchair || noLiveBuses(l);
                t += l.minutes();
            } else {
                t = bus.remainMin();
                boolean perStop = bus.remainStop() != null && bus.remainStop() > 0 && bus.remainMin() > 0
                        && l.stationCount() != null && l.stationCount() > 0;
                t += perStop ? (double) bus.remainMin() / bus.remainStop() * l.stationCount() : l.minutes();
            }
            rode = true;
        }
        return new Timeline(t, feasible);
    }

    /** BIS가 막혔거나 오는 버스 목록이 비어서 저상버스인지 알 수 없는 버스 구간 */
    static boolean noLiveBuses(TransitResult.Leg l) {
        return !Boolean.TRUE.equals(l.liveAvailable()) || l.buses() == null || l.buses().isEmpty();
    }

    private RouteProperties.Profile profile(Context ctx) {
        try {
            return props == null ? null : props.profile(ctx.mode());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 밖에서 걷는 시간(분). 걷기는 걸리는 시간 그대로(모드·내 걸음 속도로 이미 계산됨), 대중교통은 걷는 구간 시간의 합.
     * 예전엔 모든 모드를 분당 80m로 셌는데, 휠체어(50m)·어르신(60m)은 실제로 더 오래 밖에 있다.
     * 비 오는 날은 지붕 아래 구간을 뺀다 ({@link #exposed})
     */
    static double outsideMinutes(Trip t, boolean rain) {
        if ("walk".equals(t.type())) {
            return t.minutes() * exposed(t.walkRoute(), rain);
        }
        if (!t.legs().isEmpty()) {
            return t.legs().stream().filter(l -> "walk".equals(l.type()))
                    .mapToDouble(l -> l.minutes() * exposed(l.walkRoute(), rain)).sum();
        }
        return (double) t.walkM() / OUTSIDE_SPEED;
    }

    /**
     * 비 오는 날 젖는 시간 = 지붕 없는 구간을 걷는 시간. 엔진이 준 covered.roof(지붕 아래 m)를 거리로 나눈 비율만큼 뺀다 (2026-11-09).
     * 엔진이 고려한 것은 추천도 고려한다: 엔진 경로(walkRoute)를 그대로 받으므로 지하로 가는 길이면 자동으로 따라간다.
     * 폭염·한파·미세먼지는 지붕이 막아 주지 않으므로 비에만 쓴다. 비가 아니거나 경로·지붕 값이 없으면 1 (전부 밖)
     */
    static double exposed(RouteResult.Route rt, boolean rain) {
        Integer roof = !rain || rt == null || rt.covered() == null ? null : rt.covered().get("roof");
        return roof == null || rt.distanceM() <= 0 ? 1 : Math.max(0, 1 - (double) roof / rt.distanceM());
    }

    /** 이 길이 지나는, 이 모드가 피하는 행사·돌발 (원일님 엔진이 모드별로 avoid 를 정함) */
    static List<RouteResult.Event> avoided(Trip t) {
        return t.events() == null ? List.of() : t.events().stream().filter(RouteResult.Event::avoid).toList();
    }

    /** 왜 1등인지. 도구 결과 숫자만 쓴다 */
    List<String> reasons(Trip best, List<Trip> all, Context ctx, Map<Trip, Look> looks) {
        List<String> out = new ArrayList<>();
        Trip other = all.stream().filter(t -> t != best).findFirst().orElse(null);
        boolean walk = "walk".equals(best.type());
        if (!walk && ctx.badWeather()) {
            out.add(weatherWord(ctx) + " 덜 걷는 길");
        }
        if (!walk && ctx.habit() != null && "transit".equals(ctx.habit()) && ctx.badWeather()) {
            out.add("지난번 " + weatherWhen(ctx) + " 때도 대중교통을 타셨어요");
        }
        if (walk && "walk".equals(ctx.habit())) {
            out.add("지난번 이런 날씨에도 걸어가셨어요");
        }
        // 탈 수 있는 저상버스가 없으면(feasible false) "없어요"와 같이 나오지 않게
        if (!walk && best.lowFloor() && "wheelchair".equals(ctx.mode()) && looks.get(best) != null
                && looks.get(best).feasible()) {
            out.add("곧 오는 저상버스가 있어요");
        }
        if (!walk && looks.get(best) != null && !looks.get(best).feasible()) {
            out.add("곧 오는 저상버스가 없어요");
        } else if (!walk && "wheelchair".equals(ctx.mode())
                && best.legs().stream().anyMatch(l -> "bus".equals(l.type()) && noLiveBuses(l))) {
            out.add("저상버스인지 지금 확인할 수 없어요");
        }
        // 밤: 다른 후보보다 어두운 길을 덜 걸으면
        if (other != null && looks.get(best) != null && looks.get(other) != null) {
            int less = looks.get(other).darkM() - looks.get(best).darkM();
            if (less >= 50) {
                out.add("어두운 길 " + less + "m를 덜 걸어요");
            }
        }
        for (Trip t : all) {
            if (t == best || !"walk".equals(t.type())) {
                continue;
            }
            for (RouteResult.Event e : avoided(t)) {
                if (avoided(best).stream().noneMatch(b -> b.title().equals(e.title()))) {
                    out.add(e.title() + " " + e.kind() + " 피함");
                }
            }
        }
        // 밤 대중교통: 정류장까지 걷는 구간도 원일님 엔진이 밤 기준(밝은 길)으로 바꿨을 때만
        // safeWalk는 엔진 길로 바꿨다는 뜻일 뿐이라, 다른 후보보다 어두운 길이 길면 밝다고 하지 않는다
        boolean notDarker = other == null || looks.get(best) == null || looks.get(other) == null
                || looks.get(best).darkM() <= looks.get(other).darkM();
        // 밝은 길이라고 못 하면(다른 후보보다 어두움) 어두운 길이를 사실대로 (점검 18번 11/12: 밤 얘기가 하나도 없었음)
        // 밤 이유는 3개 자르기에서 빠지지 않게 맨 앞에 둔다
        if (!walk && ctx.night() && notDarker
                && best.legs().stream().anyMatch(l -> Boolean.TRUE.equals(l.safeWalk()))) {
            out.add(0, NIGHT_STOP_BRIGHT);
        } else if (!walk && ctx.night() && looks.get(best) != null && looks.get(best).darkM() >= 50) {
            out.add(0, nightStopDark(looks.get(best).darkM()));
        }
        if (!walk && "wheelchair".equals(ctx.mode())) {
            out.add("엘리베이터로 들어갈 수 있는 역만");
        }
        // 맑은 날 대중교통이 1등이면 위 이유가 하나도 없어 빈 칸이 됨 → 걷기 후보와 비교 (남는 칸만 채움)
        Trip walking = walk ? null : all.stream().filter(t -> "walk".equals(t.type())).findFirst().orElse(null);
        if (walking != null && best.minutes() < walking.minutes()) {
            out.add("걷기보다 " + (walking.minutes() - best.minutes()) + "분 빨라요");
        }
        if (walking != null && best.walkM() < walking.walkM()) {
            out.add("걷는 거리 " + (walking.walkM() - best.walkM()) + "m 줄어요");
        }
        if (walk && other != null && best.minutes() < other.minutes()) {
            out.add(best.minutes() + "분으로 " + (other.minutes() - best.minutes()) + "분 더 빨라요");
        }
        if (walk && best.reports() == 0) {
            int avoidedReports = all.stream().filter(t -> "walk".equals(t.type())).mapToInt(Trip::reports).max().orElse(0);
            if (avoidedReports > 0) {
                out.add("불편 신고 " + avoidedReports + "건 있는 길을 피함");
            }
        }
        if (walk && ctx.night()) {
            Integer light = best.diff() == null ? null : best.diff().get("light");
            out.add(light != null && light > 0 ? "밤이라 가로등 +" + light + "개 밝은 길" : "밤이라 밝은 길");
        }
        if (walk && "safe".equals(best.route()) && best.diff() != null) {
            Map<String, String> names = new LinkedHashMap<>();
            names.put("cctv", "CCTV");
            names.put("light", "가로등");
            names.put("bell", "비상벨");
            for (Map.Entry<String, String> e : names.entrySet()) {
                Integer n = best.diff().get(e.getKey());
                if (n != null && n > 0 && !(ctx.night() && "light".equals(e.getKey()))) {
                    out.add(e.getValue() + " +" + n + "개");
                }
            }
        }
        if (ctx.personalSpeed() && walk) {
            out.add("내 걸음 속도로 계산");
        }
        return out.size() > MAX_REASONS ? List.copyOf(out.subList(0, MAX_REASONS)) : List.copyOf(out);
    }

    static String weatherWord(Context ctx) {
        if (ctx.rain()) {
            return "비가 와서";
        }
        if (ctx.heat()) {
            return "더워서";
        }
        if (ctx.cold()) {
            return "추워서";
        }
        return "미세먼지가 나빠서";
    }

    static String weatherWhen(Context ctx) {
        if (ctx.rain()) {
            return "비 올";
        }
        if (ctx.heat()) {
            return "더울";
        }
        if (ctx.cold()) {
            return "추울";
        }
        return "미세먼지 나쁠";
    }
}
