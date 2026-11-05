package com.saferoute.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;

class ReportControllerTests {

    private final ReportRepository repo = mock(ReportRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReportController(new ReportService(repo)))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void postWithPhotoGives201() throws Exception {
        when(repo.insert(any(), any())).thenReturn(12L);
        MockMultipartFile photo = new MockMultipartFile("photos", "a.jpg", "image/jpeg", ReportServiceTests.JPG);

        mvc.perform(multipart("/api/reports").file(photo)
                        .param("type", "curb").param("lat", "35.14").param("lon", "126.93").param("mode", "wheelchair"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.photoCount").value(1));
    }

    @Test
    void badTypeGives400() throws Exception {
        mvc.perform(multipart("/api/reports").param("type", "hole").param("lat", "35.14").param("lon", "126.93"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void jsonInsteadOfMultipartGives415() throws Exception {
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void listGivesReports() throws Exception {
        when(repo.within(126.92, 35.13, 126.94, 35.15, ReportService.LIST_LIMIT + 1)).thenReturn(List.of(
                new ReportItem(12, "curb", "턱", "wheelchair", "턱이 높아요", 35.14, 126.93, "open", "2026-11-05T14:30",
                        List.of("/api/reports/12/photos/1"))));

        mvc.perform(get("/api/reports").param("bbox", "126.92,35.13,126.94,35.15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].typeName").value("턱"))
                .andExpect(jsonPath("$.reports[0].photoUrls[0]").value("/api/reports/12/photos/1"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void badBboxGives400() throws Exception {
        mvc.perform(get("/api/reports").param("bbox", "1,2,3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void photoIsServedAsImage() throws Exception {
        when(repo.photo(12, 1)).thenReturn(new NewReport.Photo("image/jpeg", ReportServiceTests.JPG));

        mvc.perform(get("/api/reports/12/photos/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(ReportServiceTests.JPG));
    }

    @Test
    void hiddenOrMissingPhotoGives404() throws Exception {
        mvc.perform(get("/api/reports/99/photos/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void dbDownGives503() throws Exception {
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new DataAccessResourceFailureException("끊김"));

        mvc.perform(get("/api/reports").param("bbox", "126.92,35.13,126.94,35.15"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
