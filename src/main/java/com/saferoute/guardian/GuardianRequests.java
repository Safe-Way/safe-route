package com.saferoute.guardian;

/** 보호자 위치 공유 창구가 받고 돌려주는 모양 */
public final class GuardianRequests {

    private GuardianRequests() {
    }

    /**
     * 공유 시작 (길안내를 시작할 때 "보호자에게 위치 보내기")
     *
     * @param guestId    앱의 게스트 번호 (내 기록 지우기 때 같이 지우려고 둠). 없어도 됨
     * @param walkerName 보호자 화면에 보일 이름 (예 "엄마"), 30자까지
     * @param destName   가는 곳 이름
     * @param destLat    가는 곳 위도 (있으면 남은 거리를 서버가 계산)
     * @param destLon    가는 곳 경도
     * @param mode       adult·child·senior·wheelchair
     * @param lat        지금 위치 (있으면 처음 위치로 바로 넣음)
     * @param lon        지금 위치
     */
    public record Start(String guestId, String walkerName, String destName, Double destLat, Double destLon,
                        String mode, Double lat, Double lon) {
    }

    /**
     * 시작 결과. 앱은 url 을 보호자에게 문자로 보낸다 (문자는 휴대폰이 보냄, 서버는 번호를 모름)
     *
     * @param token     위치를 올리고 끝낼 때 쓰는 공유 번호 (무작위 32자)
     * @param url       보호자가 열 웹 지도 주소
     * @param expiresAt 이때 자동으로 끝남 (시작 3시간 뒤)
     */
    public record Started(String token, String url, String expiresAt) {
    }

    /**
     * 걷는 동안 10초마다 올리는 위치
     *
     * @param remainM  남은 거리(m). 안 보내면 가는 곳 좌표로 서버가 직선거리를 계산
     * @param etaMin   도착까지 남은 분 (앱이 내 걸음 속도로 계산)
     * @param offRoute 정한 길에서 벗어났으면 true
     */
    public record Location(Double lat, Double lon, Integer remainM, Integer etaMin, Boolean offRoute) {
    }

    /** 끝내기. reason = arrived(도착) · stopped(그만둠) · sos(SOS를 눌러 끝남) */
    public record End(String reason) {
    }

    /**
     * 보호자 화면이 10초마다 읽는 값. 게스트 번호는 보여 주지 않는다
     *
     * @param status     walking(가는 중) · arrived · stopped · expired · sos
     * @param secondsAgo 마지막 위치를 받은 지 몇 초
     */
    public record View(String walkerName, String destName, Double destLat, Double destLon, String mode,
                       String status, Double lat, Double lon, String updatedAt, Long secondsAgo, Integer remainM,
                       Integer etaMin, boolean offRoute, String startedAt, String endedAt) {
    }
}
