package com.saferoute.route.engine.geo;

import java.util.List;

/** 경도·위도 사각 범위. DB에서 길·시설을 범위로 읽을 때 쓴다 */
public record BBox(double minLon, double minLat, double maxLon, double maxLat) {

    /** 점들을 감싼 사각형을 사방 marginM(m)만큼 넓힌 범위 */
    public static BBox around(List<double[]> points, double marginM) {
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (double[] p : points) {
            minLon = Math.min(minLon, p[0]);
            minLat = Math.min(minLat, p[1]);
            maxLon = Math.max(maxLon, p[0]);
            maxLat = Math.max(maxLat, p[1]);
        }
        double dLat = Geo.metersToLat(marginM);
        // 위도가 높은 쪽에서 경도 1도가 더 짧으므로, 넉넉하게 높은 쪽 기준으로 넓힌다
        double dLon = Geo.metersToLon(marginM, Math.max(Math.abs(minLat), Math.abs(maxLat)));
        return new BBox(minLon - dLon, minLat - dLat, maxLon + dLon, maxLat + dLat);
    }

    public boolean contains(double lon, double lat) {
        return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    }

    /** MySQL ST_GeomFromText에 넣을 사각형. 좌표는 POINT(경도, 위도) 순서 (schema.sql) */
    public String toWkt() {
        return "POLYGON((%s %s, %s %s, %s %s, %s %s, %s %s))".formatted(
                minLon, minLat, maxLon, minLat, maxLon, maxLat, minLon, maxLat, minLon, minLat);
    }
}
