package com.saferoute.bus;

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
 * 광주버스정보(BIS) 정류장 도착 정보(arriveInfo)를 부른다.
 * 키는 공공데이터포털 팀 키(DATA_GO_KR_KEY)를 쓴다. 정류장·노선 표를 받을 때 쓴 같은 서비스(gj_bis)다.
 * 도착 정보는 1분마다 바뀌어서 DB에 저장하지 않고 요청 때 부른다 (019_transit.sql 설명).
 */
@Component
public class GjBisClient {

    static final String PATH = "/6290000/gj_bis/arriveInfo";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient rest;
    private final String key;

    @Autowired
    public GjBisClient(@Value("${api-keys.data-go-kr:}") String key) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key);
    }

    /** 테스트에서 가짜 광주버스정보 서버를 붙일 때 쓴다 */
    GjBisClient(RestClient.Builder builder, String key) {
        this.rest = builder.baseUrl("https://apis.data.go.kr").build();
        this.key = key;
    }

    /**
     * 한 정류장에 곧 오는 버스들.
     *
     * @param busstopId bus_stop 표의 busstop_id
     * @throws BusUnavailableException 키가 없거나 광주버스정보가 실패했을 때
     */
    public List<BusArrival> arrivals(int busstopId) {
        if (key == null || key.isBlank()) {
            throw new BusUnavailableException("DATA_GO_KR_KEY 가 .env 에 없습니다", null);
        }
        JsonNode res;
        try {
            res = rest.get()
                    .uri(b -> b.path(PATH)
                            .queryParam("serviceKey", key)
                            .queryParam("BUSSTOP_ID", busstopId)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new BusUnavailableException("광주버스정보 요청 실패: " + e.getMessage(), e);
        }
        return toArrivals(res);
    }

    static List<BusArrival> toArrivals(JsonNode res) {
        JsonNode response = res == null ? null : res.path("RESPONSE");
        String code = response == null ? "" : response.path("RESULT").path("RESULT_CODE").asString("");
        if (!"SUCCESS".equals(code)) {
            String msg = response == null ? "" : response.path("RESULT").path("RESULT_MSG").asString("");
            throw new BusUnavailableException("광주버스정보 오류 " + code + " " + msg, null);
        }
        JsonNode items = response.path("ARRIVE_LIST").path("ITEM");
        List<BusArrival> out = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(item -> out.add(toArrival(item)));
        } else if (items.isObject()) {
            // 버스가 한 대뿐이면 목록이 아니라 한 개짜리로 올 수 있어서 같이 받는다
            out.add(toArrival(items));
        }
        return out;
    }

    private static BusArrival toArrival(JsonNode item) {
        return new BusArrival(
                item.path("LINE_NAME").asString(""),
                toNumber(item.path("REMAIN_MIN")),
                toNumber(item.path("REMAIN_STOP")),
                "1".equals(item.path("LOW_BUS").asString("").trim()),
                item.path("DIR_END").asString(""),
                item.path("BUSSTOP_NAME").asString(""));
    }

    /** 숫자나 숫자 글자("14")면 숫자로, 비었거나 이상하면 null */
    static Integer toNumber(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        String text = value.asString("").trim();
        return text.matches("\\d+") ? Integer.valueOf(text) : null;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
