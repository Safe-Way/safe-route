package com.saferoute.assistant;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 날짜·시각 말 알아듣기 ("이번 달까지", "11월 30일", "일주일") 와 말로 읽기 ("8시 반", "11월 30일") */
final class KoreanTime {

    private static final List<String> DAYS = List.of("월", "화", "수", "목", "금", "토", "일");
    private static final Pattern TEST_DAY = Pattern.compile("^([월화수목금토일])\\s+(\\d{1,2}):(\\d{2})$");
    private static final Pattern TEST_DATE = Pattern.compile("^(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})$");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final Pattern DAY_ONLY = Pattern.compile("(\\d{1,2})\\s*일\\s*까지");
    private static final Pattern N_DAYS = Pattern.compile("(\\d{1,2})\\s*일\\s*(동안|간)");
    private static final Pattern N_WEEKS = Pattern.compile("(\\d{1,2}|한|두|세)\\s*(주|주일)");
    private static final Pattern N_MONTHS = Pattern.compile("(\\d{1,2}|한|두|세)\\s*달");

    private KoreanTime() {
    }

    /**
     * 시험 시각 "화 08:25"(today 가 든 주의 그 요일) 또는 "11-30 09:00"(올해). 모양이 틀리면 빈 값.
     */
    static Optional<LocalDateTime> testTime(String s, LocalDate today) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        Matcher m = TEST_DAY.matcher(s.strip());
        if (m.matches()) {
            DayOfWeek d = DayOfWeek.of(DAYS.indexOf(m.group(1)) + 1);
            LocalDate date = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(
                    TemporalAdjusters.nextOrSame(d));
            return Optional.of(date.atTime(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        }
        m = TEST_DATE.matcher(s.strip());
        if (m.matches()) {
            return Optional.of(LocalDate.of(today.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                    .atTime(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
        return Optional.empty();
    }

    /** 말에서 "언제까지"를 찾는다. 못 찾으면 빈 값 ("모르겠어"도 빈 값) */
    static Optional<LocalDate> until(String text, LocalDate today) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.replace(" ", "");
        if (t.contains("모르")) {
            return Optional.empty();
        }
        if (t.contains("오늘만") || t.contains("오늘까지") || t.contains("오늘은")) {
            return Optional.of(today);
        }
        if (t.contains("내일까지")) {
            return Optional.of(today.plusDays(1));
        }
        if (t.contains("이번주")) {
            return Optional.of(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
        }
        if (t.contains("이번달") || t.contains("이달")) {
            return Optional.of(today.with(TemporalAdjusters.lastDayOfMonth()));
        }
        if (t.contains("다음달말") || t.contains("다음달까지")) {
            return Optional.of(today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()));
        }
        Matcher m = MONTH_DAY.matcher(text);
        if (m.find()) {
            LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            return Optional.of(d.isBefore(today) ? d.plusYears(1) : d);
        }
        m = DAY_ONLY.matcher(text);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            LocalDate d = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));
            return Optional.of(d.isBefore(today) ? d.plusMonths(1) : d);
        }
        m = N_DAYS.matcher(text);
        if (m.find()) {
            return Optional.of(today.plusDays(Integer.parseInt(m.group(1))));
        }
        if (t.contains("일주일")) {
            return Optional.of(today.plusWeeks(1));
        }
        m = N_WEEKS.matcher(text);
        if (m.find()) {
            return Optional.of(today.plusWeeks(count(m.group(1))));
        }
        m = N_MONTHS.matcher(text);
        if (m.find()) {
            return Optional.of(today.plusMonths(count(m.group(1))));
        }
        return Optional.empty();
    }

    private static int count(String s) {
        return switch (s) {
            case "한" -> 1;
            case "두" -> 2;
            case "세" -> 3;
            default -> Integer.parseInt(s);
        };
    }

    /** 2026-11-30 → "11월 30일" */
    static String day(LocalDate d) {
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
    }

    /** 08:30 → "8시 반", 13:15 → "1시 15분" */
    static String clock(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        if (t.getMinute() == 0) {
            return h + "시";
        }
        return h + "시 " + (t.getMinute() == 30 ? "반" : t.getMinute() + "분");
    }
}
