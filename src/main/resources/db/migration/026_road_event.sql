-- 길 위의 행사·돌발상황 (2026-11-06, 비서가 따지는 정보 · 교수님 피드백 "사고·행사로 막힌 길을 돌아가기").
-- 경로 엔진·비서가 "행사 기간엔 그 둘레를 피하거나 알리기", "앞에 통제가 있어 돌아가요"에 쓴다.
--   festival : 공공데이터포털 전국문화축제표준데이터 (tn_pubr_public_cltur_fstvl_api, DATA_GO_KR_KEY). 장소·기간·좌표
--   its      : 국가교통정보센터 돌발상황 (openapi.its.go.kr eventInfo, ITS_API_KEY). 공사·사고·통제·재난, 대부분 고속도로·국도
-- 넣기: python3 scripts/load_events.py (출처마다 지우고 새로 넣음, 광주 시도 경계 안만). 갱신 명령(refresh_data.py)에도 들어 있다.
-- 행사는 걷는 사람에게 늘 피할 길이 아니다(차 없는 거리는 오히려 걷기 좋음). 모드에 따라 쓰는 쪽에서 정한다:
--   예) 장애인·노인은 붐빔을 알리거나 둘레를 피하고, 일반은 알림만.
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS road_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    source        VARCHAR(20)  NOT NULL COMMENT 'festival 문화축제 표준데이터, its 국가교통정보센터 돌발상황',
    source_id     VARCHAR(64)  NOT NULL COMMENT '출처 안에서 같은 건을 알아보는 값 (축제는 이름·기간·장소 해시)',
    kind          VARCHAR(10)  NOT NULL COMMENT '행사, 공사, 사고, 통제, 재난, 기타',
    detail        VARCHAR(50)  NULL     COMMENT '세부 종류 (돌발: 작업·차량증가/정체·고장 등)',
    title         VARCHAR(200) NOT NULL COMMENT '화면에 보일 이름 (축제 이름, 돌발은 도로 이름 + 종류)',
    place         VARCHAR(300) NULL     COMMENT '장소 설명 (축제 개최장소 "금남로+충장로 일원", 돌발 도로 이름)',
    address       VARCHAR(300) NULL,
    lon           DOUBLE       NOT NULL,
    lat           DOUBLE       NOT NULL,
    geom          POINT        NOT NULL SRID 0 COMMENT 'POINT(경도, 위도), 다른 표와 같음',
    radius_m      SMALLINT     NOT NULL COMMENT '영향 반경 (축제 "일원"은 300m, 돌발 지점은 100m)',
    starts_at     DATETIME     NULL     COMMENT '시작 (한국 시각)',
    ends_at       DATETIME     NULL     COMMENT '끝 (한국 시각). NULL이면 끝을 모름(돌발은 다음 갱신까지 있다고 봄)',
    lanes_blocked VARCHAR(50)  NULL     COMMENT '막힌 차로 (돌발)',
    message       VARCHAR(300) NULL     COMMENT '안내 문구 (돌발 "(2차로) 시설물 보수작업중", 축제 내용 앞부분)',
    url           VARCHAR(300) NULL     COMMENT '홈페이지 (축제)',
    coord_from    VARCHAR(30)  NOT NULL COMMENT '좌표 출처: 원본, 카카오 주소 검색, 카카오 장소 검색',
    fetched_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_road_event (source, source_id),
    KEY idx_road_event_ends (ends_at),
    SPATIAL INDEX sx_road_event_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '길 위의 행사·돌발상황 (광주)';
