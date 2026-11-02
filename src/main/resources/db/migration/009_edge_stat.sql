-- 길(road_edge)마다 감점의 원값을 담는 edge_stat 표를 만든다. (2026-11-02, 한 번만 실행)
-- 요구사항 6.5 "A파트 답 C1~C6"(김재휘 안)대로 0~1 감점이 아니라 원값만 둔다.
-- 서버가 요청 때 route.yml 기준값으로 0~1 감점을 만든다. 기준(25m·300m 등)을 바꿔도 이 표는 다시 계산하지 않는다.
-- 실행 뒤 값을 채운다:  python3 scripts/build_edge_stat.py
-- 계단·덮개·길 종류는 road_edge의 칸(steps, covered, highway)을 그대로 쓴다.
CREATE TABLE IF NOT EXISTS edge_stat (
    edge_id        BIGINT      NOT NULL COMMENT 'road_edge.id',
    light_n        SMALLINT    NOT NULL COMMENT '길에서 30m 안 가로등·보안등 수 (같은 종류 10m 안 중복은 하나)',
    light_cover_m  DOUBLE      NOT NULL COMMENT '길이 중 등 30m 안에 드는 길이(m). 5m 간격 점으로 잼',
    cctv_n         SMALLINT    NOT NULL COMMENT '길에서 50m 안 CCTV 위치 수 (10m 안 중복은 하나)',
    cctv_cover_m   DOUBLE      NOT NULL COMMENT '길이 중 CCTV 50m 안에 드는 길이(m)',
    help_m         DOUBLE      NULL COMMENT '길 가운데에서 가장 가까운 도움 지점(경찰·편의점·지킴이집·비상벨)까지 m. 2km 넘으면 NULL',
    guard_m        DOUBLE      NULL COMMENT '가장 가까운 아동안전지킴이집까지 m. 2km 넘으면 NULL',
    rest_m         DOUBLE      NULL COMMENT '가장 가까운 쉼 곳(무더위·한파쉼터·공중화장실)까지 m. 2km 넘으면 NULL',
    help_n_1km     SMALLINT    NOT NULL COMMENT '길 가운데 반경 1km 안 도움 지점 수 (지역 기준 A5)',
    guard_n_1km    SMALLINT    NOT NULL COMMENT '반경 1km 안 지킴이집 수',
    rest_n_1km     SMALLINT    NOT NULL COMMENT '반경 1km 안 쉼 곳 수',
    xing_signal    TINYINT     NOT NULL COMMENT '이 길에서 건너는 횡단보도 중 신호 있음',
    xing_nosignal  TINYINT     NOT NULL COMMENT '신호 없음',
    xing_unknown   TINYINT     NOT NULL COMMENT '신호 모름',
    curb           TINYINT     NULL COMMENT '건너는 횡단보도 중 가장 나쁜 보도턱: 1 안 낮춤, 0 낮춤, NULL 모름',
    footbridge_ok  TINYINT     NULL COMMENT '육교 위 길이면 휠체어 가능 1, 불가 0. 육교가 아니거나 모르면 NULL',
    road_class     VARCHAR(30) NULL COMMENT '큰 차도 판정용 길 종류. 차도는 자기 종류, 차도 옆 인도는 15m 안 가장 큰 차도 종류',
    sgg_cd         VARCHAR(10) NULL COMMENT '길 시작점이 있는 시군구 (SGIS 코드, admin_area)',
    dong_cd        VARCHAR(10) NULL COMMENT '길 시작점이 있는 행정동 (SGIS 코드)',
    data_gap       SMALLINT    NULL COMMENT '데이터 부족 비트 (1 밝기, 2 CCTV, 4 도움, 8 지킴이집, 16 쉼 곳, 32 횡단 신호, 64 보도턱). NULL = 아직 판정 안 함',
    computed_at    DATETIME    NOT NULL,
    PRIMARY KEY (edge_id),
    KEY idx_edge_stat_dong (dong_cd)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '길별 감점 원값';
