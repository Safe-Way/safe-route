package com.saferoute.place;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로컬 API 키워드 검색을 서버가 대신 부른다 (키를 앱에 넣지 않기 위해, NFR-05).
 * 문서: https://developers.kakao.com/docs/latest/ko/local/dev-guide#search-by-keyword
 */
@Component
public class KakaoLocalClient {

    static final int MAX_RESULTS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient rest;
    private final String key;

    @Autowired
    public KakaoLocalClient(@Value("${api-keys.kakao-rest:}") String key) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key);
    }

    /** 테스트에서 가짜 카카오 서버를 붙일 때 쓴다 */
    KakaoLocalClient(RestClient.Builder builder, String key) {
        this.rest = builder.baseUrl("https://dapi.kakao.com").build();
        this.key = key;
    }

    /**
     * 키워드로 장소를 찾는다. lat·lon 을 주면 가까운 순, 안 주면 카카오 정확도 순.
     *
     * @throws PlaceSearchException 키가 없거나 카카오가 실패했을 때
     */
    public List<Place> search(String query, Double lat, Double lon) {
        if (key == null || key.isBlank()) {
            throw new PlaceSearchException("KAKAO_REST_KEY 가 .env 에 없습니다", null);
        }
        boolean near = lat != null && lon != null;
        try {
            KakaoResponse res = rest.get()
                    .uri(b -> {
                        b.path("/v2/local/search/keyword.json")
                                .queryParam("query", query)
                                .queryParam("size", MAX_RESULTS);
                        if (near) {
                            // 카카오는 x 가 경도, y 가 위도
                            b.queryParam("x", lon).queryParam("y", lat).queryParam("sort", "distance");
                        }
                        return b.build();
                    })
                    .header("Authorization", "KakaoAK " + key)
                    .retrieve()
                    .body(KakaoResponse.class);
            if (res == null || res.documents() == null) {
                return List.of();
            }
            return res.documents().stream().map(KakaoLocalClient::toPlace).toList();
        } catch (RestClientException e) {
            throw new PlaceSearchException("카카오 검색 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 주소로 찾는다 ("광주 동구 서석동 3", "서석동 3", "충장로 45"). 이름과 주소 칸 모두 카카오가 준 주소 글자.
     * 문서: https://developers.kakao.com/docs/latest/ko/local/dev-guide#address-coord
     *
     * @throws PlaceSearchException 키가 없거나 카카오가 실패했을 때
     */
    public List<Place> searchAddress(String query) {
        if (key == null || key.isBlank()) {
            throw new PlaceSearchException("KAKAO_REST_KEY 가 .env 에 없습니다", null);
        }
        try {
            AddressResponse res = rest.get()
                    .uri(b -> b.path("/v2/local/search/address.json")
                            .queryParam("query", query)
                            .queryParam("size", MAX_RESULTS)
                            .build())
                    .header("Authorization", "KakaoAK " + key)
                    .retrieve()
                    .body(AddressResponse.class);
            if (res == null || res.documents() == null) {
                return List.of();
            }
            return res.documents().stream()
                    .filter(d -> !isBlank(d.address_name()) && !isBlank(d.x()) && !isBlank(d.y()))
                    .map(d -> new Place(null, d.address_name(), d.address_name(), Double.parseDouble(d.y()),
                            Double.parseDouble(d.x()), null))
                    .toList();
        } catch (RestClientException e) {
            throw new PlaceSearchException("카카오 주소 검색 실패: " + e.getMessage(), e);
        }
    }

    static Place toPlace(KakaoDocument d) {
        String address = isBlank(d.road_address_name()) ? d.address_name() : d.road_address_name();
        Integer distance = isBlank(d.distance()) ? null : Integer.valueOf(d.distance());
        return new Place(d.id(), d.place_name(), address, Double.parseDouble(d.y()), Double.parseDouble(d.x()), distance);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }

    /** 카카오 응답에서 쓰는 칸만. 이름은 카카오 JSON 그대로 (id 장소 id, x 경도, y 위도, distance 는 m 문자열) */
    record KakaoResponse(List<KakaoDocument> documents) {
    }

    /** 주소 검색 응답에서 쓰는 칸만 (address_name 전체 주소, x 경도, y 위도) */
    record AddressResponse(List<AddressDocument> documents) {
    }

    record AddressDocument(String address_name, String x, String y) {
    }

    record KakaoDocument(String id, String place_name, String address_name, String road_address_name,
                         String x, String y, String distance) {
    }
}
