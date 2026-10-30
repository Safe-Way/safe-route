package com.saferoute.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 카카오 서버로 요청 모양과 응답 바꾸기를 확인 (인터넷 없이 돈다) */
class KakaoLocalClientTests {

    private static final String KAKAO_JSON = """
            { "documents": [
              { "place_name": "조선대학교", "address_name": "광주 동구 서석동 375",
                "road_address_name": "광주 동구 필문대로 309",
                "x": "126.9336", "y": "35.1401", "distance": "12" },
              { "place_name": "조선대학교병원", "address_name": "광주 동구 학동 588",
                "road_address_name": "", "x": "126.9265", "y": "35.1384", "distance": "" }
            ] }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer kakao = MockRestServiceServer.bindTo(builder).build();
    private final KakaoLocalClient client = new KakaoLocalClient(builder, "test-key");

    @Test
    void sendsKeyAndCoordinatesAndReadsResult() {
        kakao.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com/v2/local/search/keyword.json")))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("x", "126.9336"))
                .andExpect(queryParam("y", "35.1401"))
                .andExpect(queryParam("sort", "distance"))
                .andRespond(withSuccess(KAKAO_JSON, MediaType.APPLICATION_JSON));

        List<Place> places = client.search("조선대학교", 35.1401, 126.9336);

        assertThat(places).containsExactly(
                new Place("조선대학교", "광주 동구 필문대로 309", 35.1401, 126.9336, 12),
                // 도로명 주소가 없으면 지번 주소, 거리가 비어 있으면 null
                new Place("조선대학교병원", "광주 동구 학동 588", 35.1384, 126.9265, null));
        kakao.verify();
    }

    @Test
    void kakaoErrorBecomesPlaceSearchException() {
        kakao.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com/")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.search("조선대학교", null, null))
                .isInstanceOf(PlaceSearchException.class);
    }

    @Test
    void missingKeyFailsWithoutCallingKakao() {
        KakaoLocalClient noKey = new KakaoLocalClient(builder, "");

        assertThatThrownBy(() -> noKey.search("조선대학교", null, null))
                .isInstanceOf(PlaceSearchException.class);
        kakao.verify();
    }
}
