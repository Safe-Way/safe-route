package com.saferoute.route.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;

/** 길 점수에 쓰는 시설(poi)과 공휴일을 읽는 곳 */
public interface PoiSource {

    /** 범위 안 시설 중 kinds에 든 종류 */
    List<Poi> within(BBox box, Set<String> kinds);

    /** 공휴일 (주말처럼 본다) */
    Set<LocalDate> holidays();
}
