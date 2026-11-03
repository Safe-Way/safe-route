package com.saferoute.route.engine.facility;

/**
 * 경로에 쓰는 시설 하나 (A파트 poi 표 한 줄: 광주 정제 시설, 출처 중복은 합침, 단속용 CCTV는 뺌).
 * 경로 점수, 설명 문장의 시설 수, 지도 아이콘이 모두 이 목록 하나를 쓴다.
 * 시각은 하루 0시부터 분으로 둔다. 24시간이면 0 ~ 1440. 모르면 null.
 *
 * @param kind       light, cctv, police, bell, store, guard_house, shelter, toilet, school_zone 등
 * @param sub        세부 종류 (파출소, 치안센터, 공원, 화장실, 무더위쉼터, 한파쉼터 등)
 * @param indoor     1 = 화장실·건물 안 (비상벨). 길에서 바로 쓸 수 없다
 * @param openFrom   여는 시각(분). openTo보다 크면 밤을 넘긴다
 * @param openDays   매일 / 평일
 * @param seasonFrom 운영 기간 시작 MM-DD (쉼터). null이면 늘
 * @param bizFrom    일반 업무·영업 시간 (원본에 시간이 없어 종류별 일반값)
 * @param bizSure    true = 기관이라 시간 밖이면 닫힘, false = 가게라 시간 밖이면 닫혔을 수 있음
 * @param placeType  장소 종류 (경로당, 행정복지센터, 은행, 가게 등). 이용 가능 근거 문장에 쓴다
 * @param id         poi.id (지도 아이콘 순서를 고정할 때)
 * @param count      이 점에 합친 수 (CCTV 카메라 대수, 등 개수)
 */
public record Poi(String kind, String sub, Integer indoor, double lon, double lat,
        Integer openFrom, Integer openTo, String openDays, String seasonFrom, String seasonTo,
        Integer bizFrom, Integer bizTo, String bizDays, Boolean bizSure, String placeType, long id, int count) {

    /** id·개수·장소 종류 없이 (시험·간단한 시설) */
    public Poi(String kind, String sub, Integer indoor, double lon, double lat,
            Integer openFrom, Integer openTo, String openDays, String seasonFrom, String seasonTo,
            Integer bizFrom, Integer bizTo, String bizDays, Boolean bizSure) {
        this(kind, sub, indoor, lon, lat, openFrom, openTo, openDays, seasonFrom, seasonTo,
                bizFrom, bizTo, bizDays, bizSure, null, 0, 1);
    }

    /** 시간 정보가 없는 시설 (등, CCTV, 보호구역 등) */
    public static Poi at(String kind, double lon, double lat) {
        return new Poi(kind, null, null, lon, lat, null, null, null, null, null, null, null, null, null);
    }

    public double[] point() {
        return new double[] {lon, lat};
    }

    public boolean isIndoor() {
        return indoor != null && indoor == 1;
    }
}
