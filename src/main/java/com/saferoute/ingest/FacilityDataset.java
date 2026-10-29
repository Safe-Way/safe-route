package com.saferoute.ingest;

import java.util.List;

/**
 * 내려받은 시설 데이터 하나를 시설 표로 옮기는 규칙.
 * 칸 이름 목록은 앞에서부터 값이 있는 첫 칸을 쓴다.
 *
 * @param folder     data/raw/national 아래 폴더 이름 (scripts/download_national.py와 같음)
 * @param type       시설 표 이름 ({@link FacilityTable})
 * @param source     출처 이름 (시설 표의 source 칸)
 * @param title      사람이 읽는 이름
 * @param api        API 주소
 * @param license    이용 조건
 * @param idKeys     원본 번호 칸. 모두 비면 이름+좌표로 번호를 만든다
 * @param nameKeys   이름 칸
 * @param addrKeys   주소 칸
 * @param subtypeKeys 세부 종류 칸
 * @param sidoKey    시도 코드 칸 (없으면 null)
 * @param sggKey     시군구 코드 칸 (없으면 null)
 * @param xKey       메르카토르 x 칸
 * @param yKey       메르카토르 y 칸
 */
public record FacilityDataset(
        String folder,
        String type,
        String source,
        String title,
        String api,
        String license,
        List<String> idKeys,
        List<String> nameKeys,
        List<String> addrKeys,
        List<String> subtypeKeys,
        String sidoKey,
        String sggKey,
        String xKey,
        String yKey) {

    private static final String SAFETYDATA = "https://www.safetydata.go.kr/V2/api/";
    private static final String SAFEMAP = "https://www.safemap.go.kr/openapi2/";
    private static final String KOGL_4 = "공공누리 4유형 (출처표시·상업적 이용 금지·변경 금지)";
    private static final String SAFETYDATA_LICENSE = "이용허락범위 제한 없음";

    /** 재난안전데이터 공유플랫폼 (행정안전부 공통POI) */
    private static FacilityDataset safetydata(String folder, String type, String title, String apiId) {
        return new FacilityDataset(folder, type, "safetydata", title, SAFETYDATA + apiId, SAFETYDATA_LICENSE,
                List.of("SN"),
                List.of("POLSTN_NM", "FCLT_GVMNFC_NM", "NTNLPLCAGNC_NM"),
                List.of("ROAD_NM_ADDR", "ADDR"),
                List.of("FCLT_TYPE"),
                "STDG_CTPV_CD", "STDG_SGG_CD", "XMAP_CRTS", "YMAP_CRTS");
    }

    /** 생활안전지도 */
    private static FacilityDataset safemap(String folder, String type, String title, String apiId,
            List<String> idKeys, List<String> nameKeys, List<String> addrKeys, List<String> subtypeKeys,
            String sidoKey, String sggKey) {
        return new FacilityDataset(folder, type, "safemap", title, SAFEMAP + apiId, KOGL_4,
                idKeys, nameKeys, addrKeys, subtypeKeys, sidoKey, sggKey, "x", "y");
    }

    /** API로 받는 시설 데이터 전체 (CCTV는 CSV 파일이라 따로 읽는다) */
    public static final List<FacilityDataset> ALL = List.of(
            safetydata("police", "police", "행정안전부_공통POI_경찰", "DSSP-IF-00080"),
            safetydata("security_light", "security_light", "행정안전부_공통POI_보안등", "DSSP-IF-00083"),
            safetydata("streetlight", "streetlight", "행정안전부_공통POI_가로등", "DSSP-IF-00084"),
            safemap("emergency_bell", "emergency_bell", "생활안전지도_안전비상벨", "IF_0032",
                    List.of("objt_id"), List.of(), List.of("rn_adres", "ins_detail", "adres"),
                    List.of("ins_type"), "ctprvn_cd", "sgg_cd"),
            safemap("convenience_store", "convenience_store", "생활안전지도_편의점", "IF_0039",
                    List.of("objt_id"), List.of("fclty_nm"), List.of("rn_adres", "adres"),
                    List.of("fclty_ty"), "ctprvn_cd", "sgg_cd"),
            safemap("child_safety_house", "child_safety_house", "생활안전지도_아동안전지킴이집", "IF_0055",
                    List.of("objt_id"), List.of("fclty_nm"), List.of("rn_adres", "adres"),
                    List.of("fclty_ty"), "ctprvn_cd", "sgg_cd"),
            safemap("heat_shelter", "heat_shelter", "생활안전지도_무더위쉼터", "IF_0001",
                    List.of("num"), List.of("cc_nm"), List.of("rn_adres", "adres"),
                    List.of("cc_type"), null, null),
            safemap("cold_shelter", "cold_shelter", "생활안전지도_한파쉼터", "IF_0136",
                    List.of(), List.of("reare_nm"), List.of("rona_daddr"),
                    List.of("fclt_type"), null, null),
            safemap("public_toilet", "public_toilet", "생활안전지도_공중화장실", "IF_0132",
                    List.of("num"), List.of("fclty_nm"), List.of("lctn_rona_addr", "lctn_lotno_addr"),
                    List.of("fclty_se"), null, null));
}
