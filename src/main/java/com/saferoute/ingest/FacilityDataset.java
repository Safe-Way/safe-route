package com.saferoute.ingest;

import java.util.List;
import java.util.Map;

/**
 * 내려받은 시설 데이터 하나를 시설 표로 옮기는 규칙.
 * 칸 이름 목록은 앞에서부터 값이 있는 첫 칸을 쓴다.
 *
 * @param folder      data/raw/national 아래 폴더 이름 (scripts/download_national.py와 같음)
 * @param type        시설 표 이름 ({@link FacilityTable})
 * @param source      출처 이름 (시설 표의 source 칸)
 * @param title       사람이 읽는 이름
 * @param api         API 주소
 * @param license     이용 조건
 * @param idKeys      원본 번호 칸. 모두 비면 원본 줄 전체로 번호를 만든다
 * @param nameKeys    이름 칸
 * @param addrKeys    주소 칸
 * @param subtypeKeys 세부 종류 칸
 * @param sidoKey     시도 코드 칸 (없으면 null). 값의 앞 2자리를 쓴다
 * @param sggKey      시군구 코드 칸 (없으면 null). 값의 앞 5자리를 쓴다
 * @param xKey        x 칸 (메르카토르 x, 또는 경도)
 * @param yKey        y 칸 (메르카토르 y, 또는 위도)
 * @param lonLat      true면 x·y가 이미 경도·위도, false면 메르카토르(EPSG:3857)
 * @param kindNames   세부 종류가 코드로 올 때 이름으로 바꾸는 표 (없으면 빈 Map)
 * @param countKey    개수 칸 (보안등 설치 개수 등). 없으면 null이고 개수는 1
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
        String yKey,
        boolean lonLat,
        Map<String, String> kindNames,
        String countKey) {

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
                "STDG_CTPV_CD", "STDG_SGG_CD", "XMAP_CRTS", "YMAP_CRTS", false, Map.of(), null);
    }

    /** 생활안전지도 */
    private static FacilityDataset safemap(String folder, String type, String title, String apiId,
            List<String> idKeys, List<String> nameKeys, List<String> addrKeys, List<String> subtypeKeys,
            String sidoKey, String sggKey) {
        return new FacilityDataset(folder, type, "safemap", title, SAFEMAP + apiId, KOGL_4,
                idKeys, nameKeys, addrKeys, subtypeKeys, sidoKey, sggKey, "x", "y", false, Map.of(), null);
    }

    /**
     * 재난안전데이터 공유플랫폼 한파쉼터 (DSSP-IF-10804).
     * 생활안전지도 한파쉼터(IF_0136)는 10,000건까지만 줘서 이것으로 바꿨다 (전국 54,378건).
     * 쉼터 번호(REARE_FCLT_NO) 앞 5자리가 시군구 코드다 (예: 11110... = 서울 종로구).
     * 시설 유형 코드는 생활안전지도 자료와 이름을 맞춰 보고 정했다.
     */
    static final FacilityDataset COLD_SHELTER = new FacilityDataset(
            "cold_shelter_safetydata", "cold_shelter", "safetydata", "행정안전부_한파쉼터",
            SAFETYDATA + "DSSP-IF-10804", "확인 필요",
            List.of("REARE_FCLT_NO"), List.of("REARE_NM"), List.of("RONA_DADDR", "DADDR"), List.of("FCLT_TYPE"),
            "REARE_FCLT_NO", "REARE_FCLT_NO", "LOT", "LAT", true,
            Map.of("001", "공공시설", "003", "특정계층이용시설", "004", "생활밀착민간시설"), null);

    /**
     * 공공데이터포털 전국보안등정보표준데이터 (tn_pubr_public_scrty_lmp_api, 약 186만 건).
     * 지자체가 직접 올려서 공통POI 보안등(약 95만 건)보다 촘촘하다. 원본 번호가 없어 줄 전체로 번호를 만든다.
     * 기관 코드(insttCode)는 행정구역 코드가 아니라서 시도·시군구 코드는 비워 둔다.
     */
    static final FacilityDataset SECURITY_LIGHT_STD = new FacilityDataset(
            "security_light_std", "security_light", "data_go_kr", "전국보안등정보표준데이터",
            "https://api.data.go.kr/openapi/tn_pubr_public_scrty_lmp_api", "확인 필요",
            List.of(), List.of("lmpLcNm"), List.of("rdnmadr", "lnmadr"), List.of("installationType"),
            null, null, "longitude", "latitude", true, Map.of(), "installationCo");

    /**
     * 공공데이터포털 전국육교정보표준데이터 (tn_pubr_public_overpass_api, 약 1,400건).
     * 장애인 편의시설 종류(handicapCvntlType: 없음, 경사로, 엘리베이터 등)를 세부 종류로 넣는다.
     * 휠체어로 지나갈 수 있는지는 footbridge 표의 wheelchair_ok 칸이 판단한다 (schema.sql 참고).
     * 원본 번호가 없어 줄 전체로 번호를 만든다.
     */
    static final FacilityDataset FOOTBRIDGE = new FacilityDataset(
            "footbridge", "footbridge", "data_go_kr", "전국육교정보표준데이터",
            "https://api.data.go.kr/openapi/tn_pubr_public_overpass_api", "확인 필요",
            List.of(), List.of("ovrpsNm"), List.of("rdnmadr", "lctnLotnoAddr"), List.of("handicapCvntlType"),
            null, null, "longitude", "latitude", true, Map.of(), null);

    /**
     * 공공데이터포털 전국횡단보도표준데이터 (tn_pubr_public_crosswalk_api, 약 5만 9천 건).
     * 이름 칸이 없어 도로명을 이름으로 쓰고, 횡단보도 종류 코드는 "1"과 "01"처럼 섞여 와서 두 자리로 맞춘다.
     * 보도턱 낮춤·점자블록·음향신호기 등은 attrs에 두고 crosswalk 표의 가상 칸으로 꺼낸다 (schema.sql 참고).
     * 지역 편차가 커서 광주는 북구 285건뿐이다 (조선대 주변 없음).
     */
    static final FacilityDataset CROSSWALK = new FacilityDataset(
            "crosswalk", "crosswalk", "data_go_kr", "전국횡단보도표준데이터",
            "https://api.data.go.kr/openapi/tn_pubr_public_crosswalk_api", "확인 필요",
            List.of(), List.of("roadNm"), List.of("rdnmadr", "lnmadr"), List.of("crslkKnd"),
            null, null, "longitude", "latitude", true,
            Map.of("1", "01", "2", "02", "3", "03", "4", "04"), null);

    /** API로 받는 시설 데이터 전체 (CCTV는 CSV 파일이라 따로 읽는다) */
    public static final List<FacilityDataset> ALL = List.of(
            safetydata("police", "police", "행정안전부_공통POI_경찰", "DSSP-IF-00080"),
            safetydata("security_light", "security_light", "행정안전부_공통POI_보안등", "DSSP-IF-00083"),
            SECURITY_LIGHT_STD,
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
            COLD_SHELTER,
            safemap("public_toilet", "public_toilet", "생활안전지도_공중화장실", "IF_0132",
                    List.of("num"), List.of("fclty_nm"), List.of("lctn_rona_addr", "lctn_lotno_addr"),
                    List.of("fclty_se"), null, null),
            FOOTBRIDGE,
            CROSSWALK);
}
