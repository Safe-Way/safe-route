package com.saferoute.route.engine.explain;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.condition.Conditions;
import com.saferoute.route.engine.facility.FacilityGroup;

/**
 * 두 경로의 차이를 문장으로 만든다 (FR-06, 요구사항 6.3).
 * 예: "180m(2분) 더 돌지만 CCTV +12대, 가로등 +30개, 비상벨 +3개"
 * 낮·밤 모두 그 모드가 길을 고른 기준의 실제 차이로 말한다 (2026-11-06).
 * 노인 낮 "96m(2분) 더 돌지만 쉼 곳 +11곳", 어린이 낮 "…어린이보호구역 +1곳, 도움 지점 +2곳".
 * 최단 경로의 계단·비포장을 피했으면 "계단을 피하고 쉼 곳 +11곳"·"비포장을 피하는 길로 갑니다"처럼 앞에 말한다.
 * 말할 차이가 없으면 route.yml의 note(밤은 "더 밝고 사람 눈이 닿는 길로 갑니다")를 쓴다.
 */
public final class ExplanationBuilder {

    /** 기준이 없는 시간(어른 낮)에는 "가장 안전한"이라고 하지 않는다 (요구사항 2장 '평소') */
    static final String DAY = "지금은 밤이 아니라 가장 짧은 길을 안내합니다";
    static final String NIGHT_OFF = "밤 기준을 꺼서 가장 짧은 길을 안내합니다";
    static final String WHEELCHAIR = "휠체어로 지날 수 있는 길 중 가장 짧은 길입니다";
    static final String SAME = "가장 짧은 길이 가장 안전한 길입니다";
    static final String CAPPED = "더 안전한 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다";
    static final String NIGHT_REASON = "더 밝고 사람 눈이 닿는 길로 갑니다";
    static final String SHORTEST = "가장 짧은 길로 갑니다";

    /**
     * 최단 경로에 있던 것 중 안전 경로가 피한 것.
     *
     * @param stairs  계단 (계단을 피하는 모드)
     * @param unpaved 비포장 (비포장을 피하는 모드)
     * @param events  행사·돌발상황 둘레
     */
    public record Avoided(boolean stairs, boolean unpaved, boolean events) {

        public static final Avoided NONE = new Avoided(false, false, false);
    }

    /** 문장에 넣는 시설 차이 개수 */
    private static final int MAX_ITEMS = 3;

    /** 가장 짧은 길을 안내한다는 문장. 행사·돌발 둘레를 피해 다른 길로 가면 이 문장은 맞지 않다 */
    private static final Set<String> SHORTEST_TEXTS = Set.of(DAY, NIGHT_OFF, WHEELCHAIR, SAME, CAPPED);

    private ExplanationBuilder() {
    }

    /**
     * @param cond     상황
     * @param profile  누가 걷는지
     * @param phase    지금 쓰는 기준 (낮 또는 밤)
     * @param same     안전 경로가 최단 경로와 같은지
     * @param capped   우회 한도 때문에 기준에 맞춘 길을 줄 수 없었는지
     * @param extraM   안전 경로가 더 걷는 거리(m)
     * @param extraMin 그만큼 더 걸리는 시간(분)
     * @param diff     안전 경로 − 최단 경로 시설 수
     */
    public static String text(Conditions cond, RouteProperties.Profile profile, RouteProperties.Phase phase,
            boolean same, boolean capped, int extraM, int extraMin, Map<FacilityGroup, Integer> diff) {
        return text(cond, profile, phase, same, capped, extraM, extraMin, diff, Avoided.NONE);
    }

    /** @param avoided 최단 경로에 있던 것 중 안전 경로가 피한 것 */
    public static String text(Conditions cond, RouteProperties.Profile profile, RouteProperties.Phase phase,
            boolean same, boolean capped, int extraM, int extraMin, Map<FacilityGroup, Integer> diff,
            Avoided avoided) {
        boolean rules = phase.hasWeights() || profile.avoidStairs() || profile.avoidUnpaved();
        // 휠체어 낮: 기준(가중치)은 없고 제외·비포장 조건부 제외만 있다. 최단 그대로면 "지날 수 있는 길 중 가장 짧은 길"
        if (!profile.exclude().isEmpty() && !phase.hasWeights() && (same || !rules)) {
            return WHEELCHAIR;
        }
        if (!rules) {
            if (!cond.night()) {
                // 사용자가 직접 끈 것(manual)과 실제로 낮인 것(auto)을 구분해 말한다
                return "manual".equals(cond.nightSource()) ? NIGHT_OFF : DAY;
            }
            return SAME;
        }
        if (same) {
            return capped ? CAPPED : SAME;
        }
        String detour = detour(extraM, extraMin);
        Set<FacilityGroup> groups = groups(phase);
        String gains = diff.entrySet().stream()
                .filter(e -> e.getValue() > 0 && groups.contains(e.getKey()))
                // 많이 늘어난 순. 낮에는 같으면 비중이 큰 기준 먼저 (어린이 보호구역 3 > 도움 시설 1)
                .sorted(Comparator.comparing((Map.Entry<FacilityGroup, Integer> e) -> e.getValue()).reversed()
                        .thenComparing(e -> cond.night() ? 0 : -weight(phase, e.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_ITEMS)
                .map(e -> e.getKey().gain(e.getValue()))
                .collect(Collectors.joining(", "));
        // "계단을 피하고 쉼 곳 +11곳", 시설 차이가 없으면 "계단을 피하는 길로 갑니다"
        String what = avoided.stairs() && avoided.unpaved() ? "계단·비포장" : avoided.stairs() ? "계단"
                : avoided.unpaved() ? "비포장" : null;
        if (what != null) {
            return detour + " " + (gains.isEmpty() ? what + "을 피하는 길로 갑니다" : what + "을 피하고 " + gains);
        }
        if (!gains.isEmpty()) {
            return detour + " " + gains;
        }
        if (avoided.events()) {
            // 행사·돌발만 피해 돌았다. withEvents가 "…둘레를 피해 갑니다"로 바꾼다
            return DAY;
        }
        String reason = phase.note() != null && !phase.note().isBlank() ? phase.note() : NIGHT_REASON;
        return detour + " " + reason;
    }

    private static double weight(RouteProperties.Phase phase, FacilityGroup g) {
        return switch (g) {
            case LIGHT -> phase.weight(com.saferoute.route.engine.score.Criterion.LIGHT);
            case CCTV -> phase.weight(com.saferoute.route.engine.score.Criterion.CCTV);
            case HELP, BELL -> phase.weight(com.saferoute.route.engine.score.Criterion.HELP);
            case REST -> phase.weight(com.saferoute.route.engine.score.Criterion.REST);
            case SCHOOL -> phase.weight(com.saferoute.route.engine.score.Criterion.SCHOOL);
        };
    }

    /** 이 기준이 챙기는 시설 묶음. 도움 시설에는 비상벨도 든다 */
    static Set<FacilityGroup> groups(RouteProperties.Phase phase) {
        Set<FacilityGroup> out = EnumSet.noneOf(FacilityGroup.class);
        for (com.saferoute.route.engine.score.Criterion c : phase.weights().keySet()) {
            switch (c) {
                case LIGHT -> out.add(FacilityGroup.LIGHT);
                case CCTV -> out.add(FacilityGroup.CCTV);
                case HELP -> {
                    out.add(FacilityGroup.HELP);
                    out.add(FacilityGroup.BELL);
                }
                case REST -> out.add(FacilityGroup.REST);
                case SCHOOL -> out.add(FacilityGroup.SCHOOL);
            }
        }
        return out;
    }

    /**
     * 행사·돌발상황만 피해 돌았으면 이유 문장을 바꾼다 (2026-11-06).
     * "가장 짧은 길을 안내합니다" 같은 문장 대신 "120m(2분) 더 돌지만 ○○ 행사 둘레를 피해 갑니다".
     *
     * @param shortEvents 최단 경로가 지나는 것. 두 경로가 같으면 빈 목록
     * @param safeEvents  안내하는 경로(safe)가 지나는 것
     */
    public static String withEvents(String summary, int extraM, int extraMin, List<RouteResult.Event> shortEvents,
            List<RouteResult.Event> safeEvents) {
        String avoided = avoided(shortEvents, safeEvents);
        if (!avoided.isEmpty() && SHORTEST_TEXTS.contains(summary)) {
            return detour(extraM, extraMin) + " " + avoided + " 둘레를 피해 갑니다";
        }
        return summary;
    }

    /**
     * 행사·돌발 주의·알림.
     * <ul>
     *   <li>다른 이유로 돌면서 피하기도 했으면: 알림 "○○ 행사 둘레도 피했습니다"</li>
     *   <li>안내하는 길이 피할 것을 지나면(피하는 길이 우회 한도를 넘음): 주의 "○○ 구간을 지납니다. 피해 가는 길은 너무 멉니다"</li>
     *   <li>지나도 되는 것(어른의 행사, 차 없는 거리 등)을 지나면: 알림 "○○ 구간을 지납니다"</li>
     * </ul>
     *
     * @param summary 이유 문장 ({@link #withEvents} 뒤). 이미 피한 것을 말했으면 "둘레도 피했습니다"는 붙이지 않는다
     */
    public static List<RouteResult.Note> eventNotes(String summary, List<RouteResult.Event> shortEvents,
            List<RouteResult.Event> safeEvents) {
        List<RouteResult.Note> out = new java.util.ArrayList<>();
        String avoided = avoided(shortEvents, safeEvents);
        if (!avoided.isEmpty() && !summary.endsWith(" 둘레를 피해 갑니다")) {
            out.add(RouteResult.Note.info("event", avoided + " 둘레도 피했습니다"));
        }
        String warn = names(safeEvents.stream().filter(RouteResult.Event::avoid).map(ExplanationBuilder::label)
                .toList());
        if (!warn.isEmpty()) {
            out.add(RouteResult.Note.warn("event", warn + " 구간을 지납니다. 피해 가는 길은 너무 멉니다"));
        }
        String notice = names(safeEvents.stream().filter(e -> !e.avoid()).map(ExplanationBuilder::label).toList());
        if (!notice.isEmpty()) {
            out.add(RouteResult.Note.info("event", notice + " 구간을 지납니다"));
        }
        return out;
    }

    /** 주의가 있으면 "가장 짧은 길이 가장 안전한 길입니다"는 맞지 않아 "가장 짧은 길로 갑니다"로 바꾼다 */
    public static String withWarnings(String summary, List<RouteResult.Note> notes) {
        boolean warn = notes.stream().anyMatch(n -> n.level().equals("warn"));
        return warn && SAME.equals(summary) ? SHORTEST : summary;
    }

    /** 최단 경로가 지나는 피할 행사·돌발 중 안전 경로가 지나지 않는 것의 이름 */
    private static String avoided(List<RouteResult.Event> shortEvents, List<RouteResult.Event> safeEvents) {
        Set<String> passed = safeEvents.stream().map(ExplanationBuilder::label).collect(Collectors.toSet());
        return names(shortEvents.stream().filter(RouteResult.Event::avoid).map(ExplanationBuilder::label)
                .filter(n -> !passed.contains(n)).toList());
    }

    /**
     * 계단을 피하는 모드(노인)가 계단을 지나면 주의. 계단 없는 길이 우회 한도를 넘거나 아예 없을 때다.
     *
     * @param stairs     안내하는 경로의 계단 구간 수
     * @param freeExists 계단 없는 길이 있는지 (한도를 넘어도)
     */
    public static List<RouteResult.Note> stairsNotes(int stairs, boolean freeExists) {
        if (stairs == 0) {
            return List.of();
        }
        return List.of(RouteResult.Note.warn("stairs", "계단 %d곳을 지납니다. %s".formatted(stairs,
                freeExists ? "계단을 피하는 길은 너무 멉니다" : "계단을 피하는 길은 없습니다")));
    }

    /** 두 경로가 같은 분으로 반올림되면 "(0분)"이 어색하므로 시간은 뺀다 */
    private static String detour(int extraM, int extraMin) {
        return extraM <= 0 ? "같은 거리로"
                : extraMin > 0 ? "%dm(%d분) 더 돌지만".formatted(extraM, extraMin)
                : "%dm 더 돌지만".formatted(extraM);
    }

    /** 돌발은 이름에 종류가 이미 들어 있다 ("무진대로 공사") */
    static String label(RouteResult.Event e) {
        return e.title().contains(e.kind()) ? e.title() : e.title() + " " + e.kind();
    }

    /** 이름 둘까지 잇고 나머지는 "외 n곳" */
    private static String names(List<String> labels) {
        List<String> distinct = labels.stream().distinct().toList();
        if (distinct.size() <= 2) {
            return String.join(", ", distinct);
        }
        return String.join(", ", distinct.subList(0, 2)) + " 외 " + (distinct.size() - 2) + "곳";
    }
}
