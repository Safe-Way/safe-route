package com.saferoute.route.engine.facility;

import com.saferoute.ingest.FacilityTable;

/**
 * 시설 한 개.
 *
 * @param count 등 개수·카메라 대수처럼 한 줄이 여러 개를 뜻할 때의 수. 없으면 1
 */
public record Facility(FacilityTable type, long id, double lon, double lat, int count) {

    /** {경도, 위도} */
    public double[] point() {
        return new double[] {lon, lat};
    }
}
