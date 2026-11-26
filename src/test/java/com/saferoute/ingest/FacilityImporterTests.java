package com.saferoute.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import com.saferoute.testsupport.FakeDb;
import com.saferoute.testsupport.FakeDb.Call;

/**
 * 내려받은 파일 → 시설 표 적재를 가짜 DB로 끝까지 돌려 본다 (어떤 줄이 들어가고 빠지는지, 출처 현황).
 * <p>무엇을: data/raw 의 API JSON 페이지·CSV 파일을 읽어 police·cctv·security_light 같은 시설 표에 넣는 FacilityImporter.
 * <p>어떻게: @TempDir 임시 폴더에 원본 몇 줄짜리 파일을 직접 쓰고, FakeDb 로 실행된 DELETE·INSERT 와 넣은 값을 꺼내 본다.
 * 진짜 DB는 쓰지 않는다.
 * <p>왜: 깨지면 좌표가 바다 위인 시설이 지도에 찍히거나, 글자가 깨진 이름이 들어가거나, 다시 적재할 때 옛 줄이 남아 겹친다.
 */
@DisplayName("시설 적재: 내려받은 파일 → 시설 표")
class FacilityImporterTests {

    /** 공공데이터 포털 CSV 대부분이 쓰는 한글 글자 집합 (CP949) */
    private static final Charset MS949 = Charset.forName("MS949");

    /** data/raw 대신 쓰는 임시 폴더. 시험마다 새로 비어 있다 */
    @TempDir
    Path raw;

    private FakeDb db;
    private FacilityImporter importer;

    @BeforeEach
    void setUp() {
        db = new FakeDb()
                .updates("DELETE FROM", 0) // 나눠 지우기는 0줄이 될 때까지 돈다
                .rows("SELECT COUNT(*) FROM", FakeDb.row("n", 1)); // 적재 뒤 "실제 들어간 건수"는 늘 1 로 답한다
        importer = new FacilityImporter(db.jdbc()).rawRoot(raw);
    }

    /** 임시 폴더 아래 path 에 body 를 cs 글자 집합으로 쓴다 (원본 파일 흉내) */
    private void write(String path, String body, Charset cs) throws Exception {
        Path p = raw.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, cs);
    }

    /** 적재를 명령줄 인자(--types=...)와 함께 돌린다 */
    private void run(String... args) throws Exception {
        importer.run(new DefaultApplicationArguments(args));
    }

    /** 그 표에 넣은 줄들 (배치라 줄마다 Call 하나) */
    private List<Call> inserts(String table) {
        return db.calls("INSERT IGNORE INTO " + table + " ");
    }

    /** data_source(출처별 현황) 표에 남긴 그 종류의 줄 */
    private Call source(String type) {
        return db.calls("INSERT INTO data_source").stream().filter(c -> c.params().get(0).equals(type)).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("API 페이지에서 한국 좌표가 있는 줄만 넣는다")
    void apiPagesKeepOnlyRowsWithKoreanCoordinates() throws Exception {
        // 재난안전데이터 경찰: 메르카토르 좌표. 좌표 없는 줄·한국 밖 줄은 빠진다
        // 11번은 광주 금남로 근처 진짜 좌표, 12번은 좌표가 빈칸·null, 13번은 (0, 0) = 적도 바다 위
        write("national/police/_total.txt", "3\n", StandardCharsets.UTF_8);
        write("national/police/p00001.json", """
                [{"SN": "11", "FCLT_GVMNFC_NM": "충장치안센터", "POLSTN_NM": "광주동부", "ROAD_NM_ADDR": "광주 동구 금남로 1",
                  "FCLT_TYPE": "치안센터", "STDG_CTPV_CD": "29.0", "STDG_SGG_CD": "2.911E7", "XMAP_CRTS": "14128669.77",
                  "YMAP_CRTS": "4184284.27", "TELNO": "062-000-0000", "GEOM": "POINT(...)", "ETC": "-"},
                 {"SN": "12", "FCLT_GVMNFC_NM": "좌표 없음", "XMAP_CRTS": "", "YMAP_CRTS": null},
                 {"SN": "13", "FCLT_GVMNFC_NM": "바다 한가운데", "XMAP_CRTS": "0", "YMAP_CRTS": "0"}]
                """, StandardCharsets.UTF_8);

        run("--types=police");

        // 다시 적재해도 겹치지 않게 같은 출처 줄을 먼저 지운다
        assertThat(db.call("DELETE FROM police").params()).containsExactly("safetydata");
        List<Call> rows = inserts("police");
        assertThat(rows).hasSize(1);
        List<Object> p = rows.get(0).params();
        // kind, source, source_id, name, address, sido, sgg, lon, lat, lon, lat, attrs
        // 시도·시군구 코드는 "29.0", "2.911E7" 처럼 숫자 모양으로 와서 "29", "29110" 으로 다듬어야 한다
        assertThat(p.subList(0, 7)).containsExactly("치안센터", "safetydata", "11", "충장치안센터", "광주 동구 금남로 1",
                "29", "29110");
        assertThat((Double) p.get(7)).isCloseTo(126.92, within(1e-6));
        assertThat((Double) p.get(8)).isCloseTo(35.15, within(1e-6));
        // 쓰지 않은 칸만 attrs 로 ("-"·GEOM 은 버림)
        assertThat((String) p.get(11)).isEqualTo("{\"TELNO\":\"062-000-0000\"}");
        assertThat(p).hasSize(12); // 경찰 표에는 개수 칸이 없음

        Call src = source("police");
        // type, source, title, api, license, total, loaded, skipped, complete
        // 전체 3, 들어감 1(FakeDb 의 COUNT 답), 빠짐 2, 3건이라 1쪽이면 다 받음
        assertThat(src.params().subList(5, 9)).containsExactly(3, 1, 2, true);
        // --types 로 고른 것만
        assertThat(db.calls("DELETE FROM cctv")).isEmpty();
    }

    @Test
    @DisplayName("번호 없는 데이터는 줄 해시를 번호로, 개수도 넣는다")
    void lonLatDatasetWithoutIdUsesRowHashAndCount() throws Exception {
        // 보안등 표준데이터: 경도·위도, 번호 칸 없음 → 줄 내용으로 번호, 설치 개수는 lamp 개수 칸
        // 개수는 3(보통), 0(이상한 값 → 1), 40000(SMALLINT 칸을 넘음 → 32767)을 하나씩 넣었다.
        // _total.txt 는 2500인데 페이지는 1쪽뿐이라 "아직 다 받지 않음"이 되어야 한다. notes.txt 는 페이지가 아니라 무시
        write("national/security_light_std/_total.txt", "2500", StandardCharsets.UTF_8);
        write("national/security_light_std/p00001.json", """
                [{"lmpLcNm": "동구 보안등 1", "rdnmadr": "", "lnmadr": "광주 동구 서석동 1", "installationType": "벽부형",
                  "longitude": "126.93", "latitude": "35.14", "installationCo": "3"},
                 {"lmpLcNm": "동구 보안등 2", "longitude": "126.93", "latitude": "35.14", "installationCo": "0"},
                 {"lmpLcNm": "동구 보안등 3", "longitude": "126.93", "latitude": "35.14", "installationCo": "40000"}]
                """, StandardCharsets.UTF_8);
        write("national/security_light_std/notes.txt", "페이지 파일이 아님", StandardCharsets.UTF_8);

        run("--types=security_light_std");

        List<Call> rows = inserts("security_light");
        assertThat(rows).hasSize(3);
        List<Object> first = rows.get(0).params();
        assertThat(first.get(0)).isEqualTo("벽부형");
        assertThat((String) first.get(2)).hasSize(24); // SHA-1 앞 12바이트
        assertThat(first.get(4)).isEqualTo("광주 동구 서석동 1"); // 빈 도로명주소 다음 칸
        assertThat(first.get(5)).isNull(); // 시도 코드 없음
        assertThat(first.get(12)).isEqualTo(3); // 개수 칸
        assertThat(rows.get(1).params().get(12)).isEqualTo(1); // 0 → 1
        assertThat(rows.get(2).params().get(12)).isEqualTo((int) Short.MAX_VALUE); // 너무 크면 자름
        // 2,500건 중 1쪽(1,000건)만 받았으니 다 받지 않음
        assertThat(source("security_light").params().get(8)).isEqualTo(false);
    }

    @Test
    @DisplayName("종류 코드는 이름으로 바꾸고 긴 값은 자른다")
    void kindCodesBecomeNamesAndLongValuesAreCut() throws Exception {
        // 횡단보도 종류 코드는 "1"·"01" 이 섞여 와서 두 자리로 맞춘다. 이름(도로명) 칸은 200자라 250자는 잘라야
        // INSERT 가 "값이 너무 김" 오류로 통째로 실패하지 않는다
        write("national/crosswalk/_total.txt", "1", StandardCharsets.UTF_8);
        String longName = "가".repeat(250);
        write("national/crosswalk/p00001.json", "[{\"roadNm\": \"" + longName + "\", \"crslkKnd\": \"1\","
                + " \"longitude\": \"126.92\", \"latitude\": \"35.15\"}]", StandardCharsets.UTF_8);

        run("--types=crosswalk");

        List<Object> p = inserts("crosswalk").get(0).params();
        assertThat(p.get(0)).isEqualTo("01");
        assertThat((String) p.get(3)).hasSize(200);
    }

    @Test
    @DisplayName("없는 파일은 건너뛴다")
    void missingFilesAreSkipped() throws Exception {
        // 아직 내려받지 않은 종류가 있어도 적재가 멈추면 안 된다. 특히 DELETE 도 없어야 한다
        // (파일이 없다고 이미 들어 있던 표를 비우면 지도에서 시설이 사라진다)
        run();

        assertThat(db.calls("INSERT")).isEmpty();
        assertThat(db.calls("DELETE")).isEmpty();
    }

    @Test
    @DisplayName("전국 CCTV CSV(MS949 글자)를 읽는다")
    void nationalCctvCsvInMs949() throws Exception {
        // C-1: 따옴표 안 쉼표("북, 남")와 따옴표 친 숫자("2"), 도로명주소가 비어 지번주소를 씀
        // C-2: 카메라 대수가 비어 1 로, 남길 칸이 없어 attrs 는 null
        // C-3: 좌표 없음 → 빠짐, C-4: 위도·경도 10도(한국 밖) → 빠짐
        write("national/cctv_national.csv", String.join("\n",
                "관리번호,관리기관명,소재지도로명주소,소재지지번주소,설치목적구분,카메라대수,카메라화소수,촬영방면정보,보관일수,설치연월,관리기관전화번호,WGS84위도,WGS84경도,데이터기준일자",
                "C-1,광주 동구청,,광주 동구 서석동 2,생활방범,\"2\",200,\"북, 남\",30,2020-01,062,35.14,126.93,2026-01-01",
                "C-2,광주 동구청,광주 동구 필문대로 1,,어린이보호,,,,,,,35.14,126.93,",
                "C-3,광주 동구청,,,생활방범,1,,,,,,,,",
                "C-4,해외,,,생활방범,1,,,,,,10.0,10.0,"), MS949);

        run("--types=cctv");

        assertThat(db.call("DELETE FROM cctv").params()).containsExactly("localdata");
        List<Call> rows = db.calls("INSERT IGNORE INTO cctv");
        assertThat(rows).hasSize(2);
        List<Object> a = rows.get(0).params();
        assertThat(a.subList(0, 5)).containsExactly("생활방범", "localdata", "C-1", "광주 동구청", "광주 동구 서석동 2");
        assertThat((String) a.get(11)).contains("\"촬영방면정보\":\"북, 남\"");
        assertThat(a.get(12)).isEqualTo(2);
        List<Object> b = rows.get(1).params();
        assertThat(b.get(4)).isEqualTo("광주 동구 필문대로 1");
        assertThat(b.get(11)).isNull(); // 남길 칸이 없으면 attrs 없음
        assertThat(b.get(12)).isEqualTo(1);
        // 전체 4, 들어감 1(FakeDb 의 COUNT 답이라 실제 넣은 2와 다름), 빠짐 2
        assertThat(source("cctv").params().subList(5, 9)).containsExactly(4, 1, 2, true);
    }

    @Test
    @DisplayName("지역 CSV의 BOM·글자 집합을 맞춰 읽는다")
    void regionalCsvFilesWithBomAndCharset() throws Exception {
        // 광주시 CCTV는 MS949, 동구 보안등은 UTF-8 에 맨 앞 BOM(U+FEFF) 이 붙어 온다. BOM 을 안 떼면 첫 칸 이름이
        // "보안등위치명"과 달라져 이름을 못 찾는다. CCTV 둘째 줄은 좌표가 없어 빠진다
        write("regional/gwangju_cctv.csv", String.join("\n",
                "관리기관명,소재지도로명주소,소재지지번주소,카메라대수,카메라화소,위도,경도",
                "광주광역시,,광주 동구 1,3,200,35.14,126.93",
                "광주광역시,,,1,,,"), MS949);
        write("regional/donggu_security_light.csv", "﻿" + String.join("\n",
                "보안등위치명,설치형태,설치개수,소재지도로명주소,위도,경도,설치연도",
                "서석동 1,벽부형,2,광주 동구 서석로 1,35.14,126.93,2019"), StandardCharsets.UTF_8);

        run("--types=cctv,security_light");

        List<Call> cctv = db.calls("INSERT IGNORE INTO cctv");
        assertThat(cctv).hasSize(1);
        // 지역 CSV는 원본 번호가 없어 줄 해시를 번호로 쓴다 (값은 그대로 꺼내 비교), 광주시 CCTV는 세부 종류 칸이 없어 null
        assertThat(cctv.get(0).params().subList(0, 5)).containsExactly(null, "gwangju",
                cctv.get(0).params().get(2), "광주광역시", "광주 동구 1");
        assertThat(cctv.get(0).params().get(12)).isEqualTo(3);
        List<Call> lights = db.calls("INSERT IGNORE INTO security_light");
        assertThat(lights).hasSize(1);
        assertThat(lights.get(0).params().subList(0, 5)).containsExactly("벽부형", "gwangju_donggu",
                lights.get(0).params().get(2), "서석동 1", "광주 동구 서석로 1");
        assertThat((String) lights.get(0).params().get(11)).isEqualTo("{\"설치연도\":\"2019\"}");
        // 출처 현황의 api 칸에 어느 파일에서 왔는지 남긴다
        Call src = source("security_light");
        assertThat((String) src.params().get(3)).endsWith("(donggu_security_light.csv)");
    }

    @Test
    @DisplayName("개수 칸은 그 칸이 있는 표에만 넣는다")
    void insertSqlAddsCountColumnOnlyForTablesWithIt() {
        // 개수 칸이 없는 표에 camera_count 를 넣으면 "없는 칸" 오류로 그 종류 적재가 통째로 실패한다
        assertThat(FacilityImporter.insertSql(FacilityTable.of("police")))
                .doesNotContain("camera_count").endsWith("?, ?), 0), ?)");
        assertThat(FacilityImporter.insertSql(FacilityTable.CCTV)).contains(", camera_count)").endsWith(", ?, ?)");
    }
}
