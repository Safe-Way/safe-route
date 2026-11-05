package com.saferoute.subway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.subway.SubwayRepository.TimetableRow;

/** 가짜 DB로 요일 종류·다음 열차·"지금 어느 역 근처"·자정 넘김을 확인 (DB 없이 돈다) */
class SubwayServiceTests {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 문화전당 = 역 번호 16 → 시간표 코드 104. 위쪽(녹동 쪽) 이웃: 남광주 103, 학동증심사입구 102
    private static final SubwayStation CULTURE = new SubwayStation(16, "문화전당", 35.1464, 126.9205, 120,
            3, 2, "5", 1, 1, 2, 2);

    private final SubwayRepository repo = mock(SubwayRepository.class);

    private SubwayService at(String isoSeoul) {
        Instant i = java.time.LocalDateTime.parse(isoSeoul).atZone(SEOUL).toInstant();
        return new SubwayService(repo, Clock.fixed(i, SEOUL));
    }

    private static TimetableRow row(int code, int dir, String end, String hhmm) {
        String[] p = hhmm.split(":");
        return new TimetableRow(code, dir, end, Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]));
    }

    // 평동행(1) 열차 두 대: 10:00 녹동 출발 → 10:02 소태 → 10:04 학동 → 10:06 남광주 → 10:08 문화전당
    //                      10:10 녹동 출발 → ... → 10:18 문화전당
    private static final List<TimetableRow> WEEKDAY = List.of(
            row(100, 1, "평동", "10:00"), row(101, 1, "평동", "10:02"), row(102, 1, "평동", "10:04"),
            row(103, 1, "평동", "10:06"), row(104, 1, "평동", "10:08"),
            row(100, 1, "평동", "10:10"), row(101, 1, "평동", "10:12"), row(102, 1, "평동", "10:14"),
            row(103, 1, "평동", "10:16"), row(104, 1, "평동", "10:18"),
            row(104, 1, "평동", "10:28"),
            row(104, 2, "녹동", "10:09"), row(104, 2, "소태", "10:20"));

    @Test
    void nextTrainsAndWhereTheyAreNow() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        when(repo.timetable("평일")).thenReturn(WEEKDAY);

        // 2026-11-05 목요일 10:05 → 첫 열차는 학동(10:04)을 지났고 남광주(10:06)는 아직
        SubwayNearbyResponse r = at("2026-11-05T10:05").nearby(35.1464, 126.9205);

        assertThat(r.basis()).isEqualTo("timetable");
        assertThat(r.dayType()).isEqualTo("평일");
        StationTrains s = r.stations().get(0);
        assertThat(s.elevator()).isEqualTo(new StationTrains.Facility(3, 2));
        assertThat(s.elevatorBrokenGates()).isEqualTo("5");
        assertThat(s.trains()).containsExactly(
                new SubwayTrain("평동행", "평동", "10:08", 3, "학동증심사입구", 2),
                new SubwayTrain("녹동행", "녹동", "10:09", 4, null, null),
                new SubwayTrain("평동행", "평동", "10:18", 13, null, null), // 녹동에서 10:10에 떠날 예정
                new SubwayTrain("녹동행", "소태", "10:20", 15, null, null));
    }

    @Test
    void secondTrainPositionLater() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        when(repo.timetable("평일")).thenReturn(WEEKDAY);

        // 10:13 → 두 번째 열차는 소태(10:12)를 지났고 학동(10:14)은 아직 → 3정거장 전
        SubwayNearbyResponse r = at("2026-11-05T10:13").nearby(35.1464, 126.9205);

        assertThat(r.stations().get(0).trains().get(0))
                .isEqualTo(new SubwayTrain("평동행", "평동", "10:18", 5, "소태", 3));
    }

    @Test
    void positionWorksWhenTrainsAreCloserThanTravelTime() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        // 5분 간격, 녹동→문화전당 8분 (11/05 실제 확인에서 틀렸던 경우: 앞 열차가 문화전당에 13:18 도착)
        List<TimetableRow> dense = new java.util.ArrayList<>();
        for (int start : new int[] {13 * 60 + 10, 13 * 60 + 15}) {
            for (int i = 0; i <= 4; i++) {
                int m = start + i * 2;
                dense.add(new TimetableRow(100 + i, 1, "평동", m));
            }
        }
        when(repo.timetable("평일")).thenReturn(dense);

        // 13:18 → 13:23 문화전당 도착 열차는 13:15 녹동, 13:17 소태를 지났고 학동(13:19)은 아직
        // (예전 방식은 "소태 뒤 문화전당에 처음 오는 열차"를 13:18 앞 열차로 착각해 null을 줬다)
        SubwayNearbyResponse r = at("2026-11-05T13:18").nearby(35.1464, 126.9205);

        assertThat(r.stations().get(0).trains().get(0))
                .isEqualTo(new SubwayTrain("평동행", "평동", "13:23", 5, "소태", 3));
    }

    @Test
    void trainNotYetLeftHasNoPosition() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        when(repo.timetable("평일")).thenReturn(WEEKDAY);

        SubwayNearbyResponse r = at("2026-11-05T09:50").nearby(35.1464, 126.9205);

        assertThat(r.stations().get(0).trains().get(0))
                .isEqualTo(new SubwayTrain("평동행", "평동", "10:08", 18, null, null));
    }

    @Test
    void dayTypes() {
        SubwayService s = at("2026-11-05T10:00");
        when(repo.holidayName(LocalDate.of(2026, 9, 25))).thenReturn("추석");
        when(repo.holidayName(LocalDate.of(2026, 10, 3))).thenReturn("개천절");

        assertThat(s.dayType(LocalDate.of(2026, 11, 5))).isEqualTo("평일");   // 목
        assertThat(s.dayType(LocalDate.of(2026, 11, 7))).isEqualTo("토요일"); // 토
        assertThat(s.dayType(LocalDate.of(2026, 11, 8))).isEqualTo("휴일");   // 일
        assertThat(s.dayType(LocalDate.of(2026, 10, 3))).isEqualTo("휴일");   // 개천절(토)
        assertThat(s.dayType(LocalDate.of(2026, 9, 25))).isEqualTo("명절");
    }

    @Test
    void afterMidnightUsesYesterdaysTimetable() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        // 금요일 밤 막차가 00:10 (토요일 새벽 0시 5분에 물어봄 → 금요일 = 평일 시간표)
        when(repo.timetable("평일")).thenReturn(List.of(row(104, 1, "평동", "00:10"), row(104, 1, "평동", "05:40")));

        SubwayNearbyResponse r = at("2026-11-07T00:05").nearby(35.1464, 126.9205);

        assertThat(r.dayType()).isEqualTo("평일");
        assertThat(r.stations().get(0).trains().get(0).arriveTime()).isEqualTo("00:10");
        assertThat(r.stations().get(0).trains().get(0).inMin()).isEqualTo(5);
    }

    @Test
    void emptyHolidayTableFallsBackToWeekday() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        when(repo.timetable("휴일")).thenReturn(List.of());
        when(repo.timetable("평일")).thenReturn(WEEKDAY);

        SubwayNearbyResponse r = at("2026-11-08T10:05").nearby(35.1464, 126.9205); // 일요일

        assertThat(r.dayType()).isEqualTo("평일");
        assertThat(r.stations().get(0).trains()).isNotEmpty();
    }

    @Test
    void timetableReadOnlyOnce() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        when(repo.timetable("평일")).thenReturn(WEEKDAY);
        SubwayService s = at("2026-11-05T10:05");

        s.nearby(35.1464, 126.9205);
        s.nearby(35.1464, 126.9205);

        verify(repo, times(1)).timetable("평일");
    }

    @Test
    void noStationNearbySkipsTimetable() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());

        SubwayNearbyResponse r = at("2026-11-05T10:05").nearby(37.5, 127.0);

        assertThat(r.stations()).isEmpty();
        verify(repo, never()).timetable(anyString());
    }

    @Test
    void lastTrainGoneGivesEmptyTrains() {
        when(repo.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(CULTURE));
        when(repo.timetable("평일")).thenReturn(WEEKDAY);

        SubwayNearbyResponse r = at("2026-11-05T23:50").nearby(35.1464, 126.9205);

        assertThat(r.stations().get(0).trains()).isEmpty();
    }

    @Test
    void codeOrderMatchesStationIds() {
        assertThat(new SubwayStation(1, "평동", 0, 0, 0, 0, 0, null, 0, 0, 0, 0).timetableCode()).isEqualTo(119);
        assertThat(new SubwayStation(20, "녹동", 0, 0, 0, 0, 0, null, 0, 0, 0, 0).timetableCode()).isEqualTo(100);
        assertThat(SubwayService.stationName(104)).isEqualTo("문화전당");
        assertThat(SubwayService.stationName(119)).isEqualTo("평동");
    }
}
