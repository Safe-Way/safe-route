package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.facility.Availability;
import com.saferoute.route.engine.facility.Availability.Status;
import com.saferoute.route.engine.facility.Poi;

/** 시설을 지금 쓸 수 있는지 (A파트 poi_status와 같은 규칙 + 2026-11-03 결정) */
@DisplayName("시설 운영 여부")
class AvailabilityTests {

    static final Set<LocalDate> NO_HOLIDAY = Set.of();
    // 2026-11-04 수요일, 2026-11-07 토요일, 2026-12-02 수요일(한파쉼터 기간)
    static final LocalDateTime WED_NOON = LocalDateTime.of(2026, 11, 4, 12, 0);
    static final LocalDateTime WED_20 = LocalDateTime.of(2026, 11, 4, 20, 0);
    static final LocalDateTime SAT_NOON = LocalDateTime.of(2026, 11, 7, 12, 0);

    static Poi poi(String kind, Integer from, Integer to, String days, String seasonFrom, String seasonTo,
            Integer bizFrom, Integer bizTo, String bizDays, Boolean bizSure) {
        return new Poi(kind, null, null, 126.93, 35.14, from, to, days, seasonFrom, seasonTo, bizFrom, bizTo, bizDays, bizSure);
    }

    static int h(int hour) {
        return hour * 60;
    }

    @Test
    @DisplayName("편의점은 영업시간 정보가 없어도 연 것으로 본다")
    void storeIsAlwaysCountedEvenWithoutHours() {
        // 2026-11-03 결정
        Poi store = Poi.at("store", 126.93, 35.14);
        assertThat(Availability.status(store, WED_20, NO_HOLIDAY)).isEqualTo(Status.NO_HOURS);
        assertThat(Availability.usable(store, LocalDateTime.of(2026, 11, 4, 3, 0), NO_HOLIDAY)).isTrue();
    }

    @Test
    @DisplayName("업무 시간이 확실한 기관은 그 요일·시각에만 열고 공휴일은 주말처럼 닫힌다")
    void policeBoxOpensOnlyWeekdayDaytime() {
        Poi box = poi("police", null, null, null, null, null, h(9), h(18), "평일", true);
        assertThat(Availability.status(box, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(box, WED_20, NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        assertThat(Availability.status(box, SAT_NOON, NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        // 공휴일은 주말처럼
        assertThat(Availability.status(box, WED_NOON, Set.of(WED_NOON.toLocalDate()))).isEqualTo(Status.CLOSED);
    }

    @Test
    @DisplayName("확실하지 않은 업무 시간 밖은 '확인 안 됨'이고 세지 않는다")
    void guardHouseShopOutsideHoursIsUnknownAndNotCounted() {
        // 2026-11-03 결정
        Poi shop = poi("guard_house", null, null, null, null, null, h(9), h(19), "평일", false);
        assertThat(Availability.status(shop, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(shop, WED_20, NO_HOLIDAY)).isEqualTo(Status.UNKNOWN);
        assertThat(Availability.usable(shop, WED_20, NO_HOLIDAY)).isFalse();
    }

    @Test
    @DisplayName("운영 기간이 해를 넘겨도(11-15~03-15) 기간 안·밖을 가린다")
    void shelterSeasonSpansTheNewYear() {
        Poi cold = poi("shelter", null, null, null, "11-15", "03-15", null, null, null, null);
        assertThat(Availability.status(cold, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);
        assertThat(Availability.status(cold, LocalDateTime.of(2027, 1, 13, 10, 0), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
    }

    @Test
    @DisplayName("0~24시 운영은 23:59에도 연다")
    void allDayHoursAreOpenAt2359() {
        Poi allDay = poi("toilet", 0, h(24), "매일", null, null, null, null, null, null);
        assertThat(Availability.usable(allDay, LocalDateTime.of(2026, 11, 4, 23, 59), NO_HOLIDAY)).isTrue();
    }

    @Test
    @DisplayName("자정을 넘는 운영 시간(22~4시)은 밤에 열고 낮에 닫는다")
    void hoursPastMidnight() {
        Poi late = poi("toilet", h(22), h(4), "매일", null, null, null, null, null, null);
        assertThat(Availability.usable(late, LocalDateTime.of(2026, 11, 4, 23, 0), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(late, LocalDateTime.of(2026, 11, 4, 3, 0), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(late, WED_NOON, NO_HOLIDAY)).isFalse();
    }

    @Test
    @DisplayName("시간 정보가 없는 비상벨은 '확인 안 됨'이다")
    void bellWithoutHoursIsUnknown() {
        Poi bell = poi("bell", null, null, null, null, null, null, null, null, null);
        assertThat(Availability.status(bell, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("운영 시간이 반만 차 있으면 쓰지 않는다")
    void halfFilledHoursAreUnknown() {
        // 여는 시각만 있고 닫는 시각이 비었다
        Poi toilet = poi("toilet", h(9), null, "매일", null, null, null, null, null, null);
        assertThat(Availability.status(toilet, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.UNKNOWN);
        assertThat(Availability.usable(toilet, WED_NOON, NO_HOLIDAY)).isFalse();
        // 업무 시간도 반만 찼으면 쓰지 않는다 (확실한 기관이라 상태는 CLOSED)
        Poi both = poi("toilet", h(9), null, "매일", null, null, h(9), null, "매일", true);
        assertThat(Availability.usable(both, WED_NOON, NO_HOLIDAY)).isFalse();
    }

    @Test
    @DisplayName("운영 시간은 여는 시각을 포함하고 닫는 시각은 뺀다")
    void openingHoursStartInclusiveEndExclusive() {
        // Availability: from ≤ now < to
        Poi toilet = poi("toilet", h(9), h(18), "매일", null, null, null, null, null, null);
        LocalDateTime wed = LocalDateTime.of(2026, 11, 4, 0, 0);
        assertThat(Availability.usable(toilet, wed.withHour(8).withMinute(59), NO_HOLIDAY)).isFalse();
        assertThat(Availability.usable(toilet, wed.withHour(9), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(toilet, wed.withHour(17).withMinute(59), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(toilet, wed.withHour(18), NO_HOLIDAY)).isFalse();
    }

    @Test
    @DisplayName("평일 운영 시간은 주말·공휴일에 쓰지 않고 기간과 분 단위 시각도 가린다")
    void weekdayHoursSummerSeasonAndMinutes() {
        // 해를 넘기지 않는 기간(06-01~08-31)
        Poi weekday = poi("toilet", h(9), h(18), "평일", null, null, null, null, null, null);
        assertThat(Availability.status(weekday, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(weekday, SAT_NOON, NO_HOLIDAY)).isEqualTo(Status.UNKNOWN);

        Poi heat = poi("shelter", h(9), h(18), "매일", "06-01", "08-31", null, null, null, null);
        assertThat(Availability.status(heat, LocalDateTime.of(2026, 7, 15, 12, 0), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(heat, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);

        Poi half = poi("toilet", h(9) + 30, h(18), "매일", null, null, null, null, null, null);
        assertThat(Availability.usable(half, WED_NOON.withHour(9).withMinute(29), NO_HOLIDAY)).isFalse();
        assertThat(Availability.usable(half, WED_NOON.withHour(9).withMinute(30), NO_HOLIDAY)).isTrue();
    }

    @Test
    @DisplayName("운영 기간은 첫날과 마지막 날을 모두 포함한다")
    void seasonIncludesFirstAndLastDay() {
        Poi cold = poi("shelter", null, null, null, "11-15", "03-15", null, null, null, null);
        // 2026-11-14(토)는 기간 전, 11-15(일)는 기간 안이지만 주말이라 닫힘
        assertThat(Availability.status(cold, LocalDateTime.of(2026, 11, 14, 12, 0), NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);
        assertThat(Availability.status(cold, LocalDateTime.of(2026, 11, 15, 12, 0), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        // 2027-03-15(월)은 기간 마지막 날, 03-16(화)은 기간 뒤
        assertThat(Availability.status(cold, LocalDateTime.of(2027, 3, 15, 12, 0), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, LocalDateTime.of(2027, 3, 16, 12, 0), NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);
    }

    @Test
    @DisplayName("시간 정보 없는 쉼터는 기간 안 평일 9~18시에 연다")
    void shelterWithoutHoursIsWeekday9To18() {
        // 2026-11-03 결정, Availability.SHELTER_FROM·SHELTER_TO
        Poi cold = poi("shelter", null, null, null, "11-15", "03-15", null, null, null, null);
        LocalDateTime wed = LocalDateTime.of(2026, 12, 2, 0, 0);
        assertThat(Availability.status(cold, wed.withHour(8).withMinute(59), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        assertThat(Availability.status(cold, wed.withHour(9), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, wed.withHour(17).withMinute(59), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, wed.withHour(18), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        assertThat(Availability.status(cold, wed.withHour(12), Set.of(wed.toLocalDate()))).isEqualTo(Status.CLOSED);
    }
}
