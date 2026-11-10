-- 지금 유효한 신고 지점 (2026-11-10 결정 57, 진행표 j26·w11·c17·m24).
-- 말로 한 신고(via voice)와 앱 신고 버튼은 같은 road_report 표로 들어온다. 엔진(길 피하기)·서버(말·알림)·앱(지도 표시)이
-- 기간을 따로 계산하면 서로 어긋나므로, 같은 곳 묶기와 기간 계산을 이 뷰 하나로 한다. 읽기만 하므로 신고 저장에는 영향이 없다.
--
-- 규칙 (결정 57)
--   - 같은 곳 = 같은 종류 + 30m 안. 숨긴 신고(status hidden)는 세지 않는다.
--     묶음 번호(spot_id) = 같은 종류·30m 안 신고 중 가장 먼저 들어온 신고 번호.
--   - 1건이어도 유효하다(피하되 막지는 않음 — 막을지는 엔진이 우회 한도로 정함).
--   - 기간(첫 신고 날이 1일째): 막는 물건·사고 1일, 공사 7일, 턱·급경사·보도 파손 30일, 기타 7일.
--   - 공사만: 마지막 날(ends_on)에 같은 곳 신고가 또 오면 하루 연장(늘어난 마지막 날에 또 오면 또 하루).
--     1~6일째 신고는 기간은 그대로, 건수만 늘린다.
--   - 모든 종류: 기간이 끝난 다음 날 이후 신고가 오면 그날부터 새 기간.
--   - 누구에게: avoid_modes 모드는 피하기, cost_modes 모드는 조금 돌아가게(비용만).
--     막는 물건·사고·공사 = 모두 피하기 · 턱·급경사·보도 파손 = 휠체어·어르신 피하기, 어른·어린이 비용 · 기타 = 모두 비용
--   - confirmed: 이번 기간 신고가 2건 이상이거나 팀이 확인(status confirmed)한 신고가 있으면 1.
-- 날짜는 학교 DB 시각(한국 시각) 기준 CURDATE(). 한 날에 여러 건이 와도 연장은 하루만.
-- 시험: scripts/check_report_active.py (꾸민 신고로 날짜별 결과 확인, DB에 신고를 넣지 않음)
SET NAMES utf8mb4;

CREATE OR REPLACE VIEW road_report_active AS
WITH RECURSIVE
base AS (
    -- @@BASE@@
    SELECT r.id, r.report_type, r.lat, r.lon, r.geom, r.edge_id, r.accuracy_m, r.memo, r.status, r.created_at,
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
       SUM(s.photos)                                      AS photo_count,
       MIN(s.accuracy_m)                                  AS best_accuracy_m,
       MIN(s.created_at)                                  AS first_at,
       MAX(s.created_at)                                  AS last_at,
       SUBSTRING_INDEX(GROUP_CONCAT(s.memo ORDER BY s.created_at DESC SEPARATOR '\n'), '\n', 1) AS last_memo,
       GROUP_CONCAT(DISTINCT s.edge_id ORDER BY s.edge_id) AS edge_ids,
       CASE WHEN COUNT(s.id) >= 2 OR SUM(s.status = 'confirmed') > 0 THEN 1 ELSE 0 END AS confirmed,
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
