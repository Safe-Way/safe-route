package com.saferoute.route.engine.facility;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 시설을 지금 쓸 수 있는지 (A파트 poi_status 뷰와 같은 규칙을 요청 시각으로 계산한다).
 * DB 뷰는 DB의 현재 시각만 보므로, 밤 설정을 켜거나 시험할 때도 같은 결과가 나오게 여기서 다시 계산한다.
 * 바탕 데이터는 실시간이 아니다 (적재 때의 목록, 쉼터 기간은 정부 대책 기간을 일괄로 넣은 값).
 *
 * <p>우리 규칙 (2026-11-03 결정)
 * <ul>
 *   <li>편의점은 영업시간 정보가 없어도 연 것으로 본다</li>
 *   <li>시간 정보가 없는 쉼터(경로당 한파쉼터 등)는 운영 기간 안이면 평일 09~18시에 연 것으로 본다</li>
 *   <li>그 밖에 "확인 안 됨"인 시설은 세지 않는다</li>
 * </ul>
 */
public final class Availability {

    /** 시설 상태. NO_HOURS는 시간과 상관없는 시설(등·CCTV·보호구역 등) */
    public enum Status { AVAILABLE, CLOSED, OFF_SEASON, UNKNOWN, NO_HOURS }

    /** 시간 정보가 없는 쉼터를 연 것으로 보는 시간 (평일) */
    static final int SHELTER_FROM = 9 * 60;
    static final int SHELTER_TO = 18 * 60;

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private Availability() {
    }

    /** 경로 점수에 넣을지 */
    public static boolean usable(Poi p, LocalDateTime at, Set<LocalDate> holidays) {
        if ("store".equals(p.kind())) {
            return true;
        }
        Status s = status(p, at, holidays);
        return s == Status.AVAILABLE || s == Status.NO_HOURS;
    }

    public static Status status(Poi p, LocalDateTime at, Set<LocalDate> holidays) {
        boolean timed = p.kind().equals("toilet") || p.kind().equals("shelter") || p.kind().equals("bell");
        if (p.openFrom() == null && p.bizFrom() == null && p.seasonFrom() == null && !timed) {
            return Status.NO_HOURS;
        }
        LocalDate day = at.toLocalDate();
        int now = at.getHour() * 60 + at.getMinute();
        boolean offDay = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY
                || holidays.contains(day);
        boolean inSeason = inSeason(p.seasonFrom(), p.seasonTo(), day.format(MONTH_DAY));

        if (inSeason && p.openFrom() != null && !("평일".equals(p.openDays()) && offDay)) {
            return within(p.openFrom(), p.openTo(), now) ? Status.AVAILABLE : Status.CLOSED;
        }
        if (p.bizFrom() != null && !("평일".equals(p.bizDays()) && offDay) && within(p.bizFrom(), p.bizTo(), now)) {
            return Status.AVAILABLE;
        }
        if (p.bizFrom() != null) {
            return Boolean.TRUE.equals(p.bizSure()) ? Status.CLOSED : Status.UNKNOWN;
        }
        if (!inSeason) {
            return Status.OFF_SEASON;
        }
        if (p.kind().equals("shelter") && p.openFrom() == null) {
            return !offDay && within(SHELTER_FROM, SHELTER_TO, now) ? Status.AVAILABLE : Status.CLOSED;
        }
        return Status.UNKNOWN;
    }

    /** from ≤ to면 from ≤ now < to, 아니면(밤을 넘김) now ≥ from 또는 now < to */
    static boolean within(int from, int to, int now) {
        return from <= to ? now >= from && now < to : now >= from || now < to;
    }

    /** MM-DD 문자열 비교. 해를 넘기는 기간(11-15 ~ 03-15)도 다룬다 */
    static boolean inSeason(String from, String to, String today) {
        if (from == null || to == null) {
            return true;
        }
        return from.compareTo(to) <= 0
                ? today.compareTo(from) >= 0 && today.compareTo(to) <= 0
                : today.compareTo(from) >= 0 || today.compareTo(to) <= 0;
    }
}
