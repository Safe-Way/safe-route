-- 위치를 믿을 수 없어 시설 표에서 뺀 줄을 지우지 않고 모아 두는 표.
-- 좌표가 한 점에 몰렸거나(지자체가 기관 좌표·기본값을 넣음) 좌표가 없었는데, 카카오 주소·장소 검색으로도 못 찾았거나
-- 번지 있는 주소가 없는 줄. 나중에 다른 자료·현장 확인으로 좌표를 찾으면 원래 표로 되돌린다.
-- 넣는 곳: fix_cctv_coords.py, fix_pile_coords.py, fill_missing_coords.py, load_toilet_std.py, restore_removed.py
CREATE TABLE IF NOT EXISTS facility_unlocated (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tbl        VARCHAR(30)  NOT NULL COMMENT '원래 시설 표 이름 (security_light, cctv 등)',
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL COMMENT '원래 표의 source_id (원본 번호가 없으면 줄 해시)',
    kind       VARCHAR(50)  NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    lon        DOUBLE       NULL     COMMENT '원본 좌표 (틀린 값 그대로, 없으면 NULL)',
    lat        DOUBLE       NULL,
    reason     VARCHAR(100) NOT NULL COMMENT '뺀 이유 (좌표 몰림·주소로 못 찾음, 좌표 없음·번지 없는 주소 등)',
    tried      VARCHAR(300) NULL     COMMENT '찾아본 검색어·힌트',
    attrs      JSON         NULL     COMMENT '원래 줄의 attrs 또는 원본 줄 전체',
    moved_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_unlocated (tbl, source, source_id),
    KEY idx_unlocated_reason (tbl, reason)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '위치를 못 찾아 시설 표에서 뺀 줄';
