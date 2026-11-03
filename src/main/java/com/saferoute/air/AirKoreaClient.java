package com.saferoute.air;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * 에어코리아 시도별 실시간 측정정보(getCtprvnRltmMesureDnsty)를 부른다.
 * 키는 공공데이터포털 팀 키(DATA_GO_KR_KEY, Decoding 키)를 쓴다.
 */
@Component
public class AirKoreaClient {

    static final String PATH = "/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient rest;
    private final String key;

    @Autowired
    public AirKoreaClient(@Value("${api-keys.data-go-kr:}") String key) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key);
    }

    /** 테스트에서 가짜 에어코리아 서버를 붙일 때 쓴다 */
    AirKoreaClient(RestClient.Builder builder, String key) {
        this.rest = builder.baseUrl("https://apis.data.go.kr").build();
        this.key = key;
    }

    /**
     * 시도 안 모든 측정소의 지금 값을 받는다.
     *
     * @param sidoName 에어코리아 시도 이름 (예: 광주, 서울, 전남)
     * @throws AirUnavailableException 키가 없거나 에어코리아가 실패했을 때
     */
    public List<StationAir> sido(String sidoName) {
        if (key == null || key.isBlank()) {
            throw new AirUnavailableException("DATA_GO_KR_KEY 가 .env 에 없습니다", null);
        }
        JsonNode res;
        try {
            res = rest.get()
                    .uri(b -> b.path(PATH)
                            .queryParam("serviceKey", key)
                            .queryParam("returnType", "json")
                            .queryParam("numOfRows", 100)
                            .queryParam("pageNo", 1)
                            .queryParam("sidoName", sidoName)
                            .queryParam("ver", "1.0")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new AirUnavailableException("에어코리아 요청 실패: " + e.getMessage(), e);
        }
        return toStations(res);
    }

    static List<StationAir> toStations(JsonNode res) {
        JsonNode response = res == null ? null : res.path("response");
        String code = response == null ? "" : response.path("header").path("resultCode").asString("");
        if (!"00".equals(code)) {
            String msg = response == null ? "" : response.path("header").path("resultMsg").asString("");
            throw new AirUnavailableException("에어코리아 오류 코드 " + code + " " + msg, null);
        }
        List<StationAir> stations = new ArrayList<>();
        for (JsonNode item : response.path("body").path("items")) {
            stations.add(new StationAir(
                    item.path("stationName").asString(""),
                    toNumber(item.path("pm10Value")),
                    toNumber(item.path("pm25Value")),
                    item.path("dataTime").asString("")));
        }
        return stations;
    }

    /** 에어코리아는 값을 글자로 주고, 측정 안 된 곳은 "-" 나 빈칸을 준다. 그런 건 null */
    static Integer toNumber(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString("").trim();
        if (!text.matches("\\d+")) {
            return null;
        }
        return Integer.valueOf(text);
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
