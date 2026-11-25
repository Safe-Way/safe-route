package com.saferoute.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    @DisplayName("사진 1장 붙인 턱 신고를 보내면 201과 새 번호를 돌려주고, 종류·모드·위치가 저장소까지 그대로 간다")
    void photoReportSaved() throws Exception {
        long newId = 12L;
        when(repo.insert(any(), any(), any(), any())).thenReturn(newId);
        MockMultipartFile photo = new MockMultipartFile("photos", "a.jpg", "image/jpeg", ReportServiceTests.JPG);

        mvc.perform(multipart("/api/reports").file(photo)
                        .param("type", "curb")
                        .param("lat", "35.14")
                        .param("lon", "126.93")
                        .param("mode", "wheelchair"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newId))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.photoCount").value(1));

        ArgumentCaptor<NewReport> saved = ArgumentCaptor.forClass(NewReport.class);
        verify(repo).insert(saved.capture(), any(), any(), any());
        assertThat(saved.getValue().type()).isEqualTo("curb");
        assertThat(saved.getValue().mode()).isEqualTo("wheelchair");
        assertThat(saved.getValue().lat()).isEqualTo(35.14);
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
    void activeGivesSpotsFromView() throws Exception {
        // 재휘님 032 road_report_active 뷰 한 줄을 그대로 (기간·같은 곳 묶기는 뷰가 계산)
        when(repo.active(126.92, 35.13, 126.94, 35.15, ReportService.LIST_LIMIT + 1)).thenReturn(List.of(
                new ActiveSpot(7, "construction", 35.14, 126.93, "2026-11-10", "2026-11-16", 3, 2, 1, true,
                        List.of("adult", "child", "senior", "wheelchair"), List.of(), "2026-11-10T11:15", "공사 중이에요")));

        mvc.perform(get("/api/reports/active").param("bbox", "126.92,35.13,126.94,35.15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spots[0].spotId").value(7))
                .andExpect(jsonPath("$.spots[0].reportType").value("construction"))
                .andExpect(jsonPath("$.spots[0].endsOn").value("2026-11-16"))
                .andExpect(jsonPath("$.spots[0].reportCount").value(3))
                .andExpect(jsonPath("$.spots[0].reporterCount").value(2))
                .andExpect(jsonPath("$.spots[0].confirmed").value(true))
                .andExpect(jsonPath("$.spots[0].avoidModes[3]").value("wheelchair"))
                .andExpect(jsonPath("$.spots[0].costModes").isEmpty())
                .andExpect(jsonPath("$.spots[0].lastMemo").value("공사 중이에요"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void activeNeedsBbox() throws Exception {
        mvc.perform(get("/api/reports/active")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/active").param("bbox", "126.94,35.13,126.92,35.15"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/active").param("bbox", "126.0,35.0,127.0,36.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BBOX_TOO_LARGE"));
    }

    @Test
    void activeDbDownGives503() throws Exception {
        when(repo.active(anyDouble(), anyDouble(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new DataAccessResourceFailureException("뷰 없음"));
        mvc.perform(get("/api/reports/active").param("bbox", "126.92,35.13,126.94,35.15"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }

    @Test
    void modeListIsSplit() {
        org.assertj.core.api.Assertions.assertThat(ReportRepository.modes("senior,wheelchair"))
                .containsExactly("senior", "wheelchair");
        org.assertj.core.api.Assertions.assertThat(ReportRepository.modes("")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(ReportRepository.modes(null)).isEmpty();
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
