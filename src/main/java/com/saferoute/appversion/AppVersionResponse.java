package com.saferoute.appversion;

/**
 * GET /api/app/version 응답. 앱이 켜질 때 한 번 불러서 업데이트 창을 띄울지 정한다.
 *
 * @param latestVersionCode 지금 나와 있는 가장 새 앱의 versionCode (build.gradle.kts의 숫자)
 * @param latestVersionName 사람에게 보여 줄 버전 이름 (예: "1.2")
 * @param minVersionCode    이 번호보다 낮은 앱은 꼭 업데이트해야 쓸 수 있다
 * @param message           업데이트 창에 띄울 안내 문구
 * @param downloadUrl       [업데이트]를 누르면 열 주소. 비어 있으면 창을 띄우지 않는다
 * @param update            앱이 versionCode를 보냈을 때만: "required"(꼭) · "optional"(나중에 가능) · "none".
 *                          안 보냈으면 null (앱이 위 숫자로 직접 비교)
 */
public record AppVersionResponse(int latestVersionCode,
                                 String latestVersionName,
                                 int minVersionCode,
                                 String message,
                                 String downloadUrl,
                                 String update) {
}
