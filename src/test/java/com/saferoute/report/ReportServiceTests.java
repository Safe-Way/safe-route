package com.saferoute.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ReportServiceTests {

    static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};

    private final ReportRepository repo = mock(ReportRepository.class);
    private final ReportService service = new ReportService(repo);

    private static MultipartFile file(byte[] data) {
        return new MockMultipartFile("photos", "a.jpg", "image/jpeg", data);
    }

    @Test
    void goodReportIsChecked() {
        NewReport r = service.check("curb", 35.14, 126.93, "wheelchair", "  턱이 높아요 ", 8, List.of(file(JPG)));
        assertThat(r.type()).isEqualTo("curb");
        assertThat(r.mode()).isEqualTo("wheelchair");
        assertThat(r.memo()).isEqualTo("턱이 높아요");
        assertThat(r.accuracyM()).isEqualTo(8);
        assertThat(r.photos()).hasSize(1);
        assertThat(r.photos().get(0).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void emptyValuesBecomeNull() {
        NewReport r = service.check("other", 35.14, 126.93, " ", "", null, null);
        assertThat(r.mode()).isNull();
        assertThat(r.memo()).isNull();
        assertThat(r.photos()).isEmpty();
    }

    @Test
    void unknownTypeIsBad() {
        assertThatThrownBy(() -> service.check("hole", 35.14, 126.93, null, null, null, null))
                .isInstanceOf(ReportException.class).hasMessageContaining("type");
    }

    @Test
    void missingCoordinateIsBad() {
        assertThatThrownBy(() -> service.check("curb", null, 126.93, null, null, null, null))
                .isInstanceOf(ReportException.class).hasMessageContaining("lat");
    }

    @Test
    void notANumberCoordinateIsBad() {
        assertThatThrownBy(() -> service.check("curb", Double.NaN, 126.93, null, null, null, null))
                .isInstanceOf(ReportException.class).hasMessageContaining("lat");
        assertThatThrownBy(() -> service.check("curb", 35.14, Double.POSITIVE_INFINITY, null, null, null, null))
                .isInstanceOf(ReportException.class).hasMessageContaining("lat");
    }

    @Test
    void notANumberMapIsBadAndNeverReachesDb() {
        assertThatThrownBy(() -> service.within(Double.NaN, 35.0, 127.0, 35.2))
                .isInstanceOf(ReportException.class).hasMessageContaining("bbox");
        assertThatThrownBy(() -> service.active(126.9, 35.1, 126.95, Double.NaN))
                .isInstanceOf(ReportException.class).hasMessageContaining("bbox");
        verify(repo, never()).within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
        verify(repo, never()).active(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void unknownModeIsBad() {
        assertThatThrownBy(() -> service.check("curb", 35.14, 126.93, "women", null, null, null))
                .isInstanceOf(ReportException.class).hasMessageContaining("mode");
    }

    @Test
    void longMemoIsBad() {
        assertThatThrownBy(() -> service.check("curb", 35.14, 126.93, null, "가".repeat(301), null, null))
                .isInstanceOf(ReportException.class).hasMessageContaining("300");
    }

    @Test
    void fourPhotosAreTooMany() {
        List<MultipartFile> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(file(JPG));
        }
        assertThatThrownBy(() -> service.check("curb", 35.14, 126.93, null, null, null, four))
                .isInstanceOf(ReportException.class).hasMessageContaining("3장");
    }

    @Test
    void notAnImageIsBadEvenIfNamedJpg() {
        byte[] html = "<html><script>".getBytes();
        assertThatThrownBy(() -> service.check("curb", 35.14, 126.93, null, null, null, List.of(file(html))))
                .isInstanceOf(ReportException.class).hasMessageContaining("JPG");
    }

    @Test
    void imageTypeComesFromFileBytes() {
        assertThat(ReportService.imageType(JPG)).isEqualTo("image/jpeg");
        assertThat(ReportService.imageType(PNG)).isEqualTo("image/png");
        assertThat(ReportService.imageType("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(ReportService.imageType("\0\0\0\u0018ftypheic".getBytes())).isEqualTo("image/heic");
        assertThat(ReportService.imageType("GIF89a".getBytes())).isNull();
    }

    @Test
    void createSavesReportAndPhotosWithNearestRoad() {
        NewReport r = service.check("steep", 35.14, 126.93, "senior", null, null, List.of(file(JPG), file(PNG)));
        when(repo.nearestEdge(35.14, 126.93, ReportService.EDGE_SNAP_M)).thenReturn(777L);
        when(repo.insert(r, 777L, "photo", null)).thenReturn(12L);

        ReportCreated c = service.create(r);

        assertThat(c).isEqualTo(new ReportCreated(12, "open", 2));
        verify(repo).insertPhoto(eq(12L), eq(1), any());
        verify(repo).insertPhoto(eq(12L), eq(2), any());
    }

    @Test
    void reportIsSavedEvenIfRoadLookupFails() {
        NewReport r = service.check("steep", 35.14, 126.93, null, null, null, null);
        when(repo.nearestEdge(anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("road_edge 없음"));
        when(repo.insert(r, null, "photo", null)).thenReturn(5L);

        assertThat(service.create(r).id()).isEqualTo(5);
        verify(repo, never()).insertPhoto(anyLongArg(), anyInt(), any());
    }

    @Test
    void guestIdIsSavedOnlyAsHash() {
        ReporterHash hash = new ReporterHash("test-secret");
        ReportService withHash = new ReportService(repo, hash);
        NewReport r = withHash.check("curb", 35.14, 126.93, null, null, null, null);
        String guest = "00000000-0000-4000-8000-000000000123";
        when(repo.insert(eq(r), any(), eq("photo"), any())).thenReturn(8L);

        withHash.create(r, guest);

        verify(repo).insert(eq(r), any(), eq("photo"), eq(hash.of(guest)));
        assertThat(hash.of(guest)).hasSize(64).doesNotContain(guest);
    }

    @Test
    void voiceReportAlsoSavesHash() {
        ReporterHash hash = new ReporterHash("test-secret");
        ReportService withHash = new ReportService(repo, hash);
        when(repo.insert(any(), any(), eq("voice"), any())).thenReturn(9L);

        assertThat(withHash.createVoice("construction", "adult", null, 35.14, 126.93, "g-1")).isEqualTo(9);
        verify(repo).insert(any(), any(), eq("voice"), eq(hash.of("g-1")));
    }

    private static long anyLongArg() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void listIsCutAt300() {
        List<ReportItem> many = new ArrayList<>();
        for (int i = 0; i < ReportService.LIST_LIMIT + 1; i++) {
            many.add(new ReportItem(i, "curb", "턱", null, null, 35.14, 126.93, "open", "2026-11-05T14:00", List.of()));
        }
        when(repo.within(126.9, 35.1, 127.0, 35.2, ReportService.LIST_LIMIT + 1)).thenReturn(many);

        ReportsResponse res = service.within(126.9, 35.1, 127.0, 35.2);

        assertThat(res.reports()).hasSize(ReportService.LIST_LIMIT);
        assertThat(res.truncated()).isTrue();
    }

    @Test
    void tooLargeMapIsBad() {
        assertThatThrownBy(() -> service.within(126.0, 35.0, 127.0, 35.2))
                .isInstanceOf(ReportException.class)
                .satisfies(e -> assertThat(((ReportException) e).code()).isEqualTo("BBOX_TOO_LARGE"));
    }
}
