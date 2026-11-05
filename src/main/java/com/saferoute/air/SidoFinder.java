package com.saferoute.air;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * 위도·경도가 어느 시도인지 카카오 좌표→행정구역 API(coord2regioncode)로 찾아서,
 * 에어코리아가 쓰는 짧은 시도 이름(예: 광주)으로 바꿔 준다.
 * 문서: https://developers.kakao.com/docs/latest/ko/local/dev-guide#coord-to-district
 */
@Component
public class SidoFinder {

    static final String PATH = "/v2/local/geo/coord2regioncode.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** 카카오 시도 이름 → 에어코리아 시도 이름 (옛 이름도 같이 넣어 둠) */
    static final Map<String, String> AIRKOREA_SIDO = Map.ofEntries(
            Map.entry("서울특별시", "서울"), Map.entry("부산광역시", "부산"),
            Map.entry("대구광역시", "대구"), Map.entry("인천광역시", "인천"),
            Map.entry("광주광역시", "광주"), Map.entry("대전광역시", "대전"),
            Map.entry("울산광역시", "울산"), Map.entry("세종특별자치시", "세종"),
            Map.entry("경기도", "경기"), Map.entry("강원특별자치도", "강원"), Map.entry("강원도", "강원"),
            Map.entry("충청북도", "충북"), Map.entry("충청남도", "충남"),
            Map.entry("전북특별자치도", "전북"), Map.entry("전라북도", "전북"), Map.entry("전라남도", "전남"),
            Map.entry("경상북도", "경북"), Map.entry("경상남도", "경남"),
            Map.entry("제주특별자치도", "제주"));

    /** 광주·전남이 합쳐진 뒤 카카오가 주는 시도 이름. 에어코리아는 아직 광주·전남으로 나눠 부른다고 보고 구·시·군으로 가른다 */
    static final String MERGED_GWANGJU_JEONNAM = "전남광주통합특별시";
    /** 옛 광주광역시의 구 */
    static final Set<String> GWANGJU_GU = Set.of("동구", "서구", "남구", "북구", "광산구");

    private final RestClient rest;
    private final String key;

    @Autowired
    public SidoFinder(@Value("${api-keys.kakao-rest:}") String key) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key);
    }

    /** 테스트에서 가짜 카카오 서버를 붙일 때 쓴다 */
    SidoFinder(RestClient.Builder builder, String key) {
        this.rest = builder.baseUrl("https://dapi.kakao.com").build();
        this.key = key;
    }

    /**
     * @return 에어코리아 시도 이름 (예: 광주)
     * @throws AirUnavailableException 키가 없거나, 카카오가 실패했거나, 시도를 모를 때
     */
    public String sidoAt(double lat, double lon) {
        if (key == null || key.isBlank()) {
            throw new AirUnavailableException("KAKAO_REST_KEY 가 .env 에 없습니다", null);
        }
        JsonNode res;
        try {
            res = rest.get()
                    .uri(b -> b.path(PATH).queryParam("x", lon).queryParam("y", lat).build())
                    .header("Authorization", "KakaoAK " + key)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new AirUnavailableException("카카오 행정구역 요청 실패: " + e.getMessage(), e);
        }
        JsonNode doc = res == null ? null : res.path("documents").path(0);
        String region = doc == null ? "" : doc.path("region_1depth_name").asString("");
        if (MERGED_GWANGJU_JEONNAM.equals(region)) {
            return gwangjuOrJeonnam(doc.path("region_2depth_name").asString(""));
        }
        String sido = AIRKOREA_SIDO.get(region);
        if (sido == null) {
            throw new AirUnavailableException("시도를 알 수 없어요: " + region, null);
        }
        return sido;
    }

    /** 통합시 안에서 옛 광주의 구(예: 동구, 광주 동구)면 광주, 나머지 시·군이면 전남 */
    static String gwangjuOrJeonnam(String district) {
        String d = district.trim();
        if (d.startsWith("광주")) {
            return "광주";
        }
        return GWANGJU_GU.contains(d) ? "광주" : "전남";
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
