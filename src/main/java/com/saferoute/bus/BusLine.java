package com.saferoute.bus;

import java.time.LocalTime;

/**
 * 광주 버스 노선 하나 (A파트 bus_line 표, 019_transit.sql).
 *
 * @param from     기점 이름 (DIR_UP_NAME)
 * @param to       종점 이름 (DIR_DOWN_NAME)
 * @param everyMin 배차 간격(분). 모르면 null
 * @param lat      기점 정류장 좌표 (정류장 표에서 이름이 같은 곳). 모르면 null
 */
public record BusLine(int id, String name, String from, String to, LocalTime firstRun, LocalTime lastRun,
                      Integer everyMin, Double lat, Double lon) {

    /** "수완03 · 수완지구 ↔ 조선대 · 첫차 05:40 막차 22:20 · 15분마다" 같은 한 줄 (모르는 칸은 뺌) */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (from != null || to != null) {
            sb.append(from == null ? "?" : from).append(" ↔ ").append(to == null ? "?" : to);
        }
        if (firstRun != null && lastRun != null) {
            sb.append(sb.isEmpty() ? "" : " · ").append("첫차 ").append(hm(firstRun)).append(" 막차 ").append(hm(lastRun));
        }
        if (everyMin != null && everyMin > 0) {
            sb.append(sb.isEmpty() ? "" : " · ").append(everyMin).append("분마다");
        }
        return sb.toString();
    }

    private static String hm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
