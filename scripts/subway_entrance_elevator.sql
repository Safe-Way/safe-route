-- 지하철 출입구마다 지상 엘리베이터가 있는지 (결정 51, 2026-11-05 확인)
-- 근거 A: 공공데이터포털 「국가철도공단_광주교통공사_엘리베이터_20250630」 (data.go.kr 15041385). 엘리베이터 한 대가 한 줄,
--         출입구번호 칸이 '내부'면 대합실↔승강장이라 뺐다.
-- 근거 B: 광주교통공사 사이버스테이션 역정보 (grtc.co.kr/cyber/subway/layerC?subwayid=01~20)의 출구별 E/L 표시
-- 두 출구 '사이'에 있는 엘리베이터는 두 출구 모두 1로 둔다.
-- 확인된 역은 나머지 출구를 0으로, 확인하지 못한 곳(문화전당 5번)은 NULL(확인 안 됨)로 둔다.
-- 넣은 뒤: python3 scripts/build_poi.py --only subway,subway_entrance
SET NAMES utf8mb4;

UPDATE subway_entrance SET elevator = NULL, elevator_source = NULL;

-- (역 이름, 출구, 엘리베이터 1 있음·0 없음, 메모). 학교 DB는 임시 표 권한이 없어 SELECT를 묶어 쓴다
UPDATE subway_entrance e
JOIN subway_station s ON s.station_id = e.station_id
JOIN (
    SELECT '평동' AS station, '1' AS exit_no, 1 AS elevator, '지상역: 출구가 곧 1층 대합실' AS note
    UNION ALL SELECT '평동', '2', 1, '지상역: 출구가 곧 1층 대합실'
    UNION ALL SELECT '평동', '3', 1, '지상역: 출구가 곧 1층 대합실'
    UNION ALL SELECT '도산', '1', 1, '1·2번 사이'
    UNION ALL SELECT '도산', '2', 1, '1·2번 사이'
    UNION ALL SELECT '도산', '3', 1, NULL
    UNION ALL SELECT '도산', '4', 1, '출구 뒤편'
    UNION ALL SELECT '광주송정', '1', 0, NULL
    UNION ALL SELECT '광주송정', '2', 1, NULL
    UNION ALL SELECT '광주송정', '3', 1, '3·4번 사이'
    UNION ALL SELECT '광주송정', '4', 1, '3·4번 사이'
    UNION ALL SELECT '광주송정', '5', 0, NULL
    UNION ALL SELECT '송정공원', '1', 1, '1·2번 사이'
    UNION ALL SELECT '송정공원', '2', 1, '1·2번 사이'
    UNION ALL SELECT '송정공원', '3', 1, '3·4번 사이'
    UNION ALL SELECT '송정공원', '4', 1, '3·4번 사이'
    UNION ALL SELECT '공항', '1', 1, '1·2번 사이'
    UNION ALL SELECT '공항', '2', 1, '1·2번 사이'
    UNION ALL SELECT '공항', '3', 1, '3·4번 사이'
    UNION ALL SELECT '공항', '4', 1, '3·4번 사이'
    UNION ALL SELECT '공항', '5', 1, '5·6번 사이'
    UNION ALL SELECT '공항', '6', 1, '5·6번 사이'
    UNION ALL SELECT '김대중컨벤션센터', '1', 1, '1·2번 사이'
    UNION ALL SELECT '김대중컨벤션센터', '2', 1, '1·2번 사이'
    UNION ALL SELECT '김대중컨벤션센터', '3', 1, '3·4번 사이'
    UNION ALL SELECT '김대중컨벤션센터', '4', 1, '3·4번 사이'
    UNION ALL SELECT '김대중컨벤션센터', '5', 1, '5·6번 사이'
    UNION ALL SELECT '김대중컨벤션센터', '6', 1, '5·6번 사이'
    UNION ALL SELECT '상무', '1', 1, NULL
    UNION ALL SELECT '상무', '2', 0, NULL
    UNION ALL SELECT '상무', '3', 0, NULL
    UNION ALL SELECT '상무', '4', 1, NULL
    UNION ALL SELECT '상무', '5', 0, NULL
    UNION ALL SELECT '상무', '6', 0, NULL
    UNION ALL SELECT '운천', '1', 1, NULL
    UNION ALL SELECT '운천', '2', 0, NULL
    UNION ALL SELECT '운천', '3', 0, NULL
    UNION ALL SELECT '운천', '4', 0, NULL
    UNION ALL SELECT '쌍촌', '1', 0, NULL
    UNION ALL SELECT '쌍촌', '2', 1, NULL
    UNION ALL SELECT '쌍촌', '3', 0, NULL
    UNION ALL SELECT '쌍촌', '4', 0, NULL
    UNION ALL SELECT '화정', '1', 0, NULL
    UNION ALL SELECT '화정', '2', 1, NULL
    UNION ALL SELECT '화정', '3', 0, NULL
    UNION ALL SELECT '화정', '4', 0, NULL
    UNION ALL SELECT '농성', '1', 1, '썬큰광장'
    UNION ALL SELECT '농성', '2', 0, NULL
    UNION ALL SELECT '농성', '3', 0, NULL
    UNION ALL SELECT '농성', '4', 1, '4·5번 사이'
    UNION ALL SELECT '농성', '5', 1, '4·5번 사이'
    UNION ALL SELECT '농성', '6', 0, NULL
    UNION ALL SELECT '농성', '7', 1, NULL
    UNION ALL SELECT '돌고개', '1', 0, NULL
    UNION ALL SELECT '돌고개', '2', 1, NULL
    UNION ALL SELECT '돌고개', '3', 0, NULL
    UNION ALL SELECT '돌고개', '4', 0, NULL
    UNION ALL SELECT '양동시장', '1', 0, NULL
    UNION ALL SELECT '양동시장', '2', 0, NULL
    UNION ALL SELECT '양동시장', '3', 0, '휠체어리프트만 있음'
    UNION ALL SELECT '양동시장', '4', 0, NULL
    UNION ALL SELECT '금남로5가', '1', 0, NULL
    UNION ALL SELECT '금남로5가', '2', 0, NULL
    UNION ALL SELECT '금남로5가', '3', 1, NULL
    UNION ALL SELECT '금남로5가', '4', 0, NULL
    UNION ALL SELECT '금남로5가', '5', 0, NULL
    UNION ALL SELECT '금남로5가', '6', 0, NULL
    UNION ALL SELECT '금남로4가', '1', 1, '1·2번 사이'
    UNION ALL SELECT '금남로4가', '2', 1, '1·2번 사이'
    UNION ALL SELECT '금남로4가', '3', 0, NULL
    UNION ALL SELECT '금남로4가', '4', 0, NULL
    UNION ALL SELECT '문화전당', '1', 0, NULL
    UNION ALL SELECT '문화전당', '2', 1, NULL
    UNION ALL SELECT '문화전당', '3', 1, '3·4번 사이(5·18민주광장~방문자센터), 현장 확인 2026-11-05'
    UNION ALL SELECT '문화전당', '4', 1, '3·4번 사이(5·18민주광장~방문자센터), 현장 확인 2026-11-05'
    UNION ALL SELECT '문화전당', '6', 0, NULL
    UNION ALL SELECT '남광주', '1', 0, NULL
    UNION ALL SELECT '남광주', '2', 0, NULL
    UNION ALL SELECT '남광주', '3', 1, NULL
    UNION ALL SELECT '남광주', '4', 1, NULL
    UNION ALL SELECT '남광주', '5', 0, NULL
    UNION ALL SELECT '남광주', '6', 0, NULL
    UNION ALL SELECT '학동증심사입구', '1', 1, NULL
    UNION ALL SELECT '학동증심사입구', '2', 0, NULL
    UNION ALL SELECT '학동증심사입구', '3', 0, NULL
    UNION ALL SELECT '학동증심사입구', '4', 0, NULL
    UNION ALL SELECT '소태', '1', 1, NULL
    UNION ALL SELECT '소태', '2', 0, NULL
    UNION ALL SELECT '소태', '3', 0, NULL
    UNION ALL SELECT '소태', '4', 0, NULL
) t ON t.station = s.name AND t.exit_no = e.exit_no
SET e.elevator = t.elevator,
    e.elevator_source = CONCAT('광주교통공사 엘리베이터 현황(data.go.kr 15041385)·역정보 E/L', IFNULL(CONCAT(' · ', t.note), ''));
-- 문화전당 5번: 역정보(B)에는 E/L 표시가 있지만 공공데이터(A)에는 없어 확인 안 됨으로 둔다
-- 문화전당 3·4번: A·B에는 없지만 팀이 현장에서 3·4번 사이 지상 엘리베이터를 확인함 (2026-11-05)
-- 녹동: 역 안내도·공공데이터에 출구 엘리베이터가 없다. 공사 편의시설 표(grtc.co.kr disabledFacilityCnt)의 2대는
--       출구 쪽이 아니라고 보고 팀이 '없음'으로 정했다 (2026-11-05)
UPDATE subway_entrance e
JOIN subway_station s ON s.station_id = e.station_id
SET e.elevator = 0,
    e.elevator_source = '팀 확인 2026-11-05: 출구 쪽 지상 엘리베이터 없음 (역 안내도·data.go.kr 15041385에 없음)'
WHERE s.name = '녹동';
