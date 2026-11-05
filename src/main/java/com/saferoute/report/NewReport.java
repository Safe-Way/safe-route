package com.saferoute.report;

import java.util.List;

/**
 * 앱이 올린 신고 한 건 (검사를 마친 값).
 *
 * @param type      유형 코드 (steep, construction, curb, broken, obstacle, other)
 * @param mode      올린 모드 (adult, child, senior, wheelchair), 없으면 null
 * @param memo      한 줄 설명, 없으면 null
 * @param accuracyM GPS 정확도(m), 없으면 null
 * @param photos    사진들 (0~3장)
 */
public record NewReport(String type, String mode, String memo, double lat, double lon, Integer accuracyM,
                        List<Photo> photos) {

    /** 사진 한 장. contentType 은 앱이 보낸 값이 아니라 파일 앞부분을 보고 서버가 정한 값 */
    public record Photo(String contentType, byte[] data) {
    }
}
