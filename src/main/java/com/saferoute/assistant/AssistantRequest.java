package com.saferoute.assistant;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * POST /api/assistant 요청 (팀 비서 설계 11/06, docs/assistant).
 *
 * @param guestId        앱이 처음 실행 때 만든 무작위 번호 (user_profile·walk_profile 과 같은 번호). 없으면 내 정보 없이 답함
 * @param text           사용자가 말하거나 쓴 글. 비어 있거나 "(오늘 제안)"이면 비서가 먼저 말을 건다 (알림)
 * @param lat            지금 위치 위도. 아직 모르면 null (그러면 위치를 부탁한다)
 * @param lon            지금 위치 경도
 * @param now            앱의 지금 시각 "2026-11-09T08:05" (기록용)
 * @param conversationId 지난번 답의 conversationId 를 그대로 (같은 대화를 이어 갈 때). 처음이면 null
 * @param choice         지난번 답의 choices 버튼을 눌렀으면 그 value
 * @param favorites      휴대폰에 저장한 자주 가는 곳 (집·학교 등). 집 좌표는 휴대폰에만 있어서 앱이 보내 준다
 * @param test           시험용 날씨·시각·AI 끄기 (scripts/eval_assistant.py). 앱은 보내지 않는다
 * @param mode           앱에서 지금 고른 모드 (adult·child·senior·wheelchair). 서버에 내 정보가 없으면 이 값을 지금 모드로 쓰고
 *                       처음 설정 질문을 건너뛴다 (민수 요청 11/06). 내 정보가 있으면 내 정보가 먼저
 * @param alternatives   음성 받아쓰기의 다른 후보 (좋은 순서, text 다음 것부터). 글로 쓴 말이면 빈 목록이나 null (민수 11-12)
 */
public record AssistantRequest(String guestId, String text, Double lat, Double lon, String now,
                               String conversationId, String choice, List<Favorite> favorites, Test test,
                               String mode, List<String> alternatives) {

    @JsonCreator
    public AssistantRequest {
    }

    /** 받아쓰기 후보 없이 (예전 모양, 시험 코드용) */
    public AssistantRequest(String guestId, String text, Double lat, Double lon, String now,
                            String conversationId, String choice, List<Favorite> favorites, Test test, String mode) {
        this(guestId, text, lat, lon, now, conversationId, choice, favorites, test, mode, null);
    }

    /** 같은 요청에서 말만 바꾼 것 (받아쓰기 다른 후보로 다시 볼 때) */
    AssistantRequest withText(String other) {
        return new AssistantRequest(guestId, other, lat, lon, now, conversationId, choice, favorites, test, mode,
                alternatives);
    }

    /** 자주 가는 곳 하나 */
    public record Favorite(String name, Double lat, Double lon) {
    }

    /**
     * @param weather clear, rain, heat, cold, dust 중 하나. 주면 진짜 날씨 대신 이 값
     * @param time    "화 08:25"(이번 주 그 요일) 또는 "11-30 09:00". 주면 지금 시각 대신 이 값
     * @param ai      "down"이면 AI를 부르지 않고 규칙만으로 답함 (AI가 멈췄을 때 시험).
     *                "all_tools"면 말에 맞는 도구만 고르지 않고 7개 다 보냄 (토큰 줄이기 전과 비교)
     */
    public record Test(String weather, String time, String ai) {
    }

    static AssistantRequest empty() {
        return new AssistantRequest(null, null, null, null, null, null, null, null, null, null);
    }
}
