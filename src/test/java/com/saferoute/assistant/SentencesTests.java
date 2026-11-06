package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.bus.BusArrival;
import com.saferoute.transit.TransitResult;
import com.saferoute.walk.WalkSpeed;

/** 대중교통 구간을 차례대로 말하기 (11/06 요청: 정류장·노선·정거장 수·분, 이동 수단 말 없이 시간만) */
class SentencesTests {

    static TransitResult.Stop stop(String name) {
        return new TransitResult.Stop(name, 35.14, 126.92);
    }

    static TransitResult.Leg walk(int m, int min, String from, String to) {
        return new TransitResult.Leg("walk", m, min, stop(from), stop(to), List.of(), null, null, true, null, null,
                null, List.of(), List.of());
    }

    static TransitResult.Leg bus(String line, int stations, int min, String from, String to, BusArrival... buses) {
        return new TransitResult.Leg("bus", 3000, min, stop(from), stop(to), List.of(line), null, stations, null, null,
                null, true, List.of(buses), List.of());
    }

    static TransitResult.Leg subway(String line, int stations, int min, String from, String to) {
        return new TransitResult.Leg("subway", 5000, min, stop(from), stop(to), List.of(line), "녹동", stations, null,
                null, null, true, List.of(), List.of());
    }

    static BusArrival arrival(String line, int min, boolean lowFloor) {
        return new BusArrival(1, line, min, 2, lowFloor, null, null);
    }

    @Test
    void busTripStepByStep() {
        List<TransitResult.Leg> legs = List.of(walk(350, 5, "출발", "금남로4가"),
                bus("59", 6, 10, "금남로4가", "남광주시장", arrival("59", 9, false), arrival("59", 4, true)),
                walk(200, 3, "남광주시장", "도착"));
        assertThat(Sentences.steps(legs)).isEqualTo(
                "금남로4가 정류장까지 5분 가서, 59번 버스를 타고 6정거장(10분), 남광주시장 정류장에서 내려 3분 더 가요.");
        assertThat(Sentences.nextBus(legs)).isEqualTo("다음 저상버스는 4분 뒤예요.");
    }

    @Test
    void subwayThenBusTransfer() {
        List<TransitResult.Leg> legs = List.of(walk(300, 4, "출발", "문화전당"),
                subway("1호선", 3, 6, "문화전당", "남광주"),
                walk(100, 2, "남광주", "남광주시장"),
                bus("9", 2, 4, "남광주시장", "조선대병원", arrival("9", 7, false)));
        assertThat(Sentences.steps(legs)).isEqualTo("문화전당역까지 4분 가서, 지하철 1호선을 타고 3역(6분), 남광주역에서 내려 "
                + "남광주시장 정류장까지 2분 가서, 9번 버스를 타고 2정거장(4분), 조선대병원 정류장에서 내리면 돼요.");
        assertThat(Sentences.nextBus(legs)).isNull(); // 처음 타는 게 지하철이면 버스 안내 안 함
    }

    @Test
    void stationNamesEndingWithStationAreNotDoubled() {
        List<TransitResult.Leg> legs = List.of(walk(100, 1, "출발", "광주역"), bus("1000", 4, 8, "광주역", "시청"));
        assertThat(Sentences.steps(legs)).startsWith("광주역까지 1분 가서, 1000번 버스를 타고 4정거장(8분), 시청 정류장에서 내리면 돼요.");
        assertThat(Sentences.nextBus(legs)).isNull();
    }

    @Test
    void walkOnlyLegsGiveNoSteps() {
        assertThat(Sentences.steps(List.of(walk(800, 10, "a", "b")))).isNull();
        assertThat(Sentences.steps(List.of())).isNull();
    }

    @Test
    void walkLegMinutesUseModeSpeed() {
        List<TransitResult.Leg> legs = List.of(walk(300, 4, "출발", "금남로4가"), bus("59", 6, 10, "금남로4가", "남광주시장"));
        // 휠체어 기본 50 m/분 → 300m = 6분
        List<TransitResult.Leg> slow = TripRecommender.mySpeed(legs, new WalkSpeed("wheelchair", 50, 50, 0, false));
        assertThat(slow.get(0).minutes()).isEqualTo(6);
        assertThat(slow.get(1).minutes()).isEqualTo(10);
        assertThat(TripRecommender.mySpeed(legs, null)).isEqualTo(legs);
    }
}
