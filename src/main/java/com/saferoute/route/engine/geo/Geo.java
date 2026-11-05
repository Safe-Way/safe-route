package com.saferoute.route.engine.geo;

import java.util.List;

/**
 * 거리 계산. 좌표는 모두 WGS84 경도·위도이고 점은 {경도, 위도} 배열이다 (GeoJSON 순서).
 * 도보 경로는 몇 km 안이라, 점과 선 사이 거리는 그 자리에서 평면으로 펴서 잰다.
 */
public final class Geo {

    private static final double R = 6_371_008.8;

    private Geo() {
    }

    /** 두 점 사이 거리(m), 하버사인 */
    public static double distance(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static double distance(double[] a, double[] b) {
        return distance(a[0], a[1], b[0], b[1]);
    }

    /** 선(점 목록)의 길이(m) */
    public static double length(List<double[]> line) {
        double sum = 0;
        for (int i = 1; i < line.size(); i++) {
            sum += distance(line.get(i - 1), line.get(i));
        }
        return sum;
    }

    /** 점 p에서 선분 a-b까지 가장 가까운 거리(m) */
    public static double distanceToSegment(double[] p, double[] a, double[] b) {
        // p를 원점으로 두고 경도 1도를 그 위도의 m로 바꿔 평면에서 잰다
        double mPerLat = Math.toRadians(R);
        double mPerLon = mPerLat * Math.cos(Math.toRadians(p[1]));
        double ax = (a[0] - p[0]) * mPerLon;
        double ay = (a[1] - p[1]) * mPerLat;
        double bx = (b[0] - p[0]) * mPerLon;
        double by = (b[1] - p[1]) * mPerLat;
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double x = ax + t * dx;
        double y = ay + t * dy;
        return Math.sqrt(x * x + y * y);
    }

    /**
     * 점 p를 선 위로 내린 자리.
     *
     * @param atM     선의 처음부터 그 자리까지 선을 따라 잰 거리(m)
     * @param offsetM 점 p에서 선까지 거리(m)
     * @param left    선이 나아가는 방향에서 p가 왼쪽이면 true, 오른쪽이면 false
     */
    public record Along(double atM, double offsetM, boolean left) {
    }

    /** 점 p에서 가장 가까운 선 위 자리 (시설이 경로의 몇 m 지점·어느 쪽인지, FR-30) */
    public static Along along(double[] p, List<double[]> line) {
        double mPerLat = Math.toRadians(R);
        double mPerLon = mPerLat * Math.cos(Math.toRadians(p[1]));
        Along best = new Along(0, distance(p, line.get(0)), true);
        double walked = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            // distanceToSegment와 같이 p를 원점으로 둔 평면에서 잰다
            double ax = (a[0] - p[0]) * mPerLon;
            double ay = (a[1] - p[1]) * mPerLat;
            double dx = (b[0] - a[0]) * mPerLon;
            double dy = (b[1] - a[1]) * mPerLat;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double x = ax + t * dx;
            double y = ay + t * dy;
            double off = Math.sqrt(x * x + y * y);
            double seg = distance(a, b);
            if (off < best.offsetM()) {
                // 진행 방향 (dx, dy)와 a→p (-ax, -ay)의 외적이 양수면 p는 왼쪽
                best = new Along(walked + t * seg, off, dx * -ay - dy * -ax >= 0);
            }
            walked += seg;
        }
        return best;
    }

    /** 점 p에서 선까지 가장 가까운 거리(m) */
    public static double distanceToLine(double[] p, List<double[]> line) {
        if (line.size() == 1) {
            return distance(p, line.get(0));
        }
        double best = Double.MAX_VALUE;
        for (int i = 1; i < line.size(); i++) {
            best = Math.min(best, distanceToSegment(p, line.get(i - 1), line.get(i)));
        }
        return best;
    }

    /** m를 위도 차이(도)로 */
    static double metersToLat(double m) {
        return m / Math.toRadians(R);
    }

    /** m를 그 위도에서의 경도 차이(도)로 */
    static double metersToLon(double m, double lat) {
        return m / (Math.toRadians(R) * Math.cos(Math.toRadians(lat)));
    }
}
