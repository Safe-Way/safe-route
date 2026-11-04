package com.saferoute.air;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 카카오 서버로 좌표 → 에어코리아 시도 이름 바꾸기를 확인 (인터넷 없이 돈다) */
class SidoFinderTests {

    // 카카오 coord2regioncode 응답을 줄인 것 (조선대 근처)
    private static final String REGION_JSON = """
            { "documents": [
              { "region_type": "B", "region_1depth_name": "광주광역시", "region_2depth_name": "동구" },
              { "region_type": "H", "region_1depth_name": "광주광역시", "region_2depth_name": "동구" }
            ] }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer kakao = MockRestServiceServer.bindTo(builder).build();
    private final SidoFinder finder = new SidoFinder(builder, "test-key");

    @Test
    void findsShortSidoName() {
        kakao.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com" + SidoFinder.PATH)))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("x", "126.9336")).andExpect(queryParam("y", "35.1401"))
                .andRespond(withSuccess(REGION_JSON, MediaType.APPLICATION_JSON));

        assertThat(finder.sidoAt(35.1401, 126.9336)).isEqualTo("광주");
        kakao.verify();
    }

    @Test
    void mapsEveryProvinceToAirKoreaName() {
        assertThat(SidoFinder.AIRKOREA_SIDO.get("전라남도")).isEqualTo("전남");
        assertThat(SidoFinder.AIRKOREA_SIDO.get("전북특별자치도")).isEqualTo("전북");
        assertThat(SidoFinder.AIRKOREA_SIDO.get("강원특별자치도")).isEqualTo("강원");
        assertThat(SidoFinder.AIRKOREA_SIDO.values()).containsOnly(
                "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
                "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    }

    @Test
    void unknownPlaceFails() {
        kakao.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com")))
                .andRespond(withSuccess("{ \"documents\": [] }", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> finder.sidoAt(0, 0)).isInstanceOf(AirUnavailableException.class);
    }

    @Test
    void kakaoErrorFails() {
        kakao.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com"))).andRespond(withServerError());

        assertThatThrownBy(() -> finder.sidoAt(35.1401, 126.9336)).isInstanceOf(AirUnavailableException.class);
    }

    @Test
    void missingKeyFailsWithoutCallingKakao() {
        SidoFinder noKey = new SidoFinder(builder, "");

        assertThatThrownBy(() -> noKey.sidoAt(35.1401, 126.9336)).isInstanceOf(AirUnavailableException.class);
        kakao.verify();
    }
}
