package com.saferoute.weather;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * 기상청 초단기실황(getUltraSrtNcst)을 부른다. 지금 이 격자의 기온·강수량·강수형태.
 * 키는 공공데이터포털 팀 키(DATA_GO_KR_KEY, Decoding 키)를 쓴다.
 */
@Component
public class KmaClient {

    static final String PATH = "/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst";
    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient rest;
    private final String key;

    @Autowired
    public KmaClient(@Value("${api-keys.data-go-kr:}") String key) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key);
    }

    /** 테스트에서 가짜 기상청 서버를 붙일 때 쓴다 */
    KmaClient(RestClient.Builder builder, String key) {
        this.rest = builder.baseUrl("https://apis.data.go.kr").build();
        this.key = key;
    }

    /**
     * @param grid 기상청 격자
     * @param now  지금 시각 (이 시각에 받을 수 있는 가장 최근 관측을 고른다)
     * @throws WeatherUnavailableException 키가 없거나 기상청이 실패했을 때
     */
    public WeatherNow now(KmaGrid grid, ZonedDateTime now) {
        if (key == null || key.isBlank()) {
            throw new WeatherUnavailableException("DATA_GO_KR_KEY 가 .env 에 없습니다", null);
        }
        ZonedDateTime base = baseTime(now);
        JsonNode res;
        try {
            res = rest.get()
                    .uri(b -> b.path(PATH)
                            .queryParam("serviceKey", key)
                            .queryParam("dataType", "JSON")
                            .queryParam("numOfRows", 10)
                            .queryParam("pageNo", 1)
                            .queryParam("base_date", base.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                            .queryParam("base_time", base.format(DateTimeFormatter.ofPattern("HHmm")))
                            .queryParam("nx", grid.nx())
                            .queryParam("ny", grid.ny())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new WeatherUnavailableException("기상청 요청 실패: " + e.getMessage(), e);
        }
        return toWeather(res);
    }

    /**
     * 초단기실황은 매시 정각 관측이 40분쯤 지나야 나온다.
     * 그래서 지금에서 40분을 빼고 정각으로 내린 시각을 쓴다. 예: 10:39 → 09:00, 10:41 → 10:00
     */
    static ZonedDateTime baseTime(ZonedDateTime now) {
        return now.withZoneSameInstant(KOREA).minusMinutes(40).truncatedTo(ChronoUnit.HOURS);
    }

    static WeatherNow toWeather(JsonNode res) {
        JsonNode response = res == null ? null : res.path("response");
        String code = response == null ? "" : response.path("header").path("resultCode").asString("");
        if (!"00".equals(code)) {
            String msg = response == null ? "" : response.path("header").path("resultMsg").asString("");
            throw new WeatherUnavailableException("기상청 오류 코드 " + code + " " + msg, null);
        }
        Double temp = null;
        Double rain = null;
        Integer pty = null;
        String observedAt = null;
        for (JsonNode item : response.path("body").path("items").path("item")) {
            String category = item.path("category").asString("");
            Double value = toNumber(item.path("obsrValue"));
            observedAt = item.path("baseDate").asString("") + item.path("baseTime").asString("");
            switch (category) {
                case "T1H" -> temp = value;
                case "RN1" -> rain = value;
                case "PTY" -> pty = value == null ? null : value.intValue();
                default -> { }
            }
        }
        return new WeatherNow(temp, rain, pty, observedAt);
    }

    /** 기상청은 값을 글자나 숫자로 준다. 숫자로 못 읽거나 결측(-998.9 같은 큰 음수)이면 null */
    static Double toNumber(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            double d = Double.parseDouble(value.asString("").trim());
            return d <= -900 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
