package com.saferoute.route.engine.facility;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 시설을 지금 쓸 수 있는지 (A파트 poi_status 뷰와 같은 규칙을 요청 시각으로 계산한다, 결정 40).
 * DB 뷰는 DB의 현재 시각만 보므로, 밤 설정을 켜거나 시험할 때도 같은 결과가 나오게 여기서 다시 계산한다.
 * 바탕 데이터는 실시간이 아니다 (적재 때의 목록, 쉼터 기간은 정부 대책 기간을 일괄로 넣은 값).
 *
 * <p>우리 규칙 (2026-11-03 결정)
 * <ul>
 *   <li>편의점은 영업시간 정보가 없어도 연 것으로 본다 (경로 점수)</li>
 *   <li>시간 정보가 없는 쉼터(경로당 한파쉼터 등)는 운영 기간 안이면 평일 09~18시에 연 것으로 본다</li>
 *   <li>그 밖에 "확인 안 됨"인 시설은 경로 점수에 세지 않는다</li>
 * </ul>
 */
public final class Availability {

    /** 시설 상태. NO_HOURS는 시간과 상관없는 시설(등·CCTV·보호구역 등) */
    public enum Status {
        AVAILABLE("이용 가능"), CLOSED("닫힘"), OFF_SEASON("운영 기간 아님"), UNKNOWN("확인 안 됨"), NO_HOURS(null);

        private final String label;

        Status(String label) {
            this.label = label;
        }

        /** 응답의 nowStatus 글자. 시간과 상관없는 시설은 null */
        public String label() {
            return label;
        }
    }

    /** 상태와 그 근거 문장 (응답의 nowStatus·nowNote) */
    public record Judgement(Status status, String note) {
    }

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
        return judge(p, at, holidays).status();
    }

    public static Judgement judge(Poi p, LocalDateTime at, Set<LocalDate> holidays) {
        boolean timed = p.kind().equals("toilet") || p.kind().equals("shelter") || p.kind().equals("bell");
        if (p.openFrom() == null && p.bizFrom() == null && p.seasonFrom() == null && !timed) {
            return new Judgement(Status.NO_HOURS, null);
        }
        LocalDate day = at.toLocalDate();
        int now = at.getHour() * 60 + at.getMinute();
        boolean offDay = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY
                || holidays.contains(day);
        boolean inSeason = inSeason(p.seasonFrom(), p.seasonTo(), day.format(MONTH_DAY));

        if (inSeason && p.openFrom() != null && !("평일".equals(p.openDays()) && offDay)) {
            Status s = within(p.openFrom(), p.openTo(), now) ? Status.AVAILABLE : Status.CLOSED;
            String what = p.kind().equals("shelter") ? "쉼터 운영 시간 " : p.kind().equals("toilet") ? "개방 시간 " : "";
            return new Judgement(s, what + hours(p.openDays(), p.openFrom(), p.openTo()));
        }
        if (p.bizFrom() != null) {
            String place = p.placeType() == null ? "" : p.placeType() + " ";
            String note = (inSeason ? "" : "쉼터 기간은 아니지만 ") + place + "일반 "
                    + (Boolean.TRUE.equals(p.bizSure()) ? "업무" : "영업") + " 시간("
                    + hours(p.bizDays(), p.bizFrom(), p.bizTo()) + ") 기준"
                    + ("평일".equals(p.bizDays()) && offDay ? ", 오늘은 주말·공휴일" : "");
            if (!("평일".equals(p.bizDays()) && offDay) && within(p.bizFrom(), p.bizTo(), now)) {
                return new Judgement(Status.AVAILABLE, note);
            }
            return Boolean.TRUE.equals(p.bizSure())
                    ? new Judgement(Status.CLOSED, note)
                    : new Judgement(Status.UNKNOWN, note + ", 그 밖의 시간엔 닫혔을 수 있음");
        }
        if (!inSeason) {
            return new Judgement(Status.OFF_SEASON,
                    (p.sub() == null ? "" : p.sub() + " ") + "운영 기간 아님 (" + p.seasonFrom() + "~" + p.seasonTo() + ")");
        }
        if (p.kind().equals("shelter") && p.openFrom() == null) {
            Status s = !offDay && within(SHELTER_FROM, SHELTER_TO, now) ? Status.AVAILABLE : Status.CLOSED;
            return new Judgement(s, "여는 시간 정보가 없어 운영 기간 중 평일 09:00~18:00으로 봄");
        }
        if ("평일".equals(p.openDays()) && offDay) {
            return new Judgement(Status.UNKNOWN, "평일에만 여는 시간이 적혀 있음");
        }
        return new Judgement(Status.UNKNOWN, "여는 시간 정보 없음");
    }

    /** "매일 09:00~18:00", 24시간이면 "24시간" */
    static String hours(String days, int from, int to) {
        if (from == 0 && to >= 24 * 60) {
            return "24시간";
        }
        return (days == null ? "" : days + " ") + hm(from) + "~" + hm(to);
    }

    private static String hm(int minutes) {
        return "%02d:%02d".formatted(minutes / 60, minutes % 60);
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
