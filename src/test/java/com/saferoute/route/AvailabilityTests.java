package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.facility.Availability;
import com.saferoute.route.engine.facility.Availability.Status;
import com.saferoute.route.engine.facility.Poi;

/** 시설을 지금 쓸 수 있는지 (A파트 poi_status와 같은 규칙 + 2026-11-03 결정) */
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
    void storeIsAlwaysCountedEvenWithoutHours() {
        Poi store = Poi.at("store", 126.93, 35.14);
        assertThat(Availability.status(store, WED_20, NO_HOLIDAY)).isEqualTo(Status.NO_HOURS);
        assertThat(Availability.usable(store, LocalDateTime.of(2026, 11, 4, 3, 0), NO_HOLIDAY)).isTrue();
    }

    @Test
    void policeBoxOpensOnlyWeekdayDaytime() {
        Poi box = poi("police", null, null, null, null, null, h(9), h(18), "평일", true);
        assertThat(Availability.status(box, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(box, WED_20, NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        assertThat(Availability.status(box, SAT_NOON, NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        // 공휴일은 주말처럼
        assertThat(Availability.status(box, WED_NOON, Set.of(WED_NOON.toLocalDate()))).isEqualTo(Status.CLOSED);
    }

    @Test
    void guardHouseShopOutsideHoursIsUnknownAndNotCounted() {
        Poi shop = poi("guard_house", null, null, null, null, null, h(9), h(19), "평일", false);
        assertThat(Availability.status(shop, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(shop, WED_20, NO_HOLIDAY)).isEqualTo(Status.UNKNOWN);
        assertThat(Availability.usable(shop, WED_20, NO_HOLIDAY)).isFalse();
    }

    @Test
    void shelterWithoutHoursOpensWeekdayDaytimeInSeasonOnly() {
        Poi cold = poi("shelter", null, null, null, "11-15", "03-15", null, null, null, null);
        assertThat(Availability.status(cold, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.OFF_SEASON);
        LocalDateTime dec = LocalDateTime.of(2026, 12, 2, 12, 0);
        assertThat(Availability.status(cold, dec, NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
        assertThat(Availability.status(cold, dec.withHour(19), NO_HOLIDAY)).isEqualTo(Status.CLOSED);
        // 해를 넘기는 기간: 1월도 기간 안
        assertThat(Availability.status(cold, LocalDateTime.of(2027, 1, 13, 10, 0), NO_HOLIDAY)).isEqualTo(Status.AVAILABLE);
    }

    @Test
    void toiletFollowsOpeningHours() {
        Poi toilet = poi("toilet", h(9), h(18), "매일", null, null, null, null, null, null);
        assertThat(Availability.usable(toilet, WED_NOON, NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(toilet, WED_20, NO_HOLIDAY)).isFalse();
        Poi allDay = poi("toilet", 0, h(24), "매일", null, null, null, null, null, null);
        assertThat(Availability.usable(allDay, LocalDateTime.of(2026, 11, 4, 23, 59), NO_HOLIDAY)).isTrue();
    }

    @Test
    void hoursPastMidnight() {
        Poi late = poi("toilet", h(22), h(4), "매일", null, null, null, null, null, null);
        assertThat(Availability.usable(late, LocalDateTime.of(2026, 11, 4, 23, 0), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(late, LocalDateTime.of(2026, 11, 4, 3, 0), NO_HOLIDAY)).isTrue();
        assertThat(Availability.usable(late, WED_NOON, NO_HOLIDAY)).isFalse();
    }

    @Test
    void bellWithoutHoursIsUnknown() {
        Poi bell = poi("bell", null, null, null, null, null, null, null, null, null);
        assertThat(Availability.status(bell, WED_NOON, NO_HOLIDAY)).isEqualTo(Status.UNKNOWN);
    }
}
