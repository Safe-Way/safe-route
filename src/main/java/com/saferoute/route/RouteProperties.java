package com.saferoute.route;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * route.yml 값. 설명은 route.yml 주석과 결정 문서 「경로 엔진 결정 사항 정리」에 있다.
 *
 * @param profiles 모드 이름(adult, child, senior, wheelchair) → 그 사람에 맞춘 기준
 * @param coverage 조명·CCTV 커버율을 잴 때 쓰는 거리
 * @param searchMarginM 출발·도착 사각형을 넓혀 도로망을 읽는 거리
 * @param retryMarginM  그 범위에서 경로를 못 찾으면 이만큼 넓혀 한 번 더 찾는다 (강·철도·큰 도로를 돌아가는 길)
 * @param passableSnapExtraM 휠체어가 지날 수 있는 길에 붙일 때, 모든 길 기준보다 이만큼 넘게 멀어지면 붙이지 않는다
 * @param darkStretch 걷는 동안 "앞으로 ○m는 조명이 적습니다" 안내에 쓸 어두운 구간 기준 (FR-31)
 * @param turn        걷는 동안 "80m 앞 왼쪽" 안내에 쓸 꺾는 곳 기준 (FR-34)
 */
@ConfigurationProperties("route")
public record RouteProperties(
        Map<String, Profile> profiles,
        CoverageSettings coverage,
        CountRadius countRadiusM,
        int maxFacilities,
        double searchMarginM,
        double retryMarginM,
        double snapMaxM,
        double passableSnapExtraM,
        double samePointM,
        double tooFarM,
        DarkStretch darkStretch,
        Turn turn) {

    /**
     * @param minAngle 경로 선에서 방향이 이 각도(도)보다 크게 바뀌면 꺾는 곳 (FR-34)
     * @param lookM    방향을 잴 때 꺾임점 앞뒤로 보는 거리. 짧은 지그재그를 무시한다
     * @param linkM    다음 꺾는 곳이 이 거리 안에 이어지면 그 거리를 붙여 앱이 한 번에 말하게 한다 (횡단보도 건너기 등)
     */
    public record Turn(double minAngle, double lookM, double linkM) {
    }

    /**
     * @param mergeGapM 어두운 표본 사이 밝은 틈이 이 길이 이하면 하나의 구간으로 잇는다
     * @param minM      이은 뒤에도 이보다 짧은 어두운 구간은 알리지 않는다
     */
    public record DarkStretch(double mergeGapM, double minM) {
    }

    public record CountRadius(double light, double cctv, double bell, double help) {
    }

    /**
     * @param lightM      등에서 길을 따라 이 거리 안이면 밝은 구간 (30m)
     * @param cctvM       CCTV에서 길을 따라 이 거리 안이면 찍히는 구간 (50m)
     * @param snapToRoadM 등·CCTV를 가장 가까운 길 하나에 붙일 때 허용 거리. 넘으면 길을 비추거나 찍지 않는다고 본다 (20m)
     * @param sampleM     길을 이 간격으로 나눠 커버 여부를 잰다 (10m)
     */
    public record CoverageSettings(double lightM, double cctvM, double snapToRoadM, double sampleM) {
    }

    /** 기본 모드 (청년·중년). 요청에 mode가 없으면 이것 */
    public static final String DEFAULT_PROFILE = "adult";

    /**
     * 낮 또는 밤의 기준.
     *
     * @param weights 기준별 비중. 비중이 클수록 먼저 챙긴다 (밤 밝기 5 : 도움 시설 2 : CCTV 1). 비면 가장 짧은 길
     * @param helpM   도움 시설 반경 (걸어서 약 2분)
     * @param restM   쉼 곳 반경
     * @param schoolM 어린이보호구역 반경 (법 기준 300m)
     * @param note    다른 길을 고른 이유로 보여 줄 문장 (낮 모드용. 비면 시설 차이로 설명)
     */
    public record Phase(Map<Criterion, Double> weights, double helpM, double restM, double schoolM, String note) {

        public static final Phase SHORTEST = new Phase(Map.of(), 0, 0, 0, null);

        public Phase {
            EnumMap<Criterion, Double> copy = new EnumMap<>(Criterion.class);
            if (weights != null) {
                weights.forEach((k, v) -> {
                    if (v != null && v > 0) {
                        copy.put(k, v);
                    }
                });
            }
            weights = java.util.Collections.unmodifiableMap(copy);
        }

        public double weight(Criterion c) {
            return weights.getOrDefault(c, 0.0);
        }

        public boolean hasWeights() {
            return !weights.isEmpty();
        }
    }

    /**
     * 누가 걷는지에 따라 바뀌는 기준.
     *
     * @param maxDetour    안전 경로가 최단 경로(지날 수 있는 길 중)의 몇 배까지 돌 수 있는지. 0이면 한도 없음
     * @param nightLeadMin 일몰 몇 분 전부터 밤으로 볼지 (어린이 60)
     * @param exclude      이 성질이 1(확실히 못 지남)인 길은 아예 뺀다 (휠체어: 계단, 확인된 턱, 못 지나는 육교)
     * @param avoidStairs  계단 조건부 제외: 계단 없는 길이 우회 한도 안에 있으면 계단 길을 뺀다 (노인)
     * @param avoidUnpaved 비포장 조건부 제외: 비포장 없는 길이 우회 한도 안에 있으면 비포장 길을 뺀다 (휠체어, 2026-11-04)
     */
    public record Profile(double maxDetour, double walkSpeedMPerMin, int nightLeadMin, List<Penalty> exclude,
            boolean avoidStairs, boolean avoidUnpaved, Phase day, Phase night) {

        public Profile {
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
            day = day == null ? Phase.SHORTEST : day;
            night = night == null ? Phase.SHORTEST : night;
        }

        public Phase phase(boolean isNight) {
            return isNight ? night : day;
        }

        /**
         * 이 사람이 지날 수 있는 길인지. 제외 성질 중 하나라도 1이면 못 지난다.
         * 휠체어 경사로가 있다고 확인된 계단은 지난다 (경사로 없음·모름은 못 지남).
         */
        public boolean passable(RoadEdge e) {
            for (Penalty p : exclude) {
                if (p == Penalty.STAIRS && e.penalty().hasRamp()) {
                    continue;
                }
                if (e.penalty().get(p) >= 1.0) {
                    return false;
                }
            }
            return true;
        }

        /** 조건부 제외할 길 (노인 계단, 휠체어 비포장). 없으면 null */
        public java.util.function.Predicate<RoadEdge> avoid() {
            if (avoidStairs && avoidUnpaved) {
                return e -> RouteFinder.isStairs(e) || RouteFinder.isUnpaved(e);
            }
            return avoidStairs ? RouteFinder::isStairs : avoidUnpaved ? RouteFinder::isUnpaved : null;
        }

        /** 걸리는 시간(분), 이 사람의 보행 속도로 환산 */
        public int minutes(double meters) {
            return (int) Math.round(meters / walkSpeedMPerMin);
        }
    }

    /** 모드 이름으로 값을 찾는다. 비어 있으면 기본 모드, 모르는 이름이면 IllegalArgumentException */
    public Profile profile(String name) {
        String key = name == null || name.isBlank() ? DEFAULT_PROFILE : name.trim().toLowerCase();
        Profile p = profiles == null ? null : profiles.get(key);
        if (p == null) {
            throw new IllegalArgumentException("mode는 " + (profiles == null ? "[]" : profiles.keySet()) + " 중 하나여야 합니다");
        }
        return p;
    }
}
