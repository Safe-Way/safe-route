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

/** 외부 API 호출 수 세기와 /api/usage */
@DisplayName("외부 API 호출 수 세기와 /api/usage")
class ApiUsageTests {

    // 한국 11-25 00:30 (UTC로는 11-24): 한국 날짜로 센다
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    @DisplayName("실패한 요청까지 모두 한국 날짜로 센다")
    void everyRequestIsCountedEvenFailedOnesByKoreanDay() {
        FakeDb db = new FakeDb();
        ApiUsage usage = new ApiUsage(db.jdbc(), CLOCK);
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

    @Test
    @DisplayName("세기가 실패해도 API 호출은 막지 않는다")
    void countingFailureDoesNotBlockTheCall() {
        FakeDb db = new FakeDb().fails("INSERT INTO api_call_count", "Table 'api_call_count' doesn't exist");
        ApiUsage usage = new ApiUsage(db.jdbc(), CLOCK);
        RestClient.Builder b = RestClient.builder().requestInterceptor(usage.counter("gemini"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://g/x")).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        assertThat(b.build().get().uri("https://g/x").retrieve().body(String.class)).isEqualTo("ok");
        server.verify();
    }

    @Test
    @DisplayName("/api/usage가 한도 대비 비율과 높은 것을 보여 준다")
    void usageShowsRatioAndHighOnes() {
        FakeDb db = new FakeDb()
                .rows("FROM api_call_count", FakeDb.row("api", "gemini", "calls", 850), FakeDb.row("api", "kakao", "calls", 10))
                .rows("FROM odsay_call_count", FakeDb.row("calls", 24));
        UsageController c = new UsageController(new ApiUsage(db.jdbc(), CLOCK), 1000, 100_000, 30, 0.8);

        UsageController.Usage u = c.today();

        assertThat(u.day()).isEqualTo("2026-11-25");
        assertThat(u.apis()).extracting(UsageController.Api::api, UsageController.Api::calls, UsageController.Api::ratio)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("gemini", 850, 0.85),
                        org.assertj.core.groups.Tuple.tuple("kakao", 10, 0.0),
                        org.assertj.core.groups.Tuple.tuple("odsay", 24, 0.8));
        assertThat(u.high()).containsExactly("gemini 850/1000", "odsay 24/30");
        assertThat(db.call("FROM api_call_count").params()).containsExactly(LocalDate.of(2026, 11, 25));
    }

    @Test
    @DisplayName("표가 없으면 0으로 본다")
    void noTableMeansZero() {
        FakeDb db = new FakeDb().fails("FROM api_call_count", "no table").fails("FROM odsay_call_count", "no table");
        UsageController c = new UsageController(new ApiUsage(db.jdbc(), CLOCK), 1000, 100_000, 30, 0.8);
        assertThat(c.today().apis()).allSatisfy(a -> assertThat(a.calls()).isZero()).hasSize(3);
        assertThat(c.today().high()).isEmpty();
    }
}
