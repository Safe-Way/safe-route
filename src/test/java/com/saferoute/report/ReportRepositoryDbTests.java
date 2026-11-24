package com.saferoute.report;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 신고 저장·지도 목록·지금 유효한 신고·사진 SQL (가짜 DB로 끝까지) */
@DisplayName("신고 저장·지도 목록·유효 신고·사진 SQL")
class ReportRepositoryDbTests {

    private final FakeDb db = new FakeDb().generatedKey(7L);
    private final ReportRepository repo = new ReportRepository(db.jdbc());

    private static NewReport report(Integer accuracy) {
        return new NewReport("curb", "wheelchair", "턱이 높아요", 35.147, 126.92, accuracy, List.of());
    }

    @Test
    @DisplayName("신고를 신고한 사람 해시와 함께 저장한다")
    void insertWithReporterHash() {
        long id = repo.insert(report(12), 99L, "voice", "abc");

        assertThat(id).isEqualTo(7L);
        FakeDb.Call c = db.call("INSERT INTO road_report (");
        assertThat(c.sql()).contains(", reporter_hash) VALUES");
        assertThat(c.params()).containsExactly("curb", "wheelchair", "턱이 높아요", 126.92, 35.147, 126.92, 35.147, 12,
                99L, "voice", "abc");
    }

    @Test
    @DisplayName("정확도·가까운 길이 없으면 빈 값, 경로는 기본값으로 저장한다")
    void insertWithoutAccuracyEdgeAndDefaultVia() {
        repo.insert(report(null), null);

        assertThat(db.call("INSERT INTO road_report (").params())
                .containsExactly("curb", "wheelchair", "턱이 높아요", 126.92, 35.147, 126.92, 35.147, null, null, "photo",
                        null);
    }

    @Test
    @DisplayName("033 이전 DB면 옛 칸으로 저장한다")
    void insertFallsBackBeforeMigration033() {
        db.fails(", reporter_hash) VALUES", "Unknown column 'reporter_hash'");

        assertThat(repo.insert(report(5), 1L, "photo", "abc")).isEqualTo(7L);
        assertThat(repo.insert(report(5), 1L, "photo", "abc")).isEqualTo(7L);

        // 처음 한 번만 새 칸으로 해 보고, 그 뒤로는 예전 SQL만
        assertThat(db.calls(", reporter_hash) VALUES")).hasSize(1);
        List<FakeDb.Call> old = db.calls("edge_id, via) VALUES");
        assertThat(old).hasSize(2);
        assertThat(old.get(0).params()).hasSize(10);
    }

    @Test
    @DisplayName("신고 사진을 넣고 읽는다")
    void photoInsertAndRead() {
        byte[] jpg = {1, 2, 3};
        repo.insertPhoto(7L, 1, new NewReport.Photo("image/jpeg", jpg));
        assertThat(db.call("INSERT INTO road_report_photo").params()).containsExactly(7L, 1, "image/jpeg", 3, jpg);

        assertThat(repo.photo(7L, 2)).isNull();
        db.rows("SELECT p.content_type, p.data", row("content_type", "image/jpeg", "data", jpg));
        NewReport.Photo p = repo.photo(7L, 1);
        assertThat(p.contentType()).isEqualTo("image/jpeg");
        assertThat(p.data()).isEqualTo(jpg);
    }

    @Test
    @DisplayName("신고 위치에서 가장 가까운 길 구간을 찾는다")
    void nearestEdge() {
        assertThat(repo.nearestEdge(35.147, 126.92, 30)).isNull();

        db.rows("SELECT id FROM road_edge", row("id", 119462L));
        assertThat(repo.nearestEdge(35.147, 126.92, 30)).isEqualTo(119462L);
        List<Object> p = db.calls("SELECT id FROM road_edge").get(1).params();
        assertThat((String) p.get(0)).startsWith("POLYGON((126.919");
        assertThat(p.subList(1, 3)).containsExactly(126.92, 35.147);
    }

    @Test
    @DisplayName("지도 범위의 신고 목록에 사진 주소를 붙인다")
    void withinListsPhotoUrls() {
        db.rows("FROM road_report r WHERE r.status IN", row("id", 7L, "report_type", "curb", "mode", "wheelchair",
                        "memo", "턱", "lat", 35.147, "lon", 126.92, "status", "open", "created", "2026-11-16T10:00",
                        "photos", 2, "photo_label", null),
                row("id", 8L, "report_type", "obstacle", "mode", null, "memo", null, "lat", 35.148, "lon", 126.921,
                        "status", "confirmed", "created", "2026-11-16T09:00", "photos", 0, "photo_label", "vehicle"));

        List<ReportItem> got = repo.within(126.91, 35.14, 126.93, 35.15, 50);

        assertThat(got).hasSize(2);
        assertThat(got.get(0).photoUrls()).containsExactly("/api/reports/7/photos/1", "/api/reports/7/photos/2");
        assertThat(got.get(0).typeName()).isEqualTo(ReportType.name("curb"));
        assertThat(got.get(1).photoUrls()).isEmpty();
        // 사진으로 차량이라고 본 막는 물건 신고 (036, 비서 문장용 11-23)
        assertThat(got.get(0).vehicle()).isFalse();
        assertThat(got.get(1).vehicle()).isTrue();
        assertThat(db.call("FROM road_report r WHERE r.status IN").params().get(1)).isEqualTo(50);
    }

    @Test
    @DisplayName("지금 유효한 신고 지점: 피할 모드 목록을 나눠 읽는다")
    void activeSpotsSplitModes() {
        db.rows("FROM road_report_active", row("spot_id", 3L, "report_type", "steep", "lat", 35.147, "lon", 126.92,
                "starts", "2026-11-16", "ends", "2026-12-15", "report_count", 3, "reporter_count", 2, "photo_count", 1,
                "confirmed", 1, "avoid_modes", "senior, wheelchair", "cost_modes", "", "last", "2026-11-16T10:00",
                "last_memo", "가파름"));

        List<ActiveSpot> got = repo.active(126.91, 35.14, 126.93, 35.15, 100);

        assertThat(got).containsExactly(new ActiveSpot(3L, "steep", 35.147, 126.92, "2026-11-16", "2026-12-15", 3, 2, 1,
                true, List.of("senior", "wheelchair"), List.of(), "2026-11-16T10:00", "가파름"));
        assertThat(db.call("FROM road_report_active").params()).containsExactly(126.91, 126.93, 35.14, 35.15, 100);
    }

    @Test
    @DisplayName("오래된 신고는 500개씩 나눠 지운다")
    void cleanUpDeletesInBatchesOf500() {
        Object[] many = new Object[1001];
        for (int i = 0; i < many.length; i++) {
            many[i] = FakeDb.row("id", (long) i);
        }
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object>[] rows = java.util.Arrays.copyOf(many, many.length, java.util.Map[].class);
        db.rows(ReportRepository.ROW_DUE, rows).updates("DELETE FROM road_report WHERE id IN", 400);

        int[] done = repo.cleanUp();

        assertThat(done).containsExactly(0, 1200);
        List<FakeDb.Call> del = db.calls("DELETE FROM road_report WHERE id IN");
        assertThat(del).extracting(c -> c.params().size()).containsExactly(500, 500, 1);
    }

    @Test
    @DisplayName("모드 목록에서 빈 값은 뺀다")
    void modesIgnoresBlanks() {
        assertThat(ReportRepository.modes(null)).isEmpty();
        assertThat(ReportRepository.modes(" ")).isEmpty();
        assertThat(ReportRepository.modes("adult,,child ")).containsExactly("adult", "child");
    }
}
