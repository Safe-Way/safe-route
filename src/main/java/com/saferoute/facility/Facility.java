package com.saferoute.facility;

import java.util.List;

/**
 * 지도·가까운 곳에 보여 줄 시설 한 개 (poi_status 뷰 한 줄, 요구사항 8.3).
 *
 * @param id         poi id
 * @param kind       시설 종류 (toilet, police, shelter, subway, subway_entrance 등. schema.sql poi 설명)
 * @param sub        세부 종류 (파출소, 한파쉼터, 지하철역 등). 없으면 null
 * @param name       화면에 보일 이름
 * @param address    주소. 없으면 null
 * @param lat        위도
 * @param lon        경도
 * @param distanceM  기준점에서 직선거리(m). 지도 범위로 꺼낼 때는 null
 * @param nowStatus  이용 가능 / 닫힘 / 운영 기간 아님 / 확인 안 됨. 시간과 상관없는 시설(CCTV·보호구역 등)은 null
 * @param nowNote    무엇으로 판단했는지 (개방 시간, 일반 업무 시간 기준 등). 없으면 null
 * @param wheelchair 휠체어: 장애인 화장실·지나갈 수 있는 육교·보도턱 낮춘 횡단보도·엘리베이터 있는 역·출입구면 true.
 *                   모르면 null
 * @param count      이 점에 합친 수 (CCTV 대수 등). 지하철역은 엘리베이터 대수
 * @param modes      지도에 먼저 보일 모드 (common, child, senior, wheelchair)
 * @param detail     앱에 바로 띄울 짧은 설명
 */
public record Facility(long id, String kind, String sub, String name, String address, double lat, double lon,
        Integer distanceM, String nowStatus, String nowNote, Boolean wheelchair, int count, List<String> modes,
        String detail) {
}
