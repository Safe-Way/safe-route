package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;

/** 거리·범위 계산 */
@DisplayName("거리·범위 계산")
class GeoTests {

    @Test
    @DisplayName("두 점 거리는 MySQL ST_Distance_Sphere와 0.5m 안으로 같다")
    void distanceMatchesMysqlDistanceSphere() {
        // DatabaseConnectionTests에서 MySQL ST_Distance_Sphere가 100.02m로 잰 두 점
        assertThat(Geo.distance(126.9300, 35.1400, 126.9311, 35.1400)).isCloseTo(100.0, within(0.5));
    }

    @Test
    @DisplayName("두 점 거리는 순서와 상관없고 같은 점이면 0이다")
    void distanceIsSymmetricAndZeroForSamePoint() {
        double[] a = {126.93, 35.14};
        double[] b = {126.94, 35.15};
        assertThat(Geo.distance(a, b)).isEqualTo(Geo.distance(b, a));
        assertThat(Geo.distance(a, a)).isZero();
    }

    @Test
    @DisplayName("선 길이는 이웃한 점 사이 거리의 합이고 점 하나면 0이다")
    void lengthAddsSegments() {
        double[] a = {126.9300, 35.1400};
        double[] b = {126.9311, 35.1400};
        double[] c = {126.9311, 35.1409};
        assertThat(Geo.length(List.of(a, b, c))).isCloseTo(Geo.distance(a, b) + Geo.distance(b, c), within(1e-9));
        assertThat(Geo.length(List.of(a))).isZero();
    }

    @Test
    @DisplayName("점–선분 거리는 수직의 발이 안이면 수직 거리, 밖이면 가까운 끝점까지다")
    void distanceToSegmentPerpendicularAndBeyondEnds() {
        double[] a = {126.9300, 35.1400};
        double[] b = {126.9322, 35.1400}; // 동쪽으로 약 200m
        // 가운데에서 북쪽 약 50m: 선분까지 수직 거리
        assertThat(Geo.distanceToSegment(new double[] {126.9311, 35.14045}, a, b)).isCloseTo(50, within(1.0));
        // 선분 끝을 지나 동쪽으로 약 100m: 가까운 끝점까지 거리
        double[] past = {126.9333, 35.1400};
        assertThat(Geo.distanceToSegment(past, a, b)).isCloseTo(Geo.distance(past, b), within(1.0));
        // 길이 0인 선분은 점까지 거리
        assertThat(Geo.distanceToSegment(past, a, a)).isCloseTo(Geo.distance(past, a), within(1.0));
    }

    @Test
    @DisplayName("점–선 거리는 가장 가까운 선분까지, 점 하나인 선은 그 점까지다")
    void distanceToLineTakesClosestSegment() {
        List<double[]> line = List.of(new double[] {126.9300, 35.1400}, new double[] {126.9311, 35.1400},
                new double[] {126.9311, 35.1420});
        // 두 번째 선분(남북) 바로 옆 동쪽 약 9m
        assertThat(Geo.distanceToLine(new double[] {126.9312, 35.1410}, line)).isCloseTo(9.1, within(1.0));
        assertThat(Geo.distanceToLine(new double[] {126.9312, 35.1410}, List.of(line.get(0))))
                .isGreaterThan(100);
    }

    @Test
    @DisplayName("감싼 사각형은 사방으로 margin(m)만큼 넓힌다")
    void bboxAroundAddsMarginOnEverySide() {
        // route.yml search-margin-m에 쓰임
        BBox box = BBox.around(List.of(new double[] {126.93, 35.14}, new double[] {126.94, 35.15}), 500);

        assertThat(Geo.distance(box.minLon(), 35.14, 126.93, 35.14)).isCloseTo(500, within(5.0));
        assertThat(Geo.distance(126.93, box.minLat(), 126.93, 35.14)).isCloseTo(500, within(1.0));
        assertThat(Geo.distance(126.94, box.maxLat(), 126.94, 35.15)).isCloseTo(500, within(1.0));
        assertThat(box.contains(126.935, 35.145)).isTrue();
        assertThat(box.contains(126.95, 35.145)).isFalse();
    }

    @Test
    @DisplayName("사각형 WKT는 경도·위도 순서이고 첫 점으로 닫는다")
    void bboxWktIsLonLatOrderAndClosed() {
        BBox box = new BBox(126.9, 35.1, 127.0, 35.2);

        assertThat(box.toWkt()).isEqualTo("POLYGON((126.9 35.1, 127.0 35.1, 127.0 35.2, 126.9 35.2, 126.9 35.1))");
    }

    @Test
    @DisplayName("선 위 위치는 시작점부터 거리와 왼쪽·오른쪽으로 준다")
    void alongGivesDistanceFromStartAndSide() {
        // 동쪽으로 약 100m 가다가 북쪽으로 약 100m 꺾는 선
        List<double[]> line = List.of(new double[] {126.9300, 35.1400}, new double[] {126.9311, 35.1400},
                new double[] {126.9311, 35.1409});

        Geo.Along north = Geo.along(new double[] {126.9305, 35.1401}, line);   // 첫 구간 45% 지점(약 45.5m), 북쪽 약 11m
        Geo.Along south = Geo.along(new double[] {126.9305, 35.1399}, line);   // 같은 자리, 남쪽
        Geo.Along east = Geo.along(new double[] {126.9312, 35.1405}, line);    // 둘째 구간, 동쪽

        assertThat(north.atM()).isCloseTo(45.5, within(1.0));
        assertThat(north.offsetM()).isCloseTo(11, within(1.0));
        assertThat(north.left()).isTrue();       // 동쪽으로 갈 때 북쪽은 왼쪽
        assertThat(south.left()).isFalse();
        assertThat(east.atM()).isCloseTo(100 + 55.6, within(2.0));
        assertThat(east.left()).isFalse();       // 북쪽으로 갈 때 동쪽은 오른쪽

        // 북동쪽으로 곧게 가는 선(동·북 100m씩). 가운데(약 70.7m 지점)에서 남동쪽으로 10m 떨어진 점은 오른쪽
        List<double[]> diagonal = List.of(TestRoutes.xy(0, 0), TestRoutes.xy(100, 100));
        double side = 10 / Math.sqrt(2);
        Geo.Along beside = Geo.along(TestRoutes.xy(50 + side, 50 - side), diagonal);
        assertThat(beside.atM()).isCloseTo(50 * Math.sqrt(2), within(0.5));
        assertThat(beside.offsetM()).isCloseTo(10, within(0.5));
        assertThat(beside.left()).isFalse();
    }
}
