package com.saferoute.route.data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;

/** 경로에 쓰는 시설(poi), 공휴일, 길 위의 행사·돌발상황을 읽는 곳 */
public interface PoiSource {

    /** 범위 안 시설 중 kinds에 든 종류 */
    List<Poi> within(BBox box, Set<String> kinds);

    /** 공휴일 (주말처럼 본다) */
    Set<LocalDate> holidays();

    /** 시설 데이터를 마지막으로 적재한 날. 모르면 null */
    LocalDate dataDate();

    /** 범위 안에서 at에 진행 중인 행사·돌발상황. 읽지 못하면 빈 목록 (경로는 그대로 찾는다) */
    default List<RoadEvent> events(BBox box, LocalDateTime at) {
        return List.of();
    }
}
