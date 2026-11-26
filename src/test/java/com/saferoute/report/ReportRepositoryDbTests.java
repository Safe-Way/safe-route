package com.saferoute.report;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 신고 저장·지도 목록·지금 유효한 신고·사진 SQL (가짜 DB로 끝까지)
 * <p>무엇을: {@link ReportRepository}가 불편 신고를 넣고, 지도 범위의 신고·유효 지점·사진을 읽고, 오래된 신고를 지우는 SQL과 값.
 * <p>어떻게: 이름에 Db가 붙었지만 진짜 MySQL은 안 쓴다. FakeDb(가짜 JDBC)가 정해 둔 줄·새 번호(7)를 돌려주고 넣은 값을 남긴다.
 * 그래서 CI·로컬 어디서나 DB 없이 돈다. 뷰(road_report_active)의 기간 계산 같은 SQL 자체의 결과는 여기서 시험하지 못한다.
 * <p>왜: ? 순서가 하나만 어긋나도 경도·위도가 뒤바뀌어 신고가 엉뚱한 곳에 찍힌다.
 * 033·036 전 DB로 되돌아가는 길이 깨지면 신고 저장이 통째로 실패하고, 지우기를 나누지 않으면 IN 목록이 너무 길어진다.
 */
@DisplayName("신고 저장·지도 목록·유효 신고·사진 SQL")
class ReportRepositoryDbTests {

    // 모든 INSERT가 새 번호 7을 돌려준다
    private final FakeDb db = new FakeDb().generatedKey(7L);
    private final ReportRepository repo = new ReportRepository(db.jdbc());

    /** 위도 35.147, 경도 126.92 의 보도턱 신고. 정확도(m)만 바꿔 쓴다 */
    private static NewReport curbReport(Integer accuracy) {
        return new NewReport("curb", "wheelchair", "턱이 높아요", 35.147, 126.92, accuracy, List.of());
    }

    // 033 뒤 DB에는 신고한 사람 표시(reporter_hash)가 들어가야 같은 사람이 여러 번 신고해도 한 명으로 센다.
    // 값 순서: 종류, 모드, 메모, lon, lat, 그리고 geom 용 POINT(lon, lat) 한 번 더, 정확도, 길 번호, via, hash
    @Test
    @DisplayName("신고를 신고한 사람 해시와 함께 저장한다")
    void insertWithReporterHash() {
        // 인자: 신고, 가까운 길 번호(99), via(voice = 비서에게 말로), 신고한 사람 해시
        long id = repo.insert(curbReport(12), 99L, "voice", "abc");

        assertThat(id).isEqualTo(7L);
        FakeDb.Call c = db.call("INSERT INTO road_report (");
        assertThat(c.sql()).contains(", reporter_hash) VALUES");
        assertThat(c.params()).containsExactly("curb", "wheelchair", "턱이 높아요", 126.92, 35.147, 126.92, 35.147, 12,
                99L, "voice", "abc");
    }

    // 위치 정확도를 못 받았거나 가까운 길을 못 찾은 신고도 저장돼야 한다 (setNull 로 NULL).
    // 인자 두 개짜리 insert 는 화면 사진 신고라 via 기본값 photo, 해시는 null
    @Test
    @DisplayName("정확도·가까운 길이 없으면 빈 값, 경로는 기본값으로 저장한다")
    void insertWithoutAccuracyEdgeAndDefaultVia() {
        repo.insert(curbReport(null), null);

        assertThat(db.call("INSERT INTO road_report (").params())
                .containsExactly("curb", "wheelchair", "턱이 높아요", 126.92, 35.147, 126.92, 35.147, null, null, "photo",
                        null);
    }

    // reporter_hash 칸이 없는 033 이전 DB에서도 신고는 저장돼야 한다. 첫 실패 뒤에는 기억해 두고 바로 옛 SQL로 간다:
    // 매번 새 SQL부터 시도하면 신고마다 오류 한 번과 경고 로그가 남는다
    @Test
    @DisplayName("033 이전 DB면 옛 칸으로 저장한다 (새 칸은 처음 한 번만 시도)")
    void oldDbUsesOldSql() {
        String newSql = ", reporter_hash) VALUES";
        // 새 SQL은 "via, reporter_hash) VALUES"라 이 글자에 안 걸린다: 옛 SQL만 골라 센다
        String oldSql = "edge_id, via) VALUES";
        db.fails(newSql, "Unknown column 'reporter_hash'"); // 033 이전 DB 흉내
        NewReport report = curbReport(5);
        long roadId = 1L;
        String via = "photo";
        String hash = "abc";
        long newId = 7L;   // 맨 위 FakeDb.generatedKey(7L)가 돌려주는 번호
        int oldSqlParams = 10; // 옛 INSERT 문의 ? 개수

        long firstId = repo.insert(report, roadId, via, hash);
        long secondId = repo.insert(report, roadId, via, hash);

        assertThat(firstId).isEqualTo(newId);
        assertThat(secondId).isEqualTo(newId);
        // 새 SQL은 첫 번째 저장에서 딱 한 번만 시도됐다
        assertThat(db.calls(newSql)).hasSize(1);
        List<FakeDb.Call> oldSqlCalls = db.calls(oldSql);
        assertThat(oldSqlCalls).hasSize(2);
        // 옛 SQL에는 해시 값이 빠진다
        assertThat(oldSqlCalls.get(0).params()).hasSize(oldSqlParams);
    }

    // 사진은 바이트 그대로 넣고 크기(size_bytes)는 서버가 길이로 채운다.
    // 없는 사진(2번)이나 숨긴·기간 지난 신고의 사진은 null 이어야 404 로 답한다
    @Test
    @DisplayName("신고 사진을 넣고 읽는다")
    void photoInsertAndRead() {
        byte[] jpg = {1, 2, 3};
        repo.insertPhoto(7L, 1, new NewReport.Photo("image/jpeg", jpg));
        // 값: 신고 번호, 사진 순번, 종류, 크기 3바이트, 바이트
        assertThat(db.call("INSERT INTO road_report_photo").params()).containsExactly(7L, 1, "image/jpeg", 3, jpg);

        // 아직 줄을 정하지 않아 빈 결과 → null
        assertThat(repo.photo(7L, 2)).isNull();
        db.rows("SELECT p.content_type, p.data", row("content_type", "image/jpeg", "data", jpg));
        NewReport.Photo p = repo.photo(7L, 1);
        assertThat(p.contentType()).isEqualTo("image/jpeg");
        assertThat(p.data()).isEqualTo(jpg);
    }

    // 30m 안 길이 없으면 null (신고 칸 edge_id 는 비어도 됨).
    // 찾을 사각형은 m 를 도로 바꿔 만들므로, 서쪽 끝이 126.92 보다 조금 작은 126.919… 로 시작하는지 본다
    @Test
    @DisplayName("신고 위치에서 가장 가까운 길 구간을 찾는다")
    void nearestEdge() {
        assertThat(repo.nearestEdge(35.147, 126.92, 30)).isNull();

        db.rows("SELECT id FROM road_edge", row("id", 119462L));
        assertThat(repo.nearestEdge(35.147, 126.92, 30)).isEqualTo(119462L);
        // 0번은 위의 빈 결과 호출, 1번이 줄을 정한 뒤 호출
        List<Object> p = db.calls("SELECT id FROM road_edge").get(1).params();
        assertThat((String) p.get(0)).startsWith("POLYGON((126.919");
        // 거리 순 정렬용 POINT 는 (경도, 위도) 순서
        assertThat(p.subList(1, 3)).containsExactly(126.92, 35.147);
    }

    // 앱은 사진 주소를 받아 그대로 띄우므로 장 수만큼 /api/reports/{id}/photos/{순번} 을 1부터 만들어야 한다.
    // 차량 표시(vehicle)는 photo_label = vehicle 이고 종류가 obstacle 일 때만 참이다 (036)
    @Test
    @DisplayName("지도 범위의 신고 목록에 사진 주소를 붙인다")
    void withinListsPhotoUrls() {
        db.rows("FROM road_report r WHERE r.status IN", row("id", 7L, "report_type", "curb", "mode", "wheelchair",
                        "memo", "턱", "lat", 35.147, "lon", 126.92, "status", "open", "created", "2026-11-16T10:00",
                        "photos", 2, "photo_label", null),
                row("id", 8L, "report_type", "obstacle", "mode", null, "memo", null, "lat", 35.148, "lon", 126.921,
                        "status", "confirmed", "created", "2026-11-16T09:00", "photos", 0, "photo_label", "vehicle"));

        // 인자: 서, 남, 동, 북, 최대 개수
        List<ReportItem> got = repo.within(126.91, 35.14, 126.93, 35.15, 50);

        assertThat(got).hasSize(2);
        assertThat(got.get(0).photoUrls()).containsExactly("/api/reports/7/photos/1", "/api/reports/7/photos/2");
        assertThat(got.get(0).typeName()).isEqualTo(ReportType.name("curb"));
        assertThat(got.get(1).photoUrls()).isEmpty();
        // 사진으로 차량이라고 본 막는 물건 신고 (036, 비서 문장용 11-23)
        assertThat(got.get(0).vehicle()).isFalse();
        assertThat(got.get(1).vehicle()).isTrue();
        // 값 0번은 범위 POLYGON, 1번이 LIMIT
        assertThat(db.call("FROM road_report r WHERE r.status IN").params().get(1)).isEqualTo(50);
    }

    // 뷰는 피할 모드를 "senior, wheelchair" 같은 쉼표 글로 준다. 길찾기는 목록이 필요하니 공백 없이 나눠야 한다.
    // 뷰는 경도·위도 BETWEEN 으로 거르므로 값 순서가 서, 동, 남, 북 (within 의 POLYGON 과 다름)
    @Test
    @DisplayName("지금 유효한 신고 지점: 피할 모드 목록을 나눠 읽는다")
    void activeSpotsSplitModes() {
        db.rows("FROM road_report_active", row("spot_id", 3L, "report_type", "steep", "lat", 35.147, "lon", 126.92,
                "starts", "2026-11-16", "ends", "2026-12-15", "report_count", 3, "reporter_count", 2, "photo_count", 1,
                "confirmed", 1, "avoid_modes", "senior, wheelchair", "cost_modes", "", "last", "2026-11-16T10:00",
                "last_memo", "가파름"));

        List<ActiveSpot> got = repo.active(126.91, 35.14, 126.93, 35.15, 100);

        // confirmed 1 → true, 빈 cost_modes → 빈 목록
        assertThat(got).containsExactly(new ActiveSpot(3L, "steep", 35.147, 126.92, "2026-11-16", "2026-12-15", 3, 2, 1,
                true, List.of("senior", "wheelchair"), List.of(), "2026-11-16T10:00", "가파름"));
        assertThat(db.call("FROM road_report_active").params()).containsExactly(126.91, 126.93, 35.14, 35.15, 100);
    }

    // 지울 번호가 1001개면 IN 목록을 500, 500, 1 로 나눠야 한다 (한 번에 다 넣으면 SQL이 너무 길어짐).
    // 지운 줄 수는 가짜 DB가 매번 400 을 돌려주게 해 둔 것이라, 1200 은 "세 번의 결과를 더했다"는 뜻일 뿐이다.
    // 사진 지울 목록(PHOTO_DUE)은 정하지 않아 빈 결과 → 지운 사진 0
    @Test
    @DisplayName("오래된 신고는 500개씩 나눠 지운다")
    void cleanUpDeletesInBatchesOf500() {
        Object[] many = new Object[1001];
        for (int i = 0; i < many.length; i++) {
            many[i] = FakeDb.row("id", (long) i);
        }
        // rows(...)가 Map 가변 인자를 받아서 Object[] 를 Map[] 로 옮긴다
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object>[] rows = java.util.Arrays.copyOf(many, many.length, java.util.Map[].class);
        db.rows(ReportRepository.ROW_DUE, rows).updates("DELETE FROM road_report WHERE id IN", 400);

        int[] done = repo.cleanUp();

        // {지운 사진 장 수, 지운 신고 수}
        assertThat(done).containsExactly(0, 1200);
        List<FakeDb.Call> del = db.calls("DELETE FROM road_report WHERE id IN");
        assertThat(del).extracting(c -> c.params().size()).containsExactly(500, 500, 1);
    }

    // 뷰나 손으로 넣은 값에 빈 칸·쉼표 겹침·끝 공백이 있어도 빈 모드가 목록에 들어가면 안 된다
    @Test
    @DisplayName("모드 목록에서 빈 값은 뺀다")
    void modesIgnoresBlanks() {
        assertThat(ReportRepository.modes(null)).isEmpty();
        assertThat(ReportRepository.modes(" ")).isEmpty();
        assertThat(ReportRepository.modes("adult,,child ")).containsExactly("adult", "child");
    }
}
