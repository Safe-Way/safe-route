-- 같은 사람이 여러 번 신고했는지 알 수 있게 신고자 칸을 더한다 (2026-11-10, 결정 57, 진행표 j26·c17 ③).
-- 로그인이 없어 누가 올렸는지 모르므로, 같은 곳 신고가 한 사람인지 여러 사람인지 셀 수 없었다
-- (한 사람이 다른 폰·여러 번 올리면 '확인됨'이 됨, 앱 #108 서버 부탁).
--
-- reporter_hash: 서버(C)가 신고를 받을 때 게스트 번호에 서버 비밀값을 섞어 만든 해시(HMAC-SHA256, 16진수 64자).
--   원래 게스트 번호는 저장하지 않고, 비밀값 없이는 해시에서 번호를 되찾거나 다른 표와 이을 수 없다.
--   게스트 번호가 없는 신고·이 칸이 생기기 전 신고는 NULL(신고마다 다른 사람으로 셈).
--   비밀값은 .env·GitHub Secrets 에만 둔다(이름은 C파트가 정함). 바꾸면 그 전 신고와는 같은 사람으로 안 묶인다.
-- 뷰 road_report_active(032)를 다시 만든다: confirmed 는 '서로 다른 사람 2명 이상'(또는 팀 확인), reporter_count 칸 추가.
-- 칸은 비어도 되므로 지금 신고 저장(ReportRepository)은 그대로 동작한다. 다시 돌리면 ADD COLUMN 이 이미 있다고 실패한다.
-- 시험: scripts/check_report_active.py (이 파일의 뷰로 계산)
SET NAMES utf8mb4;

ALTER TABLE road_report
    ADD COLUMN reporter_hash CHAR(64) NULL
        COMMENT '같은 사람 판단용: HMAC-SHA256(서버 비밀값, 게스트 번호) 16진수. 원래 번호는 저장 안 함, 없으면 NULL'
        AFTER status,
    ADD KEY idx_road_report_reporter (reporter_hash);

CREATE OR REPLACE VIEW road_report_active AS
WITH RECURSIVE
base AS (
    -- @@BASE@@
    SELECT r.id, r.report_type, r.lat, r.lon, r.geom, r.edge_id, r.accuracy_m, r.memo, r.status, r.reporter_hash, r.created_at,
           DATE(r.created_at) AS d,
           (SELECT COUNT(*) FROM road_report_photo ph WHERE ph.report_id = r.id) AS photos
    FROM road_report r
    WHERE r.status <> 'hidden'
    -- @@END@@
),
spotted AS (
    SELECT b.*,
           (SELECT MIN(o.id) FROM base o
            WHERE o.report_type = b.report_type AND ST_Distance_Sphere(o.geom, b.geom) <= 30) AS spot_id,
           CASE b.report_type WHEN 'obstacle' THEN 1 WHEN 'accident' THEN 1 WHEN 'construction' THEN 7
                              WHEN 'curb' THEN 30 WHEN 'steep' THEN 30 WHEN 'broken' THEN 30 ELSE 7 END AS days
    FROM base b
),
spot_days AS (
    SELECT spot_id, report_type, days, d, ROW_NUMBER() OVER (PARTITION BY spot_id ORDER BY d) AS rn
    FROM (SELECT DISTINCT spot_id, report_type, days, d FROM spotted) x
),
periods AS (
    SELECT spot_id, report_type, days, rn, d AS starts_on, DATE_ADD(d, INTERVAL days - 1 DAY) AS ends_on
    FROM spot_days WHERE rn = 1
    UNION ALL
    SELECT n.spot_id, n.report_type, n.days, n.rn,
           CASE WHEN n.d > p.ends_on THEN n.d ELSE p.starts_on END,
           CASE WHEN n.d > p.ends_on THEN DATE_ADD(n.d, INTERVAL n.days - 1 DAY)
                WHEN n.report_type = 'construction' AND n.d = p.ends_on THEN DATE_ADD(p.ends_on, INTERVAL 1 DAY)
                ELSE p.ends_on END
    FROM periods p JOIN spot_days n ON n.spot_id = p.spot_id AND n.rn = p.rn + 1
),
latest AS (
    SELECT p.* FROM periods p
    WHERE p.rn = (SELECT MAX(q.rn) FROM spot_days q WHERE q.spot_id = p.spot_id)
)
SELECT l.spot_id,
       l.report_type,
       a.lat, a.lon, a.edge_id,
       l.starts_on, l.ends_on,
       COUNT(s.id)                                        AS report_count,
       COUNT(DISTINCT COALESCE(s.reporter_hash, CONCAT('#', s.id))) AS reporter_count,
       SUM(s.photos)                                      AS photo_count,
       MIN(s.accuracy_m)                                  AS best_accuracy_m,
       MIN(s.created_at)                                  AS first_at,
       MAX(s.created_at)                                  AS last_at,
       SUBSTRING_INDEX(GROUP_CONCAT(s.memo ORDER BY s.created_at DESC SEPARATOR '\n'), '\n', 1) AS last_memo,
       GROUP_CONCAT(DISTINCT s.edge_id ORDER BY s.edge_id) AS edge_ids,
       CASE WHEN COUNT(DISTINCT COALESCE(s.reporter_hash, CONCAT('#', s.id))) >= 2
                 OR SUM(s.status = 'confirmed') > 0 THEN 1 ELSE 0 END AS confirmed,
       CASE WHEN l.report_type IN ('obstacle', 'accident', 'construction') THEN 'adult,child,senior,wheelchair'
            WHEN l.report_type IN ('curb', 'steep', 'broken') THEN 'senior,wheelchair'
            ELSE '' END                                   AS avoid_modes,
       CASE WHEN l.report_type IN ('curb', 'steep', 'broken') THEN 'adult,child'
            WHEN l.report_type IN ('obstacle', 'accident', 'construction') THEN ''
            ELSE 'adult,child,senior,wheelchair' END      AS cost_modes
FROM latest l
JOIN spotted a ON a.id = l.spot_id
JOIN spotted s ON s.spot_id = l.spot_id AND s.d BETWEEN l.starts_on AND l.ends_on
WHERE l.ends_on >= CURDATE()   -- @@TODAY@@
GROUP BY l.spot_id, l.report_type, a.lat, a.lon, a.edge_id, l.starts_on, l.ends_on;
