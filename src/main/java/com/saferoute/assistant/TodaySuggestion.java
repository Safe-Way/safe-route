package com.saferoute.assistant;

import java.util.List;

/**
 * 오늘 제안 (GET /api/suggestions/today) 답.
 *
 * @param say         앱이 첫 화면에 보여 주고 읽어 줄 말
 * @param habit       지금 시간대에 자주 가는 곳 (같은 요일·시간대 3번 이상). 없으면 null
 * @param others      같은 시간대에 자주 가는 다른 곳 (최대 2곳)
 * @param weather     지금 날씨·미세먼지 (위치를 모르면 weatherAvailable·dustAvailable 이 false)
 * @param weatherHint 이런 날씨에 내가 보통 고른 것 (예: "비 오는 날엔 보통 대중교통을 타셨어요"). 기록이 적으면 null
 * @param pick        habit 까지 오늘 추천하는 길 (위치를 알 때만). 앱이 [이 길로 출발] 때 request 를 그대로 보냄
 * @param because     pick 을 고른 이유 (최대 3개)
 */
public record TodaySuggestion(String say, Habit habit, List<Habit> others, Weather weather, String weatherHint,
                              AssistantReply.Pick pick, List<String> because) {

    /**
     * 자주 가는 곳 하나.
     *
     * @param slot  출발 시간대 (30분 단위, 예 "08:30")
     * @param times 같은 요일·시간대에 간 횟수
     * @param usual 보통 어떻게 갔나: walk, transit
     */
    public record Habit(String toName, double lat, double lon, String slot, int times, String usual) {
    }

    /** 지금 날씨. word 는 rain·heat·cold·dust·clear 중 하나 (모르면 null) */
    public record Weather(String word, boolean rain, boolean heat, boolean cold, Double tempC, boolean dustBad,
                          boolean weatherAvailable, boolean dustAvailable) {
    }
}
