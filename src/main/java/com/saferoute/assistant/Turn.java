package com.saferoute.assistant;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.saferoute.situation.WeatherDust;

/**
 * 질문 한 번을 처리하는 동안 쓰는 값 (요청·지금 시각·내 정보·대화·부른 도구·도구 결과).
 * 요청이 끝나면 버린다. 대화는 conversationId 로 앱에 돌려준다.
 */
final class Turn {

    static final int MAX_TOOL_CALLS = 4;
    static final java.util.Set<String> APP_MODES = java.util.Set.of("adult", "child", "senior", "wheelchair");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    final AssistantRequest req;
    /** 사용자 말. 앞에 "○○에서" 출발지를 말했으면 그 부분을 뗀 나머지 (OriginReader) */
    String text;
    final String guestId;
    final Double lat;
    final Double lon;
    final LocalDateTime now;
    /** 앱이 보낸 지금 모드 (모르는 값이면 null). 내 정보가 없을 때만 쓴다 */
    final String appMode;
    /** 시험 시각을 받았으면 true (그러면 밤도 그 시각으로 정함) */
    final boolean testTime;
    /** 시험 날씨. 없으면 null (진짜 날씨) */
    final WeatherDust testWeather;

    Conversation convo;
    Profile profile;
    boolean profileLoaded;

    final List<String> tools = new ArrayList<>();
    TripRecommender.Plan plan;
    /** 이번 질문에 제미나이가 쓴 토큰 (R1). AI를 안 불렀으면 비어 있음 */
    final TokenUsage usage = new TokenUsage();
    /** 제미나이에게 보낸 도구 이름 (R2). AI를 안 불렀으면 빈 목록 */
    List<String> toolsSent = List.of();
    /** 처음 고른 도구가 틀려서 7개로 다시 물었으면 true (R5) */
    boolean toolsRetried;
    /** [참고]로 붙인 지식 조각 주제 (R4). 없으면 빈 목록 */
    List<String> knowledgeTopics = List.of();
    /** 들를 곳 묶기 결과 (plan_stops). 없으면 null */
    StopPlanner.Plan stopPlan;
    Conversation.Place planDest;
    AssistantReply.Spot spot;
    String spotKindName;
    String spotMessage;
    /** 저장 전 확인 질문 (이게 있으면 답은 이 질문) */
    String confirm;
    /** 저장한 뒤 알릴 말 */
    final List<String> done = new ArrayList<>();
    WeatherDust weather;

    Turn(AssistantRequest req, Conversation convo, LocalDateTime now, boolean testTime, WeatherDust testWeather) {
        this.req = req;
        this.text = req.text() == null ? "" : req.text().strip();
        this.guestId = req.guestId() != null && UUID.matcher(req.guestId().strip()).matches()
                ? req.guestId().strip().toLowerCase() : null;
        this.appMode = req.mode() != null && APP_MODES.contains(req.mode().strip().toLowerCase())
                ? req.mode().strip().toLowerCase() : null;
        this.lat = req.lat();
        this.lon = req.lon();
        this.now = now;
        this.testTime = testTime;
        this.testWeather = testWeather;
        this.convo = convo;
    }

    boolean located() {
        return lat != null && lon != null;
    }

    /** 도구를 더 부를 수 있으면 true (한 번 묻는 데 4번까지) */
    boolean canCall() {
        return tools.size() < MAX_TOOL_CALLS;
    }

    /** 지금 쓸 모드: 이번 대화에서 말한 것 > 저장된 지금 모드(잠깐 바뀐 모드 포함). 모르면 null */
    /** 처음 설정 질문을 해야 하나: 내 정보도 없고 앱이 모드도 안 보냈을 때만 */
    boolean needsSetup() {
        return (profile == null || !profile.setupDone()) && appMode == null;
    }

    String mode() {
        if (convo.mode() != null) {
            return convo.mode();
        }
        if (profile != null && profile.modeNow() != null) {
            return profile.modeNow();
        }
        return appMode;
    }
}
