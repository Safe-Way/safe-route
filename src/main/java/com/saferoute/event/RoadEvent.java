package com.saferoute.event;

/**
 * 길 위의 행사·돌발상황 하나 (road_event 표, 026). 지도에 영향 반경을 그릴 때 쓴다.
 * 칸 이름은 B 경로 응답 routes[].events (kind·title·place·message·endsAt)와 맞췄다.
 *
 * @param id       표 번호
 * @param kind     행사, 공사, 사고, 통제, 재난, 기타
 * @param detail   세부 종류 (돌발: 작업, 차량증가/정체 등). 없으면 null
 * @param title    화면에 보일 이름 (예: "금남로 차 없는 거리 걷자잉")
 * @param place    장소 설명 (예: "금남로+충장로 일원"). 없으면 null
 * @param lat      가운데 점 위도
 * @param lon      가운데 점 경도
 * @param radiusM  영향 반경(m). 행사 300, 돌발 100 (A가 정한 값). 지도에 원으로 그린다
 * @param startsAt 시작 "2026-11-06T10:00" (한국 시각). 모르면 null
 * @param endsAt   끝. 모르면 null (돌발은 다음 갱신까지 있다고 봄)
 * @param message  안내 문구 (돌발 "(2차로) 시설물 보수작업중", 축제 내용 앞부분). 없으면 null
 * @param url      홈페이지 (축제). 없으면 null
 */
public record RoadEvent(long id, String kind, String detail, String title, String place, double lat, double lon,
                        int radiusM, String startsAt, String endsAt, String message, String url) {
}
