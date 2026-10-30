package com.saferoute.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** 진짜 카카오 대신 가짜(mock)로 검색창 규칙을 확인 */
class PlaceControllerTests {

    private final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    private final PlaceController controller = new PlaceController(kakao);

    @Test
    void returnsPlaces() {
        Place chosun = new Place("8360227", "조선대학교", "광주 동구 필문대로 309", 35.1401, 126.9336, 0);
        when(kakao.search("조선대학교", 35.1401, 126.9336)).thenReturn(List.of(chosun));

        ResponseEntity<?> res = controller.places(" 조선대학교 ", 35.1401, 126.9336);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(new PlacesResponse(List.of(chosun)));
    }

    @Test
    void rejectsShortQuery() {
        ResponseEntity<?> res = controller.places("조", null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((ErrorResponse) res.getBody()).code()).isEqualTo("BAD_PARAM");
        verifyNoInteractions(kakao);
    }

    @Test
    void rejectsLatWithoutLon() {
        ResponseEntity<?> res = controller.places("조선대", 35.14, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(kakao);
    }

    @Test
    void kakaoFailureGives503() {
        when(kakao.search(any(), any(), any())).thenThrow(new PlaceSearchException("시간 초과", null));

        ResponseEntity<?> res = controller.places("조선대학교", null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(((ErrorResponse) res.getBody()).code()).isEqualTo("SEARCH_UNAVAILABLE");
    }
}
