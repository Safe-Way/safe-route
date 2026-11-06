package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.Facility;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.report.ReportService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.json.JsonMapper;

/** 가짜 제미나이 서버로 함수 호출 흐름 (인터넷 없이) */
class GeminiBrainTests {

    static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
    static final String A = "00000000-0000-4000-8000-00000000de01";

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final FacilityRepository facilities = mock(FacilityRepository.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    AssistantService service;

    @BeforeEach
    void setUp() {
        AssistantTools tools = new AssistantTools(mock(KakaoLocalClient.class), mock(TripRecommender.class),
                facilities, memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge());
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain brain = new GeminiBrain(new GeminiClient(builder, "test-key", "gemini-test"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), brain, tools, json,
                Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(memory.profile(A)).thenReturn(new Profile("wheelchair", "manual_wheelchair", false, null, null,
                "wheelchair", 0, 1, true, true));
        when(memory.layers()).thenReturn(List.of(
                new MemoryRepository.Layer("cctv", "CCTV", List.of("씨씨티비"))));
        when(facilities.near(35.148, 126.926, "toilet", true, 1000)).thenReturn(List.of(new Facility(1, "toilet",
                null, "문화전당 장애인화장실", null, 35.1478, 126.9262, 50, "이용 가능", null, true, 1, List.of(), null)));
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, 35.148, 126.926, null, null, null, null, null));
    }

    @Test
    void geminiPicksFindNearbyThenSpeaks() {
        server.expect(requestTo(URL)).andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("35.148"))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}},"thoughtSignature":"abc"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("thoughtSignature")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("functionResponse")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"text":"가장 가까운 장애인 화장실은 문화전당 장애인화장실로 50m예요. 지금 열려 있어요."}]}}]}
                        """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isTrue();
        assertThat(r.say()).contains("50m");
        assertThat(r.tools()).containsExactly("find_nearby");
        assertThat(r.place().name()).isEqualTo("문화전당 장애인화장실");
        server.verify();
    }

    @Test
    void madeUpNumberFallsBackToRuleSentence() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"화장실은 20m 앞이에요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isFalse();
        assertThat(r.say()).contains("50m").doesNotContain("20m");
    }

    @Test
    void savingToolOnlyAsksAndDoesNotSave() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"set_map_layers","args":{"remove":["씨씨티비"]}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("방범카메라 표시 좀 치워");
        assertThat(r.ask()).contains("CCTV", "맞으세요");
        assertThat(r.tools()).containsExactly("set_map_layers");
        verify(memory, never()).saveLayer(anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void geminiDownMeansRules() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        AssistantReply r = ask("화장실 어디야");
        assertThat(r.ai()).isFalse();
        assertThat(r.say()).contains("장애인 화장실", "50m");
    }

    @Test
    void busyModelAsksBackupModel() {
        String backup = URL.replace("gemini-test", GeminiClient.BACKUP_MODELS.get(0));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\"}}"));
        server.expect(requestTo(backup)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(backup)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"가장 가까운 장애인 화장실은 50m 앞이에요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isTrue();
        assertThat(r.say()).contains("50m");
        server.verify();
    }

    @Test
    void readsAnswerEvenWhenTypeIsOctetStream() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}]}
                """, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"가장 가까운 장애인 화장실은 50m 앞이에요."}]}}]}
                """, MediaType.APPLICATION_OCTET_STREAM));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isTrue();
    }
}
