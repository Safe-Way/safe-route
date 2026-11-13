package com.saferoute.place;

import java.util.Set;

/** 검색창에 "현재 위치", "내 위치", "여기"처럼 지금 있는 곳을 친 말인지 (11-13) */
public final class HereQuery {

    private static final Set<String> WORDS = Set.of("현재위치", "현위치", "내위치", "지금위치", "여기", "이곳",
            "지금있는곳", "내가있는곳", "내현재위치", "나의위치", "내위치주소", "현재위치주소", "여기주소", "지금여기");

    private HereQuery() {
    }

    public static boolean asks(String text) {
        return text != null && WORDS.contains(text.replaceAll("[\\s?!.]", ""));
    }
}
