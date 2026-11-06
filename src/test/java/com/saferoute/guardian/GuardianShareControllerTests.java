package com.saferoute.guardian;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;

class GuardianShareControllerTests {

    static final String TOKEN = GuardianShareServiceTests.TOKEN;

    final GuardianShareRepository repo = mock(GuardianShareRepository.class);
    final MockMvc mvc;

    GuardianShareControllerTests() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new GuardianShareController(new GuardianShareService(repo,
                GuardianShareServiceTests.CLOCK, "https://safe-route.java21.net")))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void startGives201WithLink() throws Exception {
        mvc.perform(post("/api/guardian/shares").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walkerName\":\"엄마\",\"destName\":\"집\",\"mode\":\"senior\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://safe-route.java21.net/s/")));
    }

    @Test
    void viewGivesStatusWithoutGuestId() throws Exception {
        when(repo.find(TOKEN)).thenReturn(Optional.of(GuardianShareServiceTests.walking(
                GuardianShareServiceTests.NOW.minusSeconds(10))));
        mvc.perform(get("/api/guardian/shares/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("walking"))
                .andExpect(jsonPath("$.walkerName").value("엄마"))
                .andExpect(jsonPath("$.guestId").doesNotExist());
    }

    @Test
    void missingLocationGives400AndUnknownTokenGives404() throws Exception {
        when(repo.find(TOKEN)).thenReturn(Optional.of(GuardianShareServiceTests.walking(
                GuardianShareServiceTests.NOW.minusSeconds(10))));
        mvc.perform(put("/api/guardian/shares/" + TOKEN + "/location").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        when(repo.find(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/api/guardian/shares/zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHARE_NOT_FOUND"));
    }

    @Test
    void endGives204() throws Exception {
        when(repo.find(TOKEN)).thenReturn(Optional.of(GuardianShareServiceTests.walking(
                GuardianShareServiceTests.NOW.minusSeconds(10))));
        mvc.perform(post("/api/guardian/shares/" + TOKEN + "/end").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"arrived\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void pageIsHtmlAndNotIndexed() throws Exception {
        mvc.perform(get("/s/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/guardian/shares/")));
    }

    @Test
    void dbDownGives503() throws Exception {
        when(repo.find(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(get("/api/guardian/shares/" + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
