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
import org.springframework.dao.DataAccessException;
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
 * 세는 곳은 DB 표(odsay_call_count)라 서버 두 대가 합쳐 세고 재시작해도 이어진다 (2026-11-16).
 * 표가 없거나 DB를 못 쓰면 그 서버 메모리로 센다 (예전 방식).
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
    /** DB로 세는 곳. null이면 메모리로만 센다 (테스트) */
    private final OdsayCallCounter counter;
    private LocalDate countDay;
    private int countToday;

    @Autowired
    public OdsayClient(@Value("${api-keys.odsay:}") String key,
                       @Value("${transit.odsay-daily-limit:30}") int dailyLimit, Clock clock,
                       OdsayCallCounter counter) {
        this(RestClient.builder().requestFactory(timeoutFactory()), key, dailyLimit, clock, counter);
    }

    /** 테스트에서 가짜 ODsay 서버를 붙일 때 쓴다 (메모리로만 셈) */
    OdsayClient(RestClient.Builder builder, String key, int dailyLimit, Clock clock) {
        this(builder, key, dailyLimit, clock, null);
    }

    OdsayClient(RestClient.Builder builder, String key, int dailyLimit, Clock clock, OdsayCallCounter counter) {
        this.rest = builder.build();
        this.key = key;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
        this.counter = counter;
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

    /**
     * 오늘 부른 수를 하나 올린다. 한도를 넘으면 부르지 않고 DAILY_LIMIT.
     * DB로 먼저 세고, DB를 못 쓰면 메모리로 센다 (DB가 잠깐 끊겨도 대중교통 길찾기는 된다)
     */
    private void takeOne() {
        LocalDate today = LocalDate.now(clock);
        // DB 한 문장이 동시에 와도 한도를 지키므로 잠그지 않는다 (DB가 느릴 때 다른 요청까지 기다리지 않게)
        if (counter != null) {
            try {
                if (!counter.take(today, dailyLimit)) {
                    throw TransitException.dailyLimit();
                }
                return;
            } catch (DataAccessException e) {
                log.warn("ODsay 부른 수를 DB에서 못 셈, 이 서버 메모리로 셈: {}", e.getMostSpecificCause().getMessage());
            }
        }
        takeOneInMemory(today);
    }

    /** 이 서버 메모리로 센다 (DB를 못 쓸 때, 테스트) */
    private synchronized void takeOneInMemory(LocalDate today) {
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
        List<OdsayPath.Place> stops = new ArrayList<>();
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
            stops = stops(s);
        }
        return new OdsayPath.Seg(type, num(s.path("distance")), num(s.path("sectionTime")), start, end,
                List.copyOf(lines), way, num(s.path("stationCount")), stops);
    }

    /** 지나는 정류장·역 (passStopList.stations[]: stationName, x = 경도, y = 위도). 좌표 없는 것은 뺌 */
    static List<OdsayPath.Place> stops(JsonNode s) {
        List<OdsayPath.Place> out = new ArrayList<>();
        for (JsonNode st : s.path("passStopList").path("stations")) {
            double lat = dbl(st.path("y"));
            double lon = dbl(st.path("x"));
            if (lat == 0 || lon == 0) {
                continue;
            }
            out.add(new OdsayPath.Place(st.path("stationName").asString("").trim(), lat, lon));
        }
        return out;
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
