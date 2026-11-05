-- 불편 접수 (FR-49, 결정 36·49): 길에서 급경사·공사·턱 같은 불편한 곳을 사진과 함께 받는다.
-- 노인·장애인 모드에서 앱이 유형·사진·지금 위치를 올리고, 서버가 이 표에 넣는다.
-- 로그인이 없어서 누가 올렸는지는 적지 않는다.
-- 처음에는 지도에 "신고된 곳"으로만 보이고, 경로 계산에는 쓰지 않는다(확인 방법을 정한 뒤 반영).
-- 사진은 road_report_photo에 따로 둔다.
--   서버는 도커로 배포돼서, 다시 배포하면 서버 안에 둔 파일이 사라진다.
--   지도 목록을 읽을 때 사진까지 읽지 않게 하려는 것도 있다.
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS road_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    report_type VARCHAR(20)  NOT NULL COMMENT '유형: steep 급경사, construction 공사, curb 턱, broken 보도 파손, obstacle 막는 물건, other 기타',
    mode        VARCHAR(20)  NULL     COMMENT '올린 모드 (senior, wheelchair)',
    memo        VARCHAR(300) NULL     COMMENT '한 줄 설명 (넣지 않아도 됨)',
    lon         DOUBLE       NOT NULL,
    lat         DOUBLE       NOT NULL,
    geom        POINT        NOT NULL SRID 0 COMMENT 'POINT(경도, 위도), 다른 표와 같음',
    accuracy_m  SMALLINT     NULL     COMMENT '올릴 때 GPS 정확도 (m)',
    edge_id     BIGINT       NULL     COMMENT '가장 가까운 길 road_edge.id (나중에 경로에 반영할 때 씀, 비어도 됨)',
    status      VARCHAR(10)  NOT NULL DEFAULT 'open' COMMENT 'open 지도에 보임, confirmed 확인됨, hidden 숨김(잘못 올림·얼굴·차량 번호)',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '올린 시각 (학교 DB는 한국 시각)',
    PRIMARY KEY (id),
    KEY idx_road_report_status (status),
    SPATIAL INDEX sx_road_report_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '불편 접수 (FR-49)';

-- 신고 사진. 신고를 지우면 사진도 같이 지워진다
CREATE TABLE IF NOT EXISTS road_report_photo (
    report_id    BIGINT      NOT NULL,
    seq          TINYINT     NOT NULL DEFAULT 1 COMMENT '사진 순서 (1부터)',
    content_type VARCHAR(30) NOT NULL COMMENT 'image/jpeg 등',
    size_bytes   INT         NOT NULL,
    data         MEDIUMBLOB  NOT NULL COMMENT '사진 원본 (칸은 16MB까지, 앱에서 줄여 1장 1MB 안팎을 권장)',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (report_id, seq),
    CONSTRAINT fk_road_report_photo FOREIGN KEY (report_id) REFERENCES road_report (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '불편 접수 사진 (FR-49)';
