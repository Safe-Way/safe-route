package com.saferoute.me;

import java.util.List;

/** 내 정보·다닌 기록 창구가 주고받는 모양 */
public final class MeRequests {

    private MeRequests() {
    }

    /**
     * 내 정보 고치기. 보낸 칸만 바꾼다 (null 이면 그대로).
     *
     * @param mobilityAid  manual_wheelchair, power_wheelchair, walker, cane, stroller, none(없음)
     * @param stairsOk     0 못 감, 1 몇 칸은 괜찮음, 2 괜찮음
     * @param maxDetour    최단의 몇 배까지 돌아가도 되나 (1.0 ~ 2.0)
     * @param restEveryM   쉬어야 하는 간격 m (50 ~ 2000) — 설정 질문 ③
     * @param preferTransit 0 걷기 좋아함, 1 보통, 2 대중교통 좋아함
     * @param consentTrips 다닌 기록 남기기 동의 — 설정 질문 ⑤. false 로 바꾸면 남긴 기록도 지운다
     * @param tempMode     잠깐 쓰는 모드 (예 다리를 다쳐 wheelchair)
     * @param tempUntil    잠깐 모드가 끝나는 날 "2026-11-20". 안 보내면 7일 뒤로 두고 그날 다시 묻는다
     * @param clearTemp    true 면 잠깐 모드를 끝낸다 ("다 나았어요")
     */
    public record Update(String guestId, String mode, String mobilityAid, Integer stairsOk, Double maxDetour,
                         Integer restEveryM, Integer preferTransit, Boolean consentTrips, String tempMode,
                         String tempAid, String tempReason, String tempUntil, Boolean clearTemp, Boolean setupDone) {
    }

    /** 내 지도 시설 하나 더하기(shown true)·빼기(false). until 이 없으면 바꿀 때까지 */
    public record LayerSet(String guestId, Boolean shown, String until, String reason) {
    }

    /**
     * 길안내 시작 때 기록 한 줄 (동의한 사람만). 출발 날짜·요일·30분 시간대는 서버가 정한다.
     * 출발 좌표는 받지 않고, 도착 좌표는 약 100m 로 반올림해 둔다.
     */
    public record TripStart(String guestId, String toName, Double toLat, Double toLon, String mode, String travel,
                            String routeKind, Boolean byAssistant, String weather, Boolean night, Integer plannedMin,
                            String bodyNote) {
    }

    /** 도착·그만둠 뒤 결과와 한마디 */
    public record TripEnd(String guestId, String result, Integer actualMin, String feedback, String feedbackReason) {
    }

    public record TripStarted(long id, String startedOn, String startSlot) {
    }

    /**
     * 다닌 기록으로 내 정보를 고친 것 (PATCH /api/me/trips/{id} 답). 고친 게 없으면 빈 목록.
     *
     * @param what preferTransit 또는 stairsOk
     * @param from 고치기 전 값 (모르면 null)
     * @param to   고친 값
     * @param say  사용자에게 보여 줄 한 문장
     */
    public record Learned(String what, Integer from, int to, String say) {
    }

    public record TripEnded(java.util.List<Learned> learned) {
    }

    public record Layer(String layer, String name, boolean shown, String until, String reason) {
    }

    /**
     * 내 정보 보기. known 이 false 면 서버가 아직 모르는 사람 (처음 설정 전).
     * 게스트 번호는 다시 돌려주지 않는다.
     */
    public record View(boolean known, String modeNow, String aidNow, boolean tempActive, String mode,
                       String mobilityAid, Integer stairsOk, Double maxDetour, Integer restEveryM,
                       Integer preferTransit, String tempMode, String tempAid, String tempReason, String tempUntil,
                       boolean setupDone, boolean askToday, boolean consentTrips, int trips, List<Layer> layers) {
    }

    /** 내 기록 지우기 결과: 지운 줄 수 */
    public record Deleted(boolean profile, int trips, int mapLayers, int walkSpeeds, int guardianShares) {
    }
}
