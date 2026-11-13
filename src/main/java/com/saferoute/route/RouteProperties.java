package com.saferoute.route;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.saferoute.route.data.UserPreferences;
import com.saferoute.route.engine.event.RoadEvent;
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
 * @param passableSnapOriginExtraM 휠체어 출발·도착점만 쓰는 위 한도 (건물 안 출발·도착). 길안내 중 다시 찾기(reroute)는 위 값 (2026-11-13)
 * @param noSidewalkAlleyFallbackFactor 휠체어가 인도 없는 골목을 넣어 다시 찾을 때 그 골목 1m를 다른 길 몇 m로 볼지 (2026-11-13)
 * @param sidewalkDataMinEdgeM 이보다 짧은 길은 국토지리정보원 보도 자료(edge_sidewalk)를 쓰지 않는다. 교차로 조각은 옆 길 인도가 잡힌다 (2026-11-13)
 * @param narrowSidewalkM 인도 폭(보도 자료)이 이보다 좁으면 좁은 인도. 휠체어 조건부 제외, 응답 narrows (2026-11-13)
 * @param endAlleyM 휠체어 출발·도착·들를 곳이 인도 없는 골목(휠체어 제외) 안이면, 거기서 그 골목을 따라 이 길이까지는 지난다 (2026-11-13)
 * @param darkStretch 걷는 동안 "앞으로 ○m는 조명이 적습니다" 안내에 쓸 어두운 구간 기준 (FR-31)
 * @param turn        걷는 동안 "80m 앞 왼쪽" 안내에 쓸 꺾는 곳 기준 (FR-34)
 * @param events      길 위의 행사·돌발상황을 다루는 기준 (2026-11-06)
 * @param rain        비 오는 날 기준 (2026-11-09). 없으면 비 기준을 쓰지 않는다
 * @param underground 지하 길을 지날 수 있는 시간 (2026-11-09). 없으면 지하 길을 쓰지 않는다
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
        double passableSnapOriginExtraM,
        double endAlleyM,
        double noSidewalkAlleyFallbackFactor,
        double sidewalkDataMinEdgeM,
        double narrowSidewalkM,
        double samePointM,
        double tooFarM,
        DarkStretch darkStretch,
        Turn turn,
        Events events,
        Rain rain,
        Underground underground) {

    public RouteProperties {
        events = events == null ? new Events(List.of()) : events;
        rain = rain == null ? Rain.OFF : rain;
        underground = underground == null ? Underground.NONE : underground;
    }

    /**
     * 비 오는 날 기준 (2026-11-09). 밤처럼 단계를 새로 두지 않고 그때의 낮·밤 기준에 지붕 기준(ROOF)을 곱한다 (비 오는 밤도 있으므로).
     * 지붕 없는 길의 부담 전체(거리 + 그 모드 기준 부담)를 (1 + walkMore)배로 본다. 지상 길끼리는 같은 배수라 순위가 맑은 날과 같고
     * (기준이 묽어지지 않음), 지붕 아래 길만 상대적으로 가벼워진다. 우회 한도도 같은 값에서 나온다 ({@link Rain#widen})
     *
     * @param walkMore 젖지 않으려고 더 걸을 수 있는 비율 (0.4면 지붕 아래 길을 1.4배까지). 0이면 비 기준을 쓰지 않는다
     * @param note     지붕 있는 길로 돌았는데 말할 차이가 없을 때 문장
     */
    public record Rain(double walkMore, String note) {

        public static final Rain OFF = new Rain(0, null);

        /**
         * 비 올 때 넓힌 우회 한도 = max(그 사람의 한도, 1 + walkMore). 전부 지붕 아래인 길이 (1 + walkMore)배 길이까지면 지상 길과
         * 비용이 같으므로 한도도 그만큼. 넓힌 한도는 지붕 이득이 있을 때만 쓴다 (RouteService). 한도 없음(0)은 그대로
         */
        public double widen(double maxDetour) {
            return maxDetour > 0 && walkMore > 0 ? Math.max(maxDetour, 1 + walkMore) : maxDetour;
        }
    }

    /**
     * 지하 길을 지날 수 있는 시간 (2026-11-09). 지하상가 통로는 두 역 대합실 사이에만 있어 대합실이 닫히면 못 쓴다.
     *
     * @param open  "05:30"
     * @param close "24:00"이면 자정까지. 설정이 없으면 지하를 쓰지 않는다 (안전한 쪽)
     */
    public record Underground(String open, String close) {

        public static final Underground NONE = new Underground(null, null);

        public boolean openAt(LocalTime t) {
            if (open == null || close == null) {
                return false;
            }
            LocalTime o = LocalTime.parse(open);
            // "24:00"은 LocalTime으로 읽을 수 없어 자정까지로 본다
            return "24:00".equals(close) ? !t.isBefore(o) : !t.isBefore(o) && t.isBefore(LocalTime.parse(close));
        }
    }

    /**
     * @param walkFriendly 이 말이 이름에 든 행사는 걷기에 오히려 좋아(차 없는 거리 등) 어느 모드도 피하지 않고 알리기만 한다.
     *                     띄어쓰기는 무시한다
     */
    public record Events(List<String> walkFriendly) {

        public Events {
            walkFriendly = walkFriendly == null ? List.of() : List.copyOf(walkFriendly);
        }

        /** 이 사람이 둘레를 조건부로 피할 행사·돌발상황인지. 아니면 지날 때 알리기만 한다 */
        public boolean avoids(Profile profile, RoadEvent e) {
            if (!profile.avoidEvents().contains(e.kind())) {
                return false;
            }
            String name = e.title().replace(" ", "");
            return walkFriendly.stream().noneMatch(w -> name.contains(w.replace(" ", "")));
        }
    }

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

    /** 경로에서 이 거리 안이면 "지나는 시설"로 센다. rest·school은 노인·어린이 낮 경로 기준과 같은 반경 */
    public record CountRadius(double light, double cctv, double bell, double help, double rest, double school) {
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

        /** 쉼 곳 기준을 쓸 때만 반경을 쉬는 간격의 절반으로 바꾼다. 간격이 없으면 그대로 */
        Phase withRestM(Integer restEveryM) {
            if (restEveryM == null || !weights.containsKey(Criterion.REST)) {
                return this;
            }
            return new Phase(weights, helpM, restEveryM / 2.0, schoolM, note);
        }

        /**
         * 비 올 때: 이 기준에 지붕 기준(값 = walk-more)을 둔다. 비용은 RouteScorer가 곱하기로 계산하고, 여기 두는 것은
         * covered.roof 응답과 설명이 그대로 동작하게 하려는 것이다. 문장(note)은 이 기준에 없을 때만 비 문장으로 (2026-11-09)
         */
        Phase withRoof(Rain r) {
            Map<Criterion, Double> w = new EnumMap<>(Criterion.class);
            w.putAll(weights);
            w.put(Criterion.ROOF, r.walkMore());
            return new Phase(w, helpM, restM, schoolM, note == null || note.isBlank() ? r.note() : note);
        }
    }

    /**
     * 누가 걷는지에 따라 바뀌는 기준.
     *
     * @param maxDetour    안전 경로가 최단 경로(지날 수 있는 길 중)의 몇 배까지 돌 수 있는지. 0이면 한도 없음
     * @param nightLeadMin 일몰 몇 분 전부터 밤으로 볼지 (어린이 60)
     * @param exclude      이 성질이 1(확실히 못 지남)인 길은 아예 뺀다 (휠체어: 계단, 확인된 턱, 못 지나는 육교, 인도 없는 골목)
     * @param avoidStairs  계단 조건부 제외: 계단 없는 길이 우회 한도 안에 있으면 계단 길을 뺀다 (노인)
     * @param avoidUnpaved 비포장 조건부 제외: 비포장 없는 길이 우회 한도 안에 있으면 비포장 길을 뺀다 (휠체어, 2026-11-04)
     * @param avoidNarrowSidewalk 좁은 인도 조건부 제외: 비포장과 같은 방법이되 따로, 비포장 피하기 다음에 (휠체어, 2026-11-13)
     * @param avoidEvents  둘레를 조건부로 피할 행사·돌발 종류 (road_event.kind). 피하는 길이 우회 한도 안에 있을 때만 피한다.
     *                     계단·비포장 피하기를 먼저 지킨다 (2026-11-06)
     */
    public record Profile(double maxDetour, double walkSpeedMPerMin, int nightLeadMin, List<Penalty> exclude,
            boolean avoidStairs, boolean avoidUnpaved, boolean avoidNarrowSidewalk, List<String> avoidEvents, Phase day,
            Phase night) {

        public Profile {
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
            avoidEvents = avoidEvents == null ? List.of() : List.copyOf(avoidEvents);
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
            return passable(e, false);
        }

        /** endAlley: 인도 없는 골목도 지나는지 (출발·도착·들를 곳이 그 골목 안일 때, RouteService.endAlleys) */
        public boolean passable(RoadEdge e, boolean endAlley) {
            for (Penalty p : exclude) {
                if (p == Penalty.STAIRS && e.penalty().hasRamp()) {
                    continue;
                }
                if (p == Penalty.NO_SIDEWALK_ALLEY) {
                    if (!endAlley && RouteFinder.isNoSidewalkAlley(e)) {
                        return false;
                    }
                    continue;
                }
                if (e.penalty().get(p) >= 1.0) {
                    return false;
                }
            }
            return true;
        }

        /** 조건부 제외할 길 (노인 계단, 휠체어 비포장). 없으면 null. 좁은 인도는 따로 그다음에 피한다 (RouteService.asides) */
        public java.util.function.Predicate<RoadEdge> avoid() {
            if (avoidStairs && avoidUnpaved) {
                return e -> RouteFinder.isStairs(e) || RouteFinder.isUnpaved(e);
            }
            return avoidStairs ? RouteFinder::isStairs : avoidUnpaved ? RouteFinder::isUnpaved : null;
        }

        /**
         * 그 사람이 정한 선호를 모드 기준 위에 덮는다 (user_profile, 2026-11-09). 비어 있는 값은 모드 기준 그대로.
         * <ul>
         *   <li>우회 한도: max_detour (1.0~2.0으로 자름)</li>
         *   <li>계단: 0 못 감 → 계단 길을 뺀다(휠체어 경사로가 있는 계단은 지남). 1 몇 칸은 괜찮음 → 계단 조건부 제외(노인 기준).
         *       2 괜찮음 → 계단을 피하지 않는다. 휠체어 모드는 계단 제외를 풀지 않는다</li>
         *   <li>쉬는 간격: rest_every_m마다 쉬려면 경로 어디서든 그 절반 안에 쉴 곳이 있어야 한다.
         *       그래서 쉼 곳 기준을 쓰는 때(노인 낮)의 쉼 곳 반경을 rest_every_m ÷ 2로 바꾼다</li>
         * </ul>
         */
        public Profile personalized(UserPreferences p) {
            if (p == null || p.isEmpty()) {
                return this;
            }
            double detour = p.maxDetour() == null ? maxDetour : Math.max(1.0, Math.min(2.0, p.maxDetour()));
            List<Penalty> ex = exclude;
            boolean stairs = avoidStairs;
            // 휠체어는 계단 말고도 턱·육교 등을 빼는 모드라, 사람 설정으로 계단 제외를 풀지 않는다
            boolean wheelchair = exclude.size() > 1 && exclude.contains(Penalty.STAIRS);
            if (p.stairsOk() != null && !wheelchair) {
                switch (p.stairsOk()) {
                    case 0 -> {
                        ex = new java.util.ArrayList<>(exclude);
                        if (!ex.contains(Penalty.STAIRS)) {
                            ex.add(Penalty.STAIRS);
                        }
                        stairs = false;
                    }
                    case 1 -> stairs = true;
                    default -> {
                        ex = exclude.stream().filter(x -> x != Penalty.STAIRS).toList();
                        stairs = false;
                    }
                }
            }
            return new Profile(detour, walkSpeedMPerMin, nightLeadMin, ex, stairs, avoidUnpaved, avoidNarrowSidewalk, avoidEvents,
                    day.withRestM(p.restEveryM()), night.withRestM(p.restEveryM()));
        }

        /**
         * 비 올 때 기준 (2026-11-09): 낮·밤 기준에 지붕 기준을 둔다. 우회 한도는 그대로이고, 넓힌 한도({@link Rain#widen})는
         * RouteService가 지붕 이득이 있을 때만 쓴다
         */
        public Profile rainy(Rain r) {
            if (r == null || r.walkMore() <= 0) {
                return this;
            }
            return new Profile(maxDetour, walkSpeedMPerMin, nightLeadMin, exclude, avoidStairs, avoidUnpaved, avoidNarrowSidewalk,
                    avoidEvents,
                    day.withRoof(r), night.withRoof(r));
        }

        /** 계단만 빼고 찾는 사람 (계단을 못 가는 사람 설정). 휠체어 모드와 설명 문장을 나눈다 */
        public boolean onlyStairsExcluded() {
            return exclude.size() == 1 && exclude.contains(Penalty.STAIRS);
        }

        /** 걸리는 시간(분), 이 사람의 보행 속도로 환산 */
        public int minutes(double meters) {
            return (int) Math.round(meters / walkSpeedMPerMin);
        }
    }

    /** 요청 mode를 모드 이름으로 (비어 있으면 기본 모드). 신고 뷰의 avoid_modes·cost_modes와 비교할 때도 쓴다 */
    public static String modeName(String name) {
        return name == null || name.isBlank() ? DEFAULT_PROFILE : name.trim().toLowerCase();
    }

    /** 모드 이름으로 값을 찾는다. 비어 있으면 기본 모드, 모르는 이름이면 IllegalArgumentException */
    public Profile profile(String name) {
        String key = modeName(name);
        Profile p = profiles == null ? null : profiles.get(key);
        if (p == null) {
            throw new IllegalArgumentException("mode는 " + (profiles == null ? "[]" : profiles.keySet()) + " 중 하나여야 합니다");
        }
        return p;
    }
}
