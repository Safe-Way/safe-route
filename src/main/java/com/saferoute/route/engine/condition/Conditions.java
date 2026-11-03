package com.saferoute.route.engine.condition;

import java.time.LocalTime;

/**
 * 지금 상황 판단 결과. 지금은 밤만 판단한다. 날씨(비·폭염·한파·미세먼지)는 보류.
 *
 * @param nightSource auto(일몰 계산) 또는 manual(사용자가 켜고 끔)
 * @param sunset      오늘 일몰 시각. 계산이 안 되면(극지방) null
 */
public record Conditions(boolean night, String nightSource, LocalTime sunset) {
}
