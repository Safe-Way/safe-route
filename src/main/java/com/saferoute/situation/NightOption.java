package com.saferoute.situation;

import java.util.Locale;

/**
 * 앱이 보내는 밤 설정 (FR-03).
 * AUTO 는 일몰 계산으로 정하고, ON·OFF 는 사용자가 직접 켜고 끈 것이다 (낮 시연 때 ON).
 */
public enum NightOption {
    AUTO, ON, OFF;

    /** "auto", "on", "off" 를 받는다. 비어 있으면 AUTO. 그 밖의 값이면 IllegalArgumentException */
    public static NightOption from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("night 는 auto, on, off 중 하나여야 합니다: " + value);
        }
    }
}
