package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.saferoute.route.api.RouteResult;

/**
 * 추천 규칙. 후보마다 점수를 매겨 1등과 이유를 정한다. AI는 여기에 끼지 않는다 (점수는 늘 이 규칙대로).
 * 점수는 "분"으로 센다: 걸리는 시간 + 부담(날씨 속 걷는 시간, 불편 신고, 갈아타기, 피할 행사·돌발). 낮을수록 좋다.
 * 아래 값은 처음 정한 값이고, 팀 설계상 점수 규칙은 B(원일님)가 다듬는다. 이 클래스만 바꾸면 된다.
 */
@Component
public class TripScorer {

    /** 불편 신고 한 건을 몇 분만큼 싫게 볼지 */
    static final int REPORT_MIN = 5;
    /** 갈아타기 한 번을 몇 분만큼 싫게 볼지 (어르신·휠체어는 두 배) */
    static final int TRANSFER_MIN = 5;
    /** 걷는 속도로 밖에 있는 시간을 셀 때 쓰는 값 (m/분) */
    static final int OUTSIDE_SPEED = 80;
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
    public record Context(String mode, boolean rain, boolean heat, boolean cold, boolean dustBad, boolean night,
                          boolean personalSpeed, Integer preferTransit, String habit, String bodyNote) {

        boolean badWeather() {
            return rain || heat || cold || dustBad;
        }
    }

    /** 점수 매긴 결과 */
    public record Scored(Trip best, List<Trip> others, List<String> because) {
    }

    public Scored score(List<Trip> trips, Context ctx) {
        List<Trip> sorted = new ArrayList<>(trips);
        sorted.sort(Comparator.comparingDouble(t -> points(t, ctx)));
        Trip best = sorted.get(0);
        return new Scored(best, List.copyOf(sorted.subList(1, sorted.size())), reasons(best, sorted, ctx));
    }

    double points(Trip t, Context ctx) {
        double p = t.minutes();
        boolean heavy = "senior".equals(ctx.mode()) || "wheelchair".equals(ctx.mode());
        if (ctx.badWeather() || ctx.bodyNote() != null) {
            // 날씨 속·아픈 몸으로 걷는 시간을 한 번 더 센다. 휠체어·어르신이 날씨 속이면 그만큼 더
            double outside = (double) t.walkM() / OUTSIDE_SPEED;
            p += heavy && ctx.badWeather() ? outside * HEAVY_WEATHER_X : outside;
        }
        if ("wheelchair".equals(ctx.mode()) && t.lowFloor()) {
            p -= HABIT_MIN; // 휠체어는 곧 오는 저상버스가 있으면 조금 더 좋게
        }
        p += t.reports() * REPORT_MIN;
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

    /** 이 길이 지나는, 이 모드가 피하는 행사·돌발 (원일님 엔진이 모드별로 avoid 를 정함) */
    static List<RouteResult.Event> avoided(Trip t) {
        return t.events() == null ? List.of() : t.events().stream().filter(RouteResult.Event::avoid).toList();
    }

    /** 왜 1등인지. 도구 결과 숫자만 쓴다 */
    List<String> reasons(Trip best, List<Trip> all, Context ctx) {
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
        if (!walk && best.lowFloor()) {
            out.add("곧 오는 저상버스가 있어요");
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
        if (!walk && "wheelchair".equals(ctx.mode())) {
            out.add("엘리베이터로 들어갈 수 있는 역만");
        }
        if (walk && other != null && best.minutes() < other.minutes()) {
            out.add(Sentences.go(ctx.mode()) + " " + best.minutes() + "분으로 " + (other.minutes() - best.minutes()) + "분 더 빨라요");
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
