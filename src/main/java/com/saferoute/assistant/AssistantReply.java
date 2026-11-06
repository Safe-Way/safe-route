package com.saferoute.assistant;

import java.util.List;
import java.util.Map;

/**
 * POST /api/assistant 답 (팀 비서 설계 11/06의 say·pick·others·because·ask + 이어 가기 칸).
 *
 * @param say            비서가 하는 말 (말풍선, 음성으로 읽기). 언제나 있음
 * @param pick           추천 1등. 길을 추천하지 않은 답이면 null
 * @param others         다른 후보 (요약만)
 * @param because        왜 1등인지 이유, 3개까지 (도구 결과 숫자 그대로)
 * @param ask            사용자 답을 기다리는 질문 (되묻기·저장 전 확인). 없으면 null
 * @param choices        ask 에 붙는 버튼. 누르면 value 를 choice 로 보낸다
 * @param place          find_nearby 로 찾은 곳 ([여기로 가기] 버튼용). 없으면 null
 * @param conversationId 다음 요청에 그대로 보낼 값 (대화 이어 가기). 서버는 대화를 저장하지 않고 이 값에 담아 돌려준다
 * @param tools          이번에 부른 도구 이름 (순서대로). 시험 스크립트가 확인한다
 * @param ai             say 를 AI가 만들었으면 true, 규칙 문장틀이면 false
 * @param situation      알아챈 지금 상황 (날씨·밤·행사). 길을 추천하지 않았으면 null
 */
public record AssistantReply(String say, Pick pick, List<Other> others, List<String> because, String ask,
                             List<Choice> choices, Spot place, String conversationId, List<String> tools,
                             boolean ai, Situation situation) {

    /**
     * 추천 1등.
     *
     * @param type    walk(걸어서) 또는 transit(대중교통)
     * @param request 앱이 [이 길로 출발] 때 /api/route(walk) 또는 /api/transit(transit)에 그대로 보낼 값
     * @param summary 한 줄 요약 (예: "걸어서 18분, 1,240m")
     * @param minutes 걸리는 시간(분)
     * @param route   walk 일 때 고른 길 종류 safe 또는 shortest. transit 이면 null
     */
    public record Pick(String type, Map<String, Object> request, String summary, int minutes, String route) {
    }

    /** 다른 후보 요약 */
    public record Other(String type, String summary, int minutes) {
    }

    /** 버튼 하나. label 은 화면 글, value 는 choice 로 돌려보낼 값 */
    public record Choice(String value, String label) {
    }

    /** 찾은 시설 하나 (find_nearby) */
    public record Spot(String kind, String name, double lat, double lon, Integer distanceM, String status) {
    }

    /**
     * @param events 1등 길이 지나는 행사·돌발 이름 (원일님 엔진 routes[].events)
     */
    public record Situation(boolean night, boolean rain, boolean heat, boolean cold, Double tempC,
                            boolean weatherAvailable, boolean dustBad, boolean dustAvailable, List<String> events) {
    }
}
