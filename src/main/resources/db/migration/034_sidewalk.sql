-- 인도(보도) 데이터를 넣는다 (2026-11-13, 진행표 j36, 현장 비교·교수님 피드백).
-- OSM에는 골목의 95% 넘게 인도 태그가 없고 폭은 0.6%뿐이라, 골목 피하기(w21)가 인도 있는 골목까지 피하고
-- '좁은 보도'를 데이터로 설명하지 못했다. 국토지리정보원 자료 두 가지를 넣고 우리 길(road_edge)마다 옆 인도를 붙인다.
--
-- sidewalk: 인도 원본
--   ngii_line  연속수치지형도 인도(보도) 선 N3L_A0033320 (1:5,000, 2023 제작). 폭 WIDT·재질 QUAL·종류 KIND·자전거도로 BYYN
--   ngii_area  국가기본도 보도경계면 TN_FTPTH_BNDRY (2020 측량, 2026-07 배포). 칸은 관리 정보뿐이라 폭은 넓이 ÷ 길이로 계산
--   둘 다 EPSG:5179를 경위도로 바꿔 넣고, 광주 경계 안만 둔다.
-- edge_sidewalk: 우리 길마다 옆 인도 (scripts/load_sidewalk.py가 계산)
--   길을 5m마다 나눠, 그 점에서 길과 나란한(30° 안) 인도 선·면 테두리가 큰 길 25m·그 밖 12m 안에 있으면
--   길을 그린 방향(from_node → to_node) 왼쪽·오른쪽 어느 쪽인지 센다. 점의 절반 넘게 있으면 그쪽에 인도가 있다고 본다.
--   폭·재질은 붙은 인도 선 값을 길이로 가중해 고르고(폭은 중간값, 재질은 가장 긴 것), 선에 없으면 면에서 계산한 폭.
--   계산한 길 종류: 차가 다니는 길(trunk~tertiary와 연결로, unclassified·residential·living_street·service). 인도·계단 등 걷는 길은 뺌.
-- 다시 돌리면 표가 이미 있어 그대로 두고, 내용은 load_sidewalk.py --write가 지우고 다시 넣는다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sidewalk (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    source     VARCHAR(20)  NOT NULL COMMENT 'ngii_line(연속수치지형도 인도 선), ngii_area(국가기본도 보도경계면)',
    source_id  VARCHAR(40)  NOT NULL COMMENT '원본 번호 (UFID 또는 NF_ID)',
    kind       VARCHAR(10)  NULL     COMMENT 'sidewalk 인도, cycleway 자전거도로 (선만, 면은 NULL)',
    width_m    DECIMAL(5,2) NULL     COMMENT '폭 m. 선은 원본 WIDT(0이면 NULL), 면은 넓이 ÷ (둘레/2)로 계산한 대략값',
    material   VARCHAR(20)  NULL     COMMENT 'asphalt 아스콘, concrete 콘크리트, block 블록, unpaved 비포장, asphalt_block 아스콘/블록, other 기타 (선만)',
    bicycle    TINYINT      NULL     COMMENT '자전거도로 함께 있음 1, 없음 0 (선만)',
    made_on    VARCHAR(10)  NULL     COMMENT '원본 갱신일 (면) 또는 제작 연도',
    geom       GEOMETRY     NOT NULL SRID 0 COMMENT '경위도 (선 LINESTRING, 면 POLYGON)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sidewalk_source (source, source_id),
    SPATIAL INDEX sx_sidewalk_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '인도(보도) 원본 (국토지리정보원)';

CREATE TABLE IF NOT EXISTS edge_sidewalk (
    edge_id     BIGINT       NOT NULL COMMENT 'road_edge.id',
    left_ratio  DECIMAL(4,2) NOT NULL COMMENT '길을 그린 방향 왼쪽에 인도가 있는 점의 비율 0~1',
    right_ratio DECIMAL(4,2) NOT NULL COMMENT '오른쪽에 인도가 있는 점의 비율 0~1',
    side        VARCHAR(5)   NOT NULL COMMENT 'both, left, right, no (비율 0.5 넘는 쪽). OSM sidewalk 태그와 같은 말',
    width_m     DECIMAL(5,2) NULL     COMMENT '붙은 인도 폭 중간값 m (모르면 NULL)',
    material    VARCHAR(20)  NULL     COMMENT '붙은 인도 재질 중 가장 긴 것 (sidewalk.material과 같은 값)',
    source      VARCHAR(20)  NULL     COMMENT '판단에 쓴 자료: ngii_line, ngii_area, both',
    PRIMARY KEY (edge_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '우리 길마다 옆 인도 (034, scripts/load_sidewalk.py)';
