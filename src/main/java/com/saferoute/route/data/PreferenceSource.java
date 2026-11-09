package com.saferoute.route.data;

/** 게스트 번호로 경로 선호를 읽는 곳 */
public interface PreferenceSource {

    /** 선호가 없으면 아무것도 바꾸지 않는다 */
    PreferenceSource NONE = guestId -> UserPreferences.NONE;

    /** 번호가 없거나 줄이 없거나 읽지 못하면 {@link UserPreferences#NONE} */
    UserPreferences find(String guestId);
}
