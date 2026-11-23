package com.saferoute.appversion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 최신 5 · 최소 3 일 때 앱 번호마다 어떤 창이 뜨는지 */
class AppVersionControllerTests {

    private static final String URL = "https://example.com/anshimgil.apk";

    private final AppVersionController controller =
            new AppVersionController(5, "1.4", 3, "새 버전이 나왔어요.", URL);

    @Test
    void olderThanMinimumMustUpdate() {
        assertThat(controller.version(2).update()).isEqualTo("required");
    }

    @Test
    void betweenMinimumAndLatestMayUpdateLater() {
        assertThat(controller.version(3).update()).isEqualTo("optional");
        assertThat(controller.version(4).update()).isEqualTo("optional");
    }

    @Test
    void latestOrNewerNeedsNothing() {
        assertThat(controller.version(5).update()).isEqualTo("none");
        assertThat(controller.version(6).update()).isEqualTo("none");
    }

    @Test
    void withoutVersionCodeGivesNumbersOnly() {
        AppVersionResponse res = controller.version(null);

        assertThat(res.update()).isNull();
        assertThat(res).isEqualTo(new AppVersionResponse(5, "1.4", 3, "새 버전이 나왔어요.", URL, null));
    }

    @Test
    void noDownloadUrlMeansNoPopup() {
        AppVersionController noUrl = new AppVersionController(5, "1.4", 3, "새 버전", "  ");

        assertThat(noUrl.version(1).update()).isEqualTo("none");
        assertThat(noUrl.version(1).downloadUrl()).isEmpty();
    }

    @Test
    void minimumAboveLatestIsCapped() {
        AppVersionController wrong = new AppVersionController(5, "1.4", 9, "새 버전", URL);

        assertThat(wrong.version(5).update()).isEqualTo("none");
        assertThat(wrong.version(4).update()).isEqualTo("required");
        assertThat(wrong.version(null).minVersionCode()).isEqualTo(5);
    }
}
