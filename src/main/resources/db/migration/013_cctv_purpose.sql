-- CCTV 설치 목적 분류 칸. 원문 목적(kind)은 그대로 두고, 묶은 분류와 그 근거를 따로 둔다.
-- 나중에 다른 자료나 현장 확인으로 채울 때는 purpose_group을 고치고 purpose_from = '직접 입력'으로 둔다
-- (scripts/classify_cctv.py는 '직접 입력'을 덮어쓰지 않는다).
-- purpose_group: 방범(생활방범·차량방범), 어린이(어린이보호), 다목적, 교통(교통단속·교통정보수집),
--                쓰레기(쓰레기단속), 시설·재난(시설물관리·재난재해), 기타, 모름
-- 길 계산(edge_stat)·데이터 부족 판정·poi는 교통·쓰레기·시설·재난을 빼고 센다 (2026-11-02 결정. 목적 모름은 셈)
ALTER TABLE cctv
    ADD COLUMN purpose_group VARCHAR(10) NOT NULL DEFAULT '모름' COMMENT '설치 목적 분류 (위 목록)',
    ADD COLUMN purpose_from  VARCHAR(20) NULL COMMENT '분류 근거: 원본 / 10m 안 행안부 파일 / 직접 입력',
    ADD KEY idx_cctv_purpose (purpose_group);
