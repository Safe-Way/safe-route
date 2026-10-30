package com.saferoute.tmap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * TMAP 보행자 경로 API 를 부른다. 우리 경로와 비교할 "보통 길"을 받아오는 용도 (FR-13).
 * 무료는 하루 1,000건, 팀 키 하나를 같이 쓴다 (.env 의 TMAP_APP_KEY).
 * 문서: https://tmapapi.tmapmobility.com/main.html#webservice/docs/tmapRoutePedestrianDoc
 */
@Component
public class TmapWalkClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient rest;
    private final String key;

    @Autowired
    public TmapWalkClient(@Value("${TMAP_APP_KEY:}") String key) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key);
    }

    /** 테스트에서 가짜 TMAP 서버를 붙일 때 쓴다 */
    TmapWalkClient(RestClient.Builder builder, String key) {
        this.rest = builder.baseUrl("https://apis.openapi.sk.com").build();
        this.key = key;
    }

    /**
     * 출발에서 도착까지 TMAP 도보 경로를 받는다.
     *
     * @throws TmapUnavailableException 키가 없거나 TMAP 이 실패했을 때
     */
    public WalkRoute walk(double fromLat, double fromLon, double toLat, double toLon) {
        if (key == null || key.isBlank()) {
            throw new TmapUnavailableException("TMAP_APP_KEY 가 .env 에 없습니다", null);
        }
        // TMAP 은 X 가 경도, Y 가 위도. 출발·도착 이름은 꼭 넣어야 하고 URL 인코딩해서 보낸다
        Map<String, Object> body = Map.of(
                "startX", fromLon, "startY", fromLat,
                "endX", toLon, "endY", toLat,
                "startName", encode("출발"), "endName", encode("도착"),
                "reqCoordType", "WGS84GEO", "resCoordType", "WGS84GEO",
                "searchOption", "0");
        try {
            JsonNode res = rest.post()
                    .uri("/tmap/routes/pedestrian?version=1&format=json")
                    .header("appKey", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return toRoute(res);
        } catch (RestClientException e) {
            throw new TmapUnavailableException("TMAP 경로 요청 실패: " + e.getMessage(), e);
        }
    }

    /**
     * TMAP 응답(GeoJSON)을 바꾼다. 첫 칸에 전체 거리·시간이 있고,
     * 선(LineString) 칸들의 좌표를 이으면 경로가 된다.
     */
    static WalkRoute toRoute(JsonNode res) {
        JsonNode features = res == null ? null : res.path("features");
        if (features == null || !features.isArray() || features.isEmpty()) {
            throw new TmapUnavailableException("TMAP 이 경로를 주지 않았습니다", null);
        }
        JsonNode summary = features.get(0).path("properties");
        int distance = summary.path("totalDistance").asInt();
        int time = summary.path("totalTime").asInt();

        List<WalkRoute.Coord> path = new ArrayList<>();
        for (int i = 0; i < features.size(); i++) {
            JsonNode geometry = features.get(i).path("geometry");
            if (!"LineString".equals(geometry.path("type").asString())) {
                continue;
            }
            JsonNode coords = geometry.path("coordinates");
            for (int j = 0; j < coords.size(); j++) {
                WalkRoute.Coord c = new WalkRoute.Coord(coords.get(j).get(1).asDouble(), coords.get(j).get(0).asDouble());
                // 선과 선이 만나는 점은 두 번 나오므로 한 번만 넣는다
                if (path.isEmpty() || !path.get(path.size() - 1).equals(c)) {
                    path.add(c);
                }
            }
        }
        return new WalkRoute(distance, time, path);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
