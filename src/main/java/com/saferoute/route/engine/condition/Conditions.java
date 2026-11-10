package com.saferoute.route.engine.condition;

import java.time.LocalTime;

/**
 * 지금 상황 판단 결과: 밤(C파트 NightJudge)과 비(요청 rain 또는 C파트 날씨). 폭염·한파·미세먼지는 길 계산에 쓰지 않는다.
 *
 * @param nightSource auto(일몰 계산) 또는 manual(사용자가 켜고 끔)
 * @param sunset      오늘 일몰 시각. 계산이 안 되면(극지방) null
 * @param roof        지붕 있는 길 기준(ROOF)을 더했는지: 비가 오거나(auto) 요청 rain = on (2026-11-09)
 * @param roofSource  auto(C파트 날씨로 판단) 또는 manual(요청 rain on·off)
 */
public record Conditions(boolean night, String nightSource, LocalTime sunset, boolean roof, String roofSource) {

    /** 비 기준 없이 (밤만) */
    public Conditions(boolean night, String nightSource, LocalTime sunset) {
        this(night, nightSource, sunset, false, "auto");
    }
}
