package com.saferoute.ingest;

/**
 * 시설 표 한 줄. type이 들어갈 표를 정한다 ({@link FacilityTable}).
 *
 * @param unitCount 개수 칸이 있는 표(CCTV 카메라 대수, 보안등 개수)에만 쓰인다
 */
public record FacilityRow(
        String type,
        String kind,
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
