package com.saferoute.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 연습용 주소에 일부러 잘못된 요청을 보내 오류 모양을 확인 (DB·인터넷 없이 돈다) */
class ApiErrorHandlerTests {

    @RestController
    static class PracticeController {

        @GetMapping("/practice")
        String practice(@RequestParam double lat) {
            return "ok";
        }

        @PostMapping("/practice-body")
        String practiceBody(@RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/practice-crash")
        String crash() {
            throw new IllegalStateException("일부러 낸 오류");
        }

        @GetMapping("/practice-missing")
        String missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/api/nothing", "api/nothing");
        }

        record Body(double lat) {
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PracticeController())
            .setControllerAdvice(new ApiErrorHandler())
            .build();

    @Test
    void missingParamGivesBadParam() throws Exception {
        mvc.perform(get("/practice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"))
                .andExpect(jsonPath("$.message").value("lat 값이 필요해요"));
    }

    @Test
    void wrongTypeGivesBadParam() throws Exception {
        mvc.perform(get("/practice").param("lat", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void brokenJsonGivesBadParam() throws Exception {
        mvc.perform(post("/practice-body").contentType(MediaType.APPLICATION_JSON).content("{ 깨진"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void wrongMethodGives405() throws Exception {
        mvc.perform(post("/practice"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownAddressGives404() throws Exception {
        mvc.perform(get("/practice-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedErrorHidesDetails() throws Exception {
        mvc.perform(get("/practice-crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("잠시 후 다시 시도해 주세요"));
    }
}
