package com.saferoute.route.engine.explain;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

import com.saferoute.route.RouteProperties;
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

    /** 문장에 넣는 시설 차이 개수 */
    private static final int MAX_ITEMS = 3;

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
        boolean rules = phase.hasWeights() || profile.avoidStairs();
        if (!rules) {
            if (!profile.exclude().isEmpty()) {
                return WHEELCHAIR;
            }
            if (!cond.night()) {
                // 사용자가 직접 끈 것(manual)과 실제로 낮인 것(auto)을 구분해 말한다
                return "manual".equals(cond.nightSource()) ? NIGHT_OFF : DAY;
            }
            return SAME;
        }
        if (same) {
            return capped ? CAPPED : SAME;
        }
        // 두 경로가 같은 분으로 반올림되면 "(0분)"이 어색하므로 시간은 뺀다
        String detour = extraM <= 0 ? "같은 거리로"
                : extraMin > 0 ? "%dm(%d분) 더 돌지만".formatted(extraM, extraMin)
                : "%dm 더 돌지만".formatted(extraM);
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
}
