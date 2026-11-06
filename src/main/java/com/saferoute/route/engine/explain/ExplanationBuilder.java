package com.saferoute.route.engine.explain;

import java.util.Comparator;
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
 * 모드별 설명 문구(요구사항 6.5 E1)는 아직 정하는 중이라 낮 모드는 route.yml의 note 한 문장만 쓴다.
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
        if (!cond.night() && phase.note() != null && !phase.note().isBlank()) {
            return detour + " " + phase.note();
        }
        String gains = diff.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.comparing((Map.Entry<FacilityGroup, Integer> e) -> e.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_ITEMS)
                .map(e -> e.getKey().gain(e.getValue()))
                .collect(Collectors.joining(", "));
        if (!gains.isEmpty()) {
            return detour + " " + gains;
        }
        String reason = phase.note() != null && !phase.note().isBlank() ? phase.note() : NIGHT_REASON;
        return detour + " " + reason;
    }

    /**
     * 행사·돌발상황을 문장에 더한다 (2026-11-06).
     * <ul>
     *   <li>최단 경로가 지나는 피할 행사·돌발을 안전 경로가 피했으면: "120m(2분) 더 돌지만 ○○ 행사 둘레를 피해 갑니다".
     *       다른 이유로도 돌았으면 끝에 "(○○ 행사 둘레도 피했습니다)"</li>
     *   <li>안내하는 길이 피할 것을 지나면(피하는 길이 우회 한도를 넘음): "(주의: ○○ 구간을 지납니다. 피해 가는 길은 너무 멉니다)".
     *       이때 "가장 짧은 길이 가장 안전한 길입니다"는 "가장 짧은 길로 갑니다"로 바꾼다</li>
     *   <li>지나도 되는 것(일반 모드의 행사, 차 없는 거리 등)을 지나면: "(알림: ○○ 구간을 지납니다)"</li>
     * </ul>
     *
     * @param shortEvents 최단 경로가 지나는 것. 두 경로가 같으면 빈 목록
     * @param safeEvents  안내하는 경로(safe)가 지나는 것
     */
    public static String withEvents(String text, int extraM, int extraMin, List<RouteResult.Event> shortEvents,
            List<RouteResult.Event> safeEvents) {
        Set<String> passed = safeEvents.stream().map(ExplanationBuilder::label).collect(Collectors.toSet());
        String avoided = names(shortEvents.stream().filter(RouteResult.Event::avoid).map(ExplanationBuilder::label)
                .filter(n -> !passed.contains(n)).toList());
        StringBuilder out = new StringBuilder(text);
        if (!avoided.isEmpty()) {
            if (SHORTEST_TEXTS.contains(text)) {
                out = new StringBuilder(detour(extraM, extraMin) + " " + avoided + " 둘레를 피해 갑니다");
            } else {
                out.append(" (").append(avoided).append(" 둘레도 피했습니다)");
            }
        }
        String warn = names(safeEvents.stream().filter(RouteResult.Event::avoid).map(ExplanationBuilder::label)
                .toList());
        if (!warn.isEmpty()) {
            if (SAME.contentEquals(out)) {
                out = new StringBuilder(SHORTEST);
            }
            out.append(" (주의: ").append(warn).append(" 구간을 지납니다. 피해 가는 길은 너무 멉니다)");
        }
        String notice = names(safeEvents.stream().filter(e -> !e.avoid()).map(ExplanationBuilder::label).toList());
        if (!notice.isEmpty()) {
            out.append(" (알림: ").append(notice).append(" 구간을 지납니다)");
        }
        return out.toString();
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
