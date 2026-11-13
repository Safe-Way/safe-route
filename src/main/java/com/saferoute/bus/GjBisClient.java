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
 * 광주버스정보(BIS)의 정류장 도착 정보(arriveInfo)와 노선 버스 위치(busLocationInfo)를 부른다.
 * 키는 공공데이터포털 팀 키(DATA_GO_KR_KEY)를 쓴다. 정류장·노선 표를 받을 때 쓴 같은 서비스(gj_bis)다.
 * 도착 정보는 1분마다 바뀌어서 DB에 저장하지 않고 요청 때 부른다 (019_transit.sql 설명).
 */
@Component
public class GjBisClient {

    static final String PATH = "/6290000/gj_bis/arriveInfo";
    static final String LOCATION_PATH = "/6290000/gj_bis/busLocationInfo";
    static final String LINE_STOPS_PATH = "/6290000/gj_bis/lineStationInfo";
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
        return toArrivals(call(PATH, "BUSSTOP_ID", busstopId));
    }

    /**
     * 한 노선 위를 지금 달리는 버스들 (정류장 번호 단위). 차고지에 있는 버스(정류장 0)는 뺀다.
     *
     * @param lineId bus_line 표의 line_id (arriveInfo의 LINE_ID와 같다)
     * @throws BusUnavailableException 키가 없거나 광주버스정보가 실패했을 때
     */
    public List<BusOnLine> locations(int lineId) {
        return toLocations(call(LOCATION_PATH, "LINE_ID", lineId));
    }

    /**
     * 한 노선이 지나는 정류장들, 순서대로 (11-13 "수완동 지나가는 버스"). 좌표 없는 정류장은 뺀다.
     *
     * @param lineId bus_line 표의 line_id
     * @throws BusUnavailableException 키가 없거나 광주버스정보가 실패했을 때
     */
    public List<LineStop> lineStops(int lineId) {
        return toLineStops(call(LINE_STOPS_PATH, "LINE_ID", lineId));
    }

    static List<LineStop> toLineStops(JsonNode res) {
        JsonNode response = successOrThrow(res);
        List<LineStop> out = new ArrayList<>();
        for (JsonNode item : items(response.path("BUSSTOP_LIST"))) {
            Integer stop = toNumber(item.path("BUSSTOP_ID"));
            Integer seq = toNumber(item.path("BUSSTOP_NUM"));
            JsonNode lat = item.path("LATITUDE");
            JsonNode lon = item.path("LONGITUDE");
            if (stop == null || !lat.isNumber() || !lon.isNumber()) {
                continue;
            }
            out.add(new LineStop(seq == null ? out.size() + 1 : seq, stop, item.path("BUSSTOP_NAME").asString(""),
                    lat.asDouble(), lon.asDouble()));
        }
        return out;
    }

    /** 키를 붙여 광주버스정보 한 곳을 부른다. 실패는 모두 BusUnavailableException으로 바꾼다 */
    private JsonNode call(String path, String param, int value) {
        if (key == null || key.isBlank()) {
            throw new BusUnavailableException("DATA_GO_KR_KEY 가 .env 에 없습니다", null);
        }
        try {
            return rest.get()
                    .uri(b -> b.path(path)
                            .queryParam("serviceKey", key)
                            .queryParam(param, value)
                            .queryParam("resultType", "json")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new BusUnavailableException("광주버스정보 요청 실패: " + e.getMessage(), e);
        }
    }

    /** RESULT_CODE가 SUCCESS인지 보고, 맞으면 RESPONSE 칸을 돌려준다 */
    private static JsonNode successOrThrow(JsonNode res) {
        JsonNode response = res == null ? null : res.path("RESPONSE");
        String code = response == null ? "" : response.path("RESULT").path("RESULT_CODE").asString("");
        if (!"SUCCESS".equals(code)) {
            String msg = response == null ? "" : response.path("RESULT").path("RESULT_MSG").asString("");
            throw new BusUnavailableException("광주버스정보 오류 " + code + " " + msg, null);
        }
        return response;
    }

    /** ITEM이 목록이든 한 개짜리든 한 줄씩 넘긴다 (버스가 한 대뿐이면 목록이 아니라 한 개로 올 수 있다) */
    private static List<JsonNode> items(JsonNode list) {
        JsonNode items = list.path("ITEM");
        List<JsonNode> out = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(out::add);
        } else if (items.isObject()) {
            out.add(items);
        }
        return out;
    }

    static List<BusOnLine> toLocations(JsonNode res) {
        JsonNode response = successOrThrow(res);
        List<BusOnLine> out = new ArrayList<>();
        for (JsonNode item : items(response.path("BUSLOCATION_LIST"))) {
            Integer stop = toNumber(item.path("CURR_STOP_ID"));
            if (stop == null || stop <= 0) {
                continue; // 0 = 차고지 등 노선 밖
            }
            Integer seq = toNumber(item.path("SEQ"));
            out.add(new BusOnLine(item.path("BUS_ID").asString(""), stop,
                    "1".equals(item.path("LOW_BUS").asString("").trim()), seq == null ? 0 : seq));
        }
        return out;
    }

    static List<BusArrival> toArrivals(JsonNode res) {
        JsonNode response = successOrThrow(res);
        List<BusArrival> out = new ArrayList<>();
        for (JsonNode item : items(response.path("ARRIVE_LIST"))) {
            out.add(toArrival(item));
        }
        return out;
    }

    private static BusArrival toArrival(JsonNode item) {
        return new BusArrival(
                toNumber(item.path("LINE_ID")),
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

    /** busLocationInfo 한 줄 (좌표 없음, 정류장 번호만) */
    record BusOnLine(String busId, int stopId, boolean lowFloor, int seq) {
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
