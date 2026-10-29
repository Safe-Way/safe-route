package com.saferoute.ingest;

/** facility 표의 한 줄 */
public record FacilityRow(
        String type,
        String subtype,
        String source,
        String sourceId,
        String name,
        String address,
        String sidoCode,
        String sggCode,
        double lon,
        double lat,
        int unitCount,
        String attrsJson) {
}
