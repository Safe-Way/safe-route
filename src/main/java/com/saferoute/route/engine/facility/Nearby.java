package com.saferoute.route.engine.facility;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 한 요청에서 쓰는 주변 시설. DB(poi)에서 한 번 읽어 경로 점수, 설명의 시설 수, 지도 아이콘이 같이 쓴다.
 *
 * @param pois     도로망 범위 안 시설
 * @param holidays 공휴일 (주말처럼 본다)
 * @param at       요청 시각 (이용 가능 판단)
 * @param dataDate 시설 데이터를 마지막으로 적재한 날. 모르면 null
 */
public record Nearby(List<Poi> pois, Set<LocalDate> holidays, LocalDateTime at, LocalDate dataDate) {

    public Nearby {
        pois = List.copyOf(pois);
        holidays = Set.copyOf(holidays);
    }
}
