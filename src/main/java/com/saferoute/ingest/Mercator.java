package com.saferoute.ingest;

/**
 * 웹 메르카토르(EPSG:3857) x/y를 WGS84 경도·위도로 바꾼다.
 * 생활안전지도와 재난안전데이터 좌표가 이 형식이다.
 */
public final class Mercator {

    private static final double R = 6378137.0;

    private Mercator() {
    }

    /** {경도, 위도} */
    public static double[] toLonLat(double x, double y) {
        double lon = Math.toDegrees(x / R);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / R)) - Math.PI / 2);
        return new double[] {lon, lat};
    }

    /** 대한민국 육지·섬이 들어가는 넉넉한 범위인지 */
    public static boolean inKorea(double lon, double lat) {
        return lon >= 124.0 && lon <= 132.0 && lat >= 33.0 && lat <= 39.0;
    }
}
