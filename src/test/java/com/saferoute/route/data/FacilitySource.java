package com.saferoute.route.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.saferoute.ingest.FacilityTable;

import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.geo.BBox;

/** 경로 주변 시설을 읽는 곳 */
public interface FacilitySource {

    List<Facility> within(BBox box, Set<FacilityTable> types);

    /** 시설 데이터를 마지막으로 적재한 날. 모르면 null */
    LocalDate dataDate();
}
