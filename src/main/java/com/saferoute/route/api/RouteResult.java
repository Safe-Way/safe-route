package com.saferoute.route.api;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.saferoute.route.engine.condition.Conditions;

/**
 * POST /api/route 응답 (요구사항 8.3). 이 모양 그대로 JSON으로 내보내면 된다.
 */
public record RouteResult(
        Conditions conditions,
        List<Route> routes,
        Explanation explanation,
        List<FacilityMark> facilities,
        DataQuality dataQuality) {

    /**
     * rain·heat 등 날씨 칸은 RouteController가 C파트 날씨로 덧붙인다 (2026-11-05). roof는 그와 별개로 엔진이 비 기준을 적용했는지 (2026-11-09).
     *
     * @param sunset     "17:41" 형식
     * @param roof       지붕 있는 길 기준을 더했는지: 비가 오거나(auto) 요청 rain = on. 그러면 지붕 있는 길(지하 포함)을 먼저 찾는다
     * @param roofSource auto(C파트 날씨로 판단) 또는 manual(요청 rain on·off)
     */
    public record Conditions(boolean night, String nightSource, String sunset, boolean roof, String roofSource) {

        /** 비 기준 없이 (밤만) */
        public Conditions(boolean night, String nightSource, String sunset) {
            this(night, nightSource, sunset, false, "auto");
        }

        public static Conditions of(com.saferoute.route.engine.condition.Conditions c) {
            return new Conditions(c.night(), c.nightSource(),
                    c.sunset() == null ? null : c.sunset().format(DateTimeFormatter.ofPattern("HH:mm")),
                    c.roof(), c.roofSource());
        }
    }

    /**
     * @param kind     shortest(지도에 회색) 또는 safe(초록). 두 경로가 같으면 safe 하나만 온다
     * @param geometry [경도, 위도] 배열 (GeoJSON 순서)
     * @param counts   light·cctv·bell·help·rest·school → 경로 주변 시설 수 (rest는 지금 이용 가능한 쉼터·화장실)
     * @param sidewalks 차도를 따라 걷는 구간의 인도 쪽. 인도·보행로 같은 걷는 길은 없다
     * @param dark     밤에 조명이 적은 구간 (FR-31). 낮이면 빈 목록
     * @param turns    꺾는 곳 (FR-34)
     * @param crossings 횡단보도를 건너는 구간(꺾지 않고 곧장 건너는 곳도 있다)과 큰 길을 건너는 곳 (2026-11-12, specs/014). 거리순
     * @param covered  이 요청 기준(모드·낮밤)마다 경로가 그 범위 안을 걷는 길이(m). 예: 어린이 낮
     *                 {"school": 1905, "help": 2896} = 보호구역 300m 안 1905m, 도움 시설 150m 안 2896m.
     *                 비 오는 날 {"roof": 780} = 지붕 있는 길 780m (비 올 때만 키가 생김, 2026-11-09).
     *                 기준이 없으면(어른 낮·휠체어 낮) 빈 맵. 출발·도착에서 길까지 잇는 부분은 넣지 않는다
     * @param stairs   계단 구간. 계단이 없으면 빈 목록
     * @param events   지나는 행사·돌발상황 영향 구간. 지나지 않으면 빈 목록
     * @param unpavedM        지나는 비포장 길이(m). 휠체어 주의 문장과 같은 값 (2026-11-09, 비서 추천 점수가 씀)
     * @param surfaceUnknownM 지나는 재질 모르는 산길 길이(m)
     * @param stops    요청한 들를 곳을 거치는 순서대로 (2026-11-10). 들를 곳이 없으면 빈 목록
     */
    public record Route(String kind, int distanceM, int durationMin, List<double[]> geometry,
            Map<String, Integer> counts, Map<String, Integer> covered, List<Sidewalk> sidewalks, List<Stretch> dark, List<Turn> turns,
            List<Crossing> crossings, List<Stair> stairs, List<Event> events, int unpavedM, int surfaceUnknownM,
            List<StopMark> stops) {

        /** 들를 곳 없이 */
        public Route(String kind, int distanceM, int durationMin, List<double[]> geometry, Map<String, Integer> counts,
                Map<String, Integer> covered, List<Sidewalk> sidewalks, List<Stretch> dark, List<Turn> turns,
                List<Crossing> crossings, List<Stair> stairs, List<Event> events, int unpavedM, int surfaceUnknownM) {
            this(kind, distanceM, durationMin, geometry, counts, covered, sidewalks, dark, turns, crossings, stairs,
                    events, unpavedM, surfaceUnknownM, List.of());
        }

        /** 바닥 길이 없이 (시험·다른 파트 코드가 만드는 모양) */
        public Route(String kind, int distanceM, int durationMin, List<double[]> geometry, Map<String, Integer> counts,
                Map<String, Integer> covered, List<Sidewalk> sidewalks, List<Stretch> dark, List<Turn> turns,
                List<Crossing> crossings, List<Stair> stairs, List<Event> events) {
            this(kind, distanceM, durationMin, geometry, counts, covered, sidewalks, dark, turns, crossings, stairs,
                    events, 0, 0);
        }
    }

    /**
     * 경로가 거치는 들를 곳 (2026-11-10). 지도 번호 핀과 "다음 들를 곳까지 몇 m"에 쓴다.
     *
     * @param name, lat, lon 요청한 값 그대로 (가게 위치)
     * @param atM  출발부터 경로를 따라 그 들를 곳을 붙인 길 위 점까지 거리(m). 시설 along.atM과 같은 기준
     */
    public record StopMark(String name, double lat, double lon, int atM) {
    }

    /**
     * 건너는 곳 하나. 두 가지가 있다.
     * <ul>
     *   <li>경로가 횡단보도 길(footway=crossing)을 지나는 구간: fromM·toM만, 나머지 null</li>
     *   <li>큰 길을 건너는 곳 (specs/014): fromM = toM = 그 자리. 경로 선은 차도 가운데라 앱이 교차로 한가운데에서 쪽을 바꿔
     *       그리던 것을 이 횡단보도 자리에서 건너게 그린다. 30m 안에 그 큰 길 위 횡단보도가 없으면 보내지 않는다</li>
     * </ul>
     *
     * @param fromM, toM 출발부터 경로를 따라 잰 시작·끝(m). 시설 along.atM과 같은 기준
     * @param lat, lon   건너는 횡단보도 자리
     * @param kind       crossing(횡단보도 점) 또는 footway(횡단보도 길 가운데)
     * @param fromSide, toSide 건너기 앞뒤 큰 길에서 걷는 방향 기준 인도 쪽 (sidewalks의 side와 같은 값: left·right·both)
     */
    public record Crossing(int fromM, int toM, Double lat, Double lon, String kind, String fromSide, String toSide) {
    }

    /**
     * 경로 위 계단 (OSM highway=steps). 이어진 계단은 한 구간이다.
     *
     * @param fromM, toM 출발부터 경로를 따라 잰 시작·끝(m). 시설 along.atM과 같은 기준
     * @param ramp       휠체어 경사로. true 있음, false 없음, null 모름
     */
    public record Stair(int fromM, int toM, Boolean ramp) {
    }

    /**
     * 경로가 지나는 행사·돌발상황 (A파트 road_event). 걷는 동안 앱이 "앞에 금남로 행사로 붐벼요"를 만든다.
     *
     * @param kind    행사, 공사, 사고, 통제, 재난, 기타
     * @param title   이름 ("금남로 차 없는 거리 걷자잉", 돌발은 "도로 이름 + 종류")
     * @param place   장소 설명. 없으면 null
     * @param message 안내 문구. 없으면 null
     * @param endsAt  끝나는 때 "2026-11-07 23:59". 모르면 null
     * @param fromM   출발부터 경로를 따라 영향 반경에 들어가는 곳(m). 시설 along.atM과 같은 기준
     * @param toM     영향 반경을 나오는 곳(m)
     * @param avoid   이 모드가 둘레를 피하는 행사·돌발이면 true(앱은 주의로), 지나도 되는 것이면 false(알림만).
     *                안전 경로에 true가 있으면 피하는 길이 우회 한도를 넘어 지나는 것이다
     * @param near    출발·도착이 영향 범위 안이면 start·end·both (그 행사장에서 출발하거나 그리로 가는 길). 경로 중간에서만 지나면 null.
     *                near가 있으면 "지나간다"가 아니라 "근처에서 열리고 있다"로 알린다 (2026-11-09)
     */
    public record Event(String kind, String title, String place, String message, String endsAt, int fromM, int toM,
            boolean avoid, String near) {
    }

    /**
     * 차도 구간 하나의 인도 쪽과 앱이 경로 선을 인도 쪽으로 옮겨 그릴 거리 (2026-11-11).
     * 아래 값이 하나라도 바뀌면 구간을 나눈다.
     *
     * @param from, to geometry 점 번호. from번 점부터 to번 점까지의 선
     * @param side     걷는 방향 기준 left, right, both(양쪽), no(인도 없음), unknown(태그 없음).
     *                 sidewalk 태그가 없고 oneway가 true면 차가 가는 방향 오른쪽, 양방향(또는 모름) primary·secondary·tertiary면
     *                 both로 본다(대개 양쪽에 인도가 있음, w15). both면 앱이 내 위치 쪽으로 옮긴다
     * @param highway  길 종류 (OSM highway: primary, secondary, residential 등)
     * @param lanes    차로 수. 태그가 없으면 null
     * @param oneway   일방통행 길이면 true(oneway yes·1·true·-1), 아니라고 적혀 있으면 false, 태그가 없으면 null
     * @param widthM   차도 폭(m). 태그가 없으면 null
     * @param offsetM  선(차도 가운데)에서 인도 가운데까지(m, 소수 1자리). 차도 반폭 + 1.5.
     *                 반폭은 widthM/2, 없으면 차로 수 × 3.25/2, 차로 수도 없으면 길 종류·일방 기본 차로 수(oneway가 null이면 양방 값)로 계산해 늘 값이 있다
     */
    public record Sidewalk(int from, int to, String side, String highway, Integer lanes, Boolean oneway, Double widthM,
            double offsetM) {
    }

    /**
     * 꺾는 곳. 걷는 동안 앱이 내 위치와 비교해 "80m 앞 왼쪽", 30m 앞에서 "잠시 후 왼쪽입니다"를 만든다.
     *
     * @param atM       출발부터 경로를 따라 꺾는 곳까지 거리(m). 시설 along.atM과 같은 기준
     * @param direction "left" 또는 "right"
     * @param angle     방향이 바뀌는 정도(도, 45~180). 화살표 모양(살짝·크게 꺾음)에 쓴다
     * @param crossing  꺾은 뒤 다음 꺾는 곳(없으면 도착)까지 가는 동안 횡단보도를 건너면 true
     * @param thenM     다음 꺾는 곳이 20m 안에 이어지면 그 거리(m), 아니면 null. 앱은 둘을 한 문장으로 말한다
     *                  ("잠시 후 오른쪽 횡단보도를 건너 왼쪽입니다")
     */
    public record Turn(int atM, String direction, int angle, boolean crossing, Integer thenM) {
    }

    /**
     * 경로 위 구간. 걷는 동안 앱이 내 위치를 경로 위로 내린 거리와 비교해 "앞으로 150m는 조명이 적습니다"를 만든다.
     *
     * @param fromM 출발부터 경로를 따라 구간이 시작하는 곳(m). 시설 along.atM과 같은 기준
     * @param toM   구간이 끝나는 곳(m)
     */
    public record Stretch(int fromM, int toM) {
    }

    /**
     * @param text    summary 뒤에 notes를 괄호로 이어 붙인 한 문장 ("…(알림: …)"). 앱이 summary·notes로 옮기기 전까지 그대로 둔다
     * @param summary 다른 길을 고른 이유 한 줄. 괄호 붙임 없음 ("109m(1분) 더 돌지만 가로등 +12개, CCTV +5대")
     * @param notes   주의·알림 목록. 없으면 빈 목록
     * @param diff    안전 − 최단 시설 수
     */
    public record Explanation(String text, String summary, List<Note> notes, int extraM, int extraMin,
            Map<String, Integer> diff) {

        public static Explanation of(String summary, List<Note> notes, int extraM, int extraMin,
                Map<String, Integer> diff) {
            StringBuilder text = new StringBuilder(summary);
            notes.forEach(n -> text.append(" (").append(n.label()).append(": ").append(n.text()).append(")"));
            return new Explanation(text.toString(), summary, List.copyOf(notes), extraM, extraMin, diff);
        }

    }

    /**
     * 설명에 붙는 주의·알림 한 줄. 앱은 목록으로 보여 준다.
     *
     * @param level warn(주의), info(알림), unknown(확인 안 됨)
     * @param kind  stairs(노인이 계단을 지남), event(행사·돌발), unpaved(휠체어 비포장), surface(재질 모르는 산길),
     *              snap(휠체어 출발·도착을 옆 길에 붙임), underground(비 오는 날 지하로 지남, 2026-11-09)
     * @param text  괄호·머리말 없는 문장
     */
    public record Note(String level, String kind, String text) {

        public static Note warn(String kind, String text) {
            return new Note("warn", kind, text);
        }

        public static Note info(String kind, String text) {
            return new Note("info", kind, text);
        }

        public static Note unknown(String kind, String text) {
            return new Note("unknown", kind, text);
        }

        /** text 칸의 괄호 머리말 */
        String label() {
            return switch (level) {
                case "warn" -> "주의";
                case "unknown" -> "확인 안 됨";
                default -> "알림";
            };
        }
    }

    /**
     * 지도 아이콘용 시설.
     *
     * @param type 시설 표 종류 (CCTV, EMERGENCY_BELL, CONVENIENCE_STORE, SHELTER, TOILET, SCHOOL_ZONE, ...).
     *             SCHOOL_ZONE은 보호구역 대상 시설(학교·유치원·어린이집) 위치라, 보호구역은 그 둘레 300m다
     * @param on    safe, shortest, both
     * @param along 경로 종류(safe, shortest) → 그 경로에서의 자리. on에 든 경로만 있다
     */
    public record FacilityMark(String type, double lat, double lon, String on, Map<String, Along> along) {
    }

    /**
     * 시설이 경로의 어디에 있는지 (FR-30). 걷는 동안 "50m 앞 오른쪽 비상벨" 안내에 쓴다.
     *
     * @param atM  출발부터 경로를 따라 그 시설 옆까지 거리(m)
     * @param side 걷는 방향 기준 "left" 또는 "right"
     */
    public record Along(int atM, String side) {
    }

    /**
     * @param status   늘 OK (2026-11-06부터 데이터 부족 알림을 띄우지 않는다. 예전 값 PARTIAL·NO_DATA)
     * @param message  늘 null
     * @param dataDate 시설 데이터 기준일 "2026-10-28"
     */
    public record DataQuality(String status, String message, String dataDate) {
    }
}
