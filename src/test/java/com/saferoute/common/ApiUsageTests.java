package com.saferoute.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.saferoute.testsupport.FakeDb;

/**
 * 외부 API 호출 수 세기와 /api/usage
 * <p>무엇을: {@link ApiUsage}가 RestClient로 나가는 요청을 하루 단위로 세고, {@link UsageController}가 한도 대비 비율을 보여 주는지.
 * <p>어떻게: DB는 FakeDb(실행한 SQL과 값만 남김), 외부 서버는 MockRestServiceServer, 시각은 고정 Clock이다. 진짜 망·DB는 안 쓴다.
 * <p>왜: 세기가 틀리면 health.yml의 사용량 알림(usage-high 이슈)이 안 뜨거나 엉뚱하게 떠서 무료 한도를 넘긴 줄 모른다.
 * 세기가 API 호출을 막으면 길찾기·비서가 통째로 멈춘다.
 */
@DisplayName("외부 API 호출 수 세기와 /api/usage")
class ApiUsageTests {

    // 한국 11-25 00:30 (UTC로는 11-24): 한국 날짜로 센다
    // 자정을 막 넘긴 시각을 골라, 서버가 UTC 날짜로 세면 하루 어긋나는 실수를 잡는다
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    // 외부 서비스는 실패한 요청(5xx)도 하루 한도에서 깎는다. 성공만 세면 실제보다 적게 보여 알림이 늦는다.
    // 날짜 값이 UTC(11-24)가 아니라 한국(11-25)인지도 같이 본다
    @Test
    @DisplayName("실패한 요청까지 모두 한국 날짜로 센다")
    void everyRequestIsCountedEvenFailedOnesByKoreanDay() {
        FakeDb db = new FakeDb();
        ApiUsage usage = new ApiUsage(db.jdbc(), CLOCK);
        // 세는 인터셉터를 끼운 빌더에 가짜 서버를 묶는다: 요청은 인터셉터를 거친 뒤 가짜 서버로 간다
        RestClient.Builder b = RestClient.builder().requestInterceptor(usage.counter("kakao"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://dapi.kakao.com/a")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dapi.kakao.com/b")).andRespond(withServerError());
        RestClient rest = b.build();

        rest.get().uri("https://dapi.kakao.com/a").retrieve().toBodilessEntity();
        try {
            rest.get().uri("https://dapi.kakao.com/b").retrieve().toBodilessEntity();
        } catch (RestClientException expected) {
            // 실패한 요청도 한도에 들어간다
        }

        assertThat(db.calls("INSERT INTO api_call_count")).hasSize(2);
        assertThat(db.calls("INSERT INTO api_call_count")).allSatisfy(c -> assertThat(c.params()).containsExactly(LocalDate.of(2026, 11, 25), "kakao"));
        server.verify();
    }

    // api_call_count 표(V40)가 아직 없는 DB에 배포돼도 외부 API는 그대로 불려야 한다.
    // 세기 오류가 인터셉터 밖으로 새면 제미나이·카카오 호출이 모두 실패한다
    @Test
    @DisplayName("세기가 실패해도 API 호출은 막지 않는다")
    void countingFailureDoesNotBlockTheCall() {
        FakeDb db = new FakeDb().fails("INSERT INTO api_call_count", "Table 'api_call_count' doesn't exist");
        ApiUsage usage = new ApiUsage(db.jdbc(), CLOCK);
        RestClient.Builder b = RestClient.builder().requestInterceptor(usage.counter("gemini"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://g/x")).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        // 응답 본문이 그대로 오면 호출이 막히지 않은 것이다
        assertThat(b.build().get().uri("https://g/x").retrieve().body(String.class)).isEqualTo("ok");
        server.verify();
    }

    // 한도: gemini 1000, kakao 100000, odsay 30, 알림 기준 0.8 (생성자 인자 순서대로).
    // gemini 850/1000 = 0.85 는 기준을 넘고, odsay 24/30 = 0.8 은 기준과 딱 같아 경곗값(>=)을 본다.
    // kakao 10/100000 은 0.0001 이라 소수 셋째 자리 반올림으로 0.0 이 된다
    @Test
    @DisplayName("/api/usage가 한도 대비 비율과 높은 것을 보여 준다")
    void usageShowsRatioAndHighOnes() {
        // odsay 는 api_call_count 가 아니라 따로 세는 odsay_call_count 표에서 읽는다
        FakeDb db = new FakeDb()
                .rows("FROM api_call_count", FakeDb.row("api", "gemini", "calls", 850), FakeDb.row("api", "kakao", "calls", 10))
                .rows("FROM odsay_call_count", FakeDb.row("calls", 24));
        UsageController c = new UsageController(new ApiUsage(db.jdbc(), CLOCK), 1000, 100_000, 30, 0.8);

        UsageController.Usage u = c.today();

        assertThat(u.day()).isEqualTo("2026-11-25");
        // 순서는 gemini, kakao, odsay 로 늘 같다 (health.yml 이 읽기 쉽게)
        assertThat(u.apis()).extracting(UsageController.Api::api, UsageController.Api::calls, UsageController.Api::ratio)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("gemini", 850, 0.85),
                        org.assertj.core.groups.Tuple.tuple("kakao", 10, 0.0),
                        org.assertj.core.groups.Tuple.tuple("odsay", 24, 0.8));
        assertThat(u.high()).containsExactly("gemini 850/1000", "odsay 24/30");
        // 읽을 때도 한국 날짜로 찾는다
        assertThat(db.call("FROM api_call_count").params()).containsExactly(LocalDate.of(2026, 11, 25));
    }

    // 표가 없거나 DB가 안 될 때 /api/usage 가 500 을 내면 health.yml 이 서버가 죽은 줄 안다.
    // 그래서 0으로 보여 주고 알림 목록은 비운다
    @Test
    @DisplayName("표가 없으면 0으로 본다")
    void noTableMeansZero() {
        FakeDb db = new FakeDb().fails("FROM api_call_count", "no table").fails("FROM odsay_call_count", "no table");
        UsageController c = new UsageController(new ApiUsage(db.jdbc(), CLOCK), 1000, 100_000, 30, 0.8);
        // 못 읽어도 세 API 줄은 모두 나온다 (빈 목록이 아님)
        assertThat(c.today().apis()).allSatisfy(a -> assertThat(a.calls()).isZero()).hasSize(3);
        assertThat(c.today().high()).isEmpty();
    }
}
