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
    /** 말한 장소(도착·들를 곳·출발)를 찾지 못했거나 광주 밖이면 true. 받아쓰기 다른 후보로 다시 볼지 정할 때 씀 */
    boolean placeMissed;
    /** 알아들은 말 (뜸 들이는 말을 빼고 받아쓰기 실수를 고친 뒤, 출발지를 떼기 전) */
    String heard;
    /** 받아쓰기 다른 후보(alternatives)를 썼으면 true */
    boolean alternative;
    TripRecommender.Plan plan;
    /**
     * 답을 끝내야 하는 시각 (System.nanoTime 기준, 2026-11-16). 지나면 제미나이를 더 기다리지 않고 규칙으로 답한다.
     * 받아쓰기 다른 후보로 다시 생각해도 처음 질문을 받은 때부터 잰다. 정하지 않으면 한도 없음
     */
    long deadline = Long.MAX_VALUE;

    /** 남은 시간 ms. 한도가 없으면 Long.MAX_VALUE, 지났으면 0 이하 */
    long leftMs() {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : (deadline - System.nanoTime()) / 1_000_000;
    }

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
    /** "○○ 가는 버스"에서 맨 앞에 말한 노선 (정류장 파일에 그 노선이 있을 때만) */
    com.saferoute.bus.BusLine busLine;
    String spotMessage;
    /** "3시까지 …"처럼 말한 도착 시각. 길을 고르면 출발 시각을 알려 준다 (2026-11-23). 없으면 null */
    java.time.LocalDateTime arriveBy;
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
