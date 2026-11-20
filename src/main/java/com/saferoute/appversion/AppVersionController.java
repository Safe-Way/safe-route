package com.saferoute.appversion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 업데이트 안내 (C 파트). 옛 버전 앱에 "업데이트해 주세요" 창을 띄우게 하는 창구.
 *
 * <p>값은 application.yml의 app-update 아래에 있고, 환경 변수(app.env)로 바꿀 수 있다.
 * 새 APK를 올리면 APP_LATEST_VERSION_CODE를 올리고, 옛 앱을 막아야 하면 APP_MIN_VERSION_CODE도 올린다.
 *
 * <pre>
 * GET /api/app/version?versionCode=3
 * → {"latestVersionCode":5,"latestVersionName":"1.4","minVersionCode":4,
 *    "message":"...","downloadUrl":"https://...","update":"required"}
 * </pre>
 */
@RestController
public class AppVersionController {

    static final String REQUIRED = "required";
    static final String OPTIONAL = "optional";
    static final String NONE = "none";

    private final int latestVersionCode;
    private final String latestVersionName;
    private final int minVersionCode;
    private final String message;
    private final String downloadUrl;

    public AppVersionController(@Value("${app-update.latest-version-code:1}") int latestVersionCode,
                                @Value("${app-update.latest-version-name:1.0}") String latestVersionName,
                                @Value("${app-update.min-version-code:1}") int minVersionCode,
                                @Value("${app-update.message:}") String message,
                                @Value("${app-update.download-url:}") String downloadUrl) {
        this.latestVersionCode = latestVersionCode;
        this.latestVersionName = latestVersionName;
        // 최소 번호를 최신보다 크게 잘못 적어도, 최신 앱까지 막히지 않게
        this.minVersionCode = Math.min(minVersionCode, latestVersionCode);
        this.message = message;
        this.downloadUrl = downloadUrl == null ? "" : downloadUrl.trim();
    }

    @GetMapping("/api/app/version")
    public AppVersionResponse version(@RequestParam(required = false) Integer versionCode) {
        String update = versionCode == null ? null : decide(versionCode);
        return new AppVersionResponse(latestVersionCode, latestVersionName, minVersionCode,
                message, downloadUrl, update);
    }

    /** 앱 번호를 보고 창 종류를 고른다. 내려받을 주소가 없으면 누를 곳이 없으니 창을 띄우지 않는다 */
    String decide(int versionCode) {
        if (downloadUrl.isEmpty()) return NONE;
        if (versionCode < minVersionCode) return REQUIRED;
        if (versionCode < latestVersionCode) return OPTIONAL;
        return NONE;
    }
}
