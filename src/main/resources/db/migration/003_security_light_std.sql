-- 전국보안등정보표준데이터(공공데이터포털)를 security_light 표에 함께 넣기 위한 칸 변경. (2026-10-29, 한 번만 실행)
-- 실행 뒤 데이터를 넣는다:
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--types=security_light_std

ALTER TABLE security_light
    MODIFY install_year VARCHAR(10) AS (COALESCE(attrs ->> '$.installationYear', attrs ->> '$."설치연도"')) VIRTUAL COMMENT '설치 연도 (attrs에서 꺼낸 값)',
    MODIFY manager VARCHAR(100) AS (COALESCE(attrs ->> '$.institutionNm', attrs ->> '$."관리기관명"')) VIRTUAL COMMENT '관리 기관 (attrs에서 꺼낸 값)',
    ADD COLUMN manager_phone VARCHAR(30) AS (attrs ->> '$.phoneNumber') VIRTUAL COMMENT '관리 기관 전화 (attrs에서 꺼낸 값)' AFTER manager;
