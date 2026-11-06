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

    /** @param sunset "17:41" 형식 */
    public record Conditions(boolean night, String nightSource, String sunset) {

        public static Conditions of(com.saferoute.route.engine.condition.Conditions c) {
            return new Conditions(c.night(), c.nightSource(),
                    c.sunset() == null ? null : c.sunset().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
    }

    /**
     * @param kind     shortest(지도에 회색) 또는 safe(초록). 두 경로가 같으면 safe 하나만 온다
     * @param geometry [경도, 위도] 배열 (GeoJSON 순서)
     * @param counts   light·cctv·bell·help·rest·school → 경로 주변 시설 수 (rest는 지금 이용 가능한 쉼터·화장실)
     * @param sidewalks 차도를 따라 걷는 구간의 인도 쪽. 인도·보행로 같은 걷는 길은 없다
     * @param dark     밤에 조명이 적은 구간 (FR-31). 낮이면 빈 목록
     * @param turns    꺾는 곳 (FR-34)
     * @param crossings 횡단보도를 건너는 구간. 꺾지 않고 곧장 건너는 곳도 있다
     * @param covered  이 요청 기준(모드·낮밤)마다 경로가 그 범위 안을 걷는 길이(m). 예: 어린이 낮
     *                 {"school": 1905, "help": 2896} = 보호구역 300m 안 1905m, 도움 시설 150m 안 2896m.
     *                 기준이 없으면(어른 낮·휠체어 낮) 빈 맵. 출발·도착에서 길까지 잇는 부분은 넣지 않는다
     * @param stairs   계단 구간. 계단이 없으면 빈 목록
     * @param events   지나는 행사·돌발상황 영향 구간. 지나지 않으면 빈 목록
     */
    public record Route(String kind, int distanceM, int durationMin, List<double[]> geometry,
            Map<String, Integer> counts, Map<String, Integer> covered, List<Sidewalk> sidewalks, List<Stretch> dark, List<Turn> turns,
            List<Stretch> crossings, List<Stair> stairs, List<Event> events) {
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
     */
    public record Event(String kind, String title, String place, String message, String endsAt, int fromM, int toM,
            boolean avoid) {
    }

    /**
     * 차도 구간 하나의 인도 쪽 (OSM sidewalk 태그).
     *
     * @param from, to geometry 점 번호. from번 점부터 to번 점까지의 선
     * @param side     걷는 방향 기준 left, right, both(양쪽), no(인도 없음), unknown(태그 없음)
     */
    public record Sidewalk(int from, int to, String side) {
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

        /** 주의·알림을 뒤에 더한다 */
        public Explanation with(List<Note> more) {
            if (more.isEmpty()) {
                return this;
            }
            List<Note> all = new java.util.ArrayList<>(notes);
            all.addAll(more);
            return of(summary, all, extraM, extraMin, diff);
        }
    }

    /**
     * 설명에 붙는 주의·알림 한 줄. 앱은 목록으로 보여 준다.
     *
     * @param level warn(주의), info(알림), unknown(확인 안 됨)
     * @param kind  stairs(노인이 계단을 지남), event(행사·돌발), unpaved(휠체어 비포장), surface(재질 모르는 산길),
     *              snap(휠체어 출발·도착을 옆 길에 붙임)
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
