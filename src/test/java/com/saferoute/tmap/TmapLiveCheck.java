package com.saferoute.tmap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 진짜 TMAP 을 한 번 불러 보는 손 확인용. 하루 1,000건을 아끼려고
 * 이름이 ...Tests 가 아니라서 mvn·CI 에서는 돌지 않고, IntelliJ 에서 직접 눌러야 돈다.
 * 키는 프로젝트 폴더의 .env 에서 읽는다.
 */
class TmapLiveCheck {

    @Test
    void chosunUnivToChosunUnivHospital() throws IOException {
        TmapWalkClient client = new TmapWalkClient(RestClient.builder(), readKeyFromEnvFile());

        // 조선대학교 → 조선대학교병원
        WalkRoute route = client.walk(35.1401, 126.9336, 35.1384, 126.9265);

        System.out.printf("거리 %dm, 시간 %d분 %d초, 좌표 %d개%n",
                route.distanceM(), route.durationS() / 60, route.durationS() % 60, route.path().size());
        assertThat(route.distanceM()).isPositive();
        assertThat(route.path()).isNotEmpty();
    }

    private static String readKeyFromEnvFile() throws IOException {
        return Files.readAllLines(Path.of(".env")).stream()
                .filter(l -> l.startsWith("TMAP_APP_KEY="))
                .map(l -> l.substring("TMAP_APP_KEY=".length()).trim())
                .findFirst()
                .orElse("");
    }
}
