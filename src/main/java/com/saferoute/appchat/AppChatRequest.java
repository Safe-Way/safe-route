package com.saferoute.appchat;

import java.util.List;

/**
 * 앱 화면 AI 요청 (말로 맞추기·지도 비서). 사용자가 말한 글자와 앱 설정만 받는다.
 * 이름·전화번호·위치·게스트 번호는 받지 않는다 (제미나이에 보내지 않기 위해).
 *
 * @param history  대화 (오래된 것부터). 마지막 줄이 사용자의 말
 * @param settings 지금 앱 설정 (말로 맞추기에서는 지금까지 고른 설정, 없으면 null)
 */
public record AppChatRequest(List<Line> history, Settings settings) {

    /** 대화 한 줄. fromUser 가 false 면 앱(AI)이 한 말 */
    public record Line(boolean fromUser, String text) {
    }

    /**
     * 앱 설정 (앱의 이름 그대로).
     *
     * @param mode          CHILD, SENIOR, DISABLED, GENERAL
     * @param textSize      SMALL, NORMAL, LARGE
     * @param extraMapItems 모드에 더해 지도에 보는 것 (HEAT_SHELTER 등)
     */
    public record Settings(String mode, String textSize, Boolean voiceGuide, Boolean sosButton,
                           List<String> extraMapItems) {
    }
}
