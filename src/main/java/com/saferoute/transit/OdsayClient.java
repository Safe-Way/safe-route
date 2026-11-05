package com.saferoute.transit;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * ODsay 대중교통 길찾기 (searchPubTransPathT) 를 부른다.
 * 키는 ODSAY_API_KEY: 배포 서버는 Server 키(학교 서버 IP 등록), 노트북 .env 는 Android 키.
 * 주소에 키와 좌표가 들어가므로 주소·오류 내용은 로그에 남기지 않는다 (NFR-07).
 * 하루 부를 수 있는 수가 적어서 서버가 직접 세고, 넘으면 부르지 않는다.
 */
@Component
public class OdsayClient {

    private static final Logger log = LoggerFactory.getLogger(OdsayClient.class);

    static final String URL = "https://api.odsay.com/v1/api/searchPubTransPathT";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient rest;
    private final String key;
    private final int dailyLimit;
    private final Clock clock;
    private LocalDate countDay;
    private int countToday;

    @Autowired
    public OdsayClient(@Value("${api-keys.odsay:}") String key,
                       @Value("${transit.odsay-daily-limit:30}") int dailyLimit, Clock clock) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key, dailyLimit, clock);
    }

    /** 테스트에서 가짜 ODsay 서버를 붙일 때 쓴다 */
    OdsayClient(RestClient.Builder builder, String key, int dailyLimit, Clock clock) {
        this.rest = builder.build();
        this.key = key;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /**
     * 출발→도착 대중교통 경로들 (ODsay 추천 순).
     *
     * @throws TransitException 가까움(TOO_CLOSE), 경로 없음(NO_ROUTE), 하루 한도(DAILY_LIMIT), 그 밖(TRANSIT_UNAVAILABLE)
     */
    public List<OdsayPath> search(double fromLat, double fromLon, double toLat, double toLon) {
        if (key == null || key.isBlank()) {
            log.warn("ODSAY_API_KEY 가 비어 있어요");
            throw TransitException.unavailable();
        }
        takeOne();
        JsonNode res;
        try {
            res = rest.get().uri(uri(fromLat, fromLon, toLat, toLon)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("ODsay 요청 실패: {}", e.getClass().getSimpleName());
            throw TransitException.unavailable();
        }
        return toPaths(res);
    }

    /** 오늘 부른 수를 하나 올린다. 한도를 넘으면 부르지 않고 DAILY_LIMIT */
    private synchronized void takeOne() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(countDay)) {
            countDay = today;
            countToday = 0;
        }
        if (countToday >= dailyLimit) {
            throw TransitException.dailyLimit();
        }
        countToday++;
    }

    URI uri(double fromLat, double fromLon, double toLat, double toLon) {
        // 키에 / + = 가 들어 있어 직접 인코딩한다. ODsay 는 X = 경도, Y = 위도
        return URI.create(URL + "?apiKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&SX=" + coord(fromLon) + "&SY=" + coord(fromLat)
                + "&EX=" + coord(toLon) + "&EY=" + coord(toLat)
                + "&OPT=0&SearchPathType=0");
    }

    private static String coord(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /** ODsay 답 → 경로 목록. 오류 답이면 TransitException */
    static List<OdsayPath> toPaths(JsonNode res) {
        if (res == null) {
            throw TransitException.unavailable();
        }
        JsonNode err = res.path("error");
        if (!err.isMissingNode() && !err.isNull()) {
            JsonNode e = err.isArray() ? err.path(0) : err;
            String code = e.path("code").asString("");
            switch (code) {
                case "-98" -> throw TransitException.tooClose();
                case "-99" -> throw TransitException.noRoute();
                default -> {
                    String msg = e.path("message").asString(e.path("msg").asString(""));
                    log.warn("ODsay 오류 코드 {} {}", code, msg);
                    throw TransitException.unavailable();
                }
            }
        }
        List<OdsayPath> paths = new ArrayList<>();
        for (JsonNode p : res.path("result").path("path")) {
            JsonNode info = p.path("info");
            List<OdsayPath.Seg> segs = new ArrayList<>();
            for (JsonNode s : p.path("subPath")) {
                segs.add(seg(s));
            }
            Integer fare = info.path("payment").isNumber() ? info.path("payment").asInt() : null;
            paths.add(new OdsayPath(num(info.path("totalTime")), fare, segs));
        }
        if (paths.isEmpty()) {
            throw TransitException.noRoute();
        }
        return paths;
    }

    private static OdsayPath.Seg seg(JsonNode s) {
        int type = num(s.path("trafficType"));
        OdsayPath.Place start = null;
        OdsayPath.Place end = null;
        List<String> lines = new ArrayList<>();
        String way = null;
        if (type == OdsayPath.SUBWAY || type == OdsayPath.BUS) {
            start = place(s, "start");
            end = place(s, "end");
            for (JsonNode lane : s.path("lane")) {
                String name = type == OdsayPath.BUS ? lane.path("busNo").asString("") : lane.path("name").asString("");
                if (!name.isBlank()) {
                    lines.add(name.trim());
                }
            }
            if (type == OdsayPath.SUBWAY) {
                way = s.path("way").asString(null);
            }
        }
        return new OdsayPath.Seg(type, num(s.path("distance")), num(s.path("sectionTime")), start, end,
                List.copyOf(lines), way, num(s.path("stationCount")));
    }

    private static OdsayPath.Place place(JsonNode s, String prefix) {
        return new OdsayPath.Place(s.path(prefix + "Name").asString(""),
                dbl(s.path(prefix + "Y")), dbl(s.path(prefix + "X")));
    }

    /** ODsay 는 숫자를 글자("126.9")로 줄 때가 있어 둘 다 읽는다 */
    private static double dbl(JsonNode n) {
        if (n.isNumber()) {
            return n.asDouble();
        }
        try {
            return Double.parseDouble(n.asString(""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int num(JsonNode n) {
        return (int) Math.round(dbl(n));
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(TIMEOUT);
        f.setReadTimeout(TIMEOUT);
        return f;
    }
}
