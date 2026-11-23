#!/usr/bin/env bash
# schema.sql(표 전체 그림)이 Flyway로 만든 DB와 같은지 본다 (2026-11-23).
# 두 DB의 표·칸(자료형·NULL·키)·인덱스·뷰 이름을 뽑아 비교하고, 다르면 차이를 보여 주고 1로 끝난다.
# 뷰의 칸 자료형은 만든 접속의 문자 설정에 따라 길이가 달라져서(varchar(120)↔184) 비교하지 않는다.
#   쓰는 법: MYSQL_PWD=<비밀번호> scripts/check_schema_sync.sh <호스트> <포트> <사용자> <Flyway DB> <schema.sql DB>
# CI(ci.yml)가 Flyway 적용 뒤에 돌린다. V 파일을 더했으면 schema.sql도 같이 고쳐야 통과한다.
set -euo pipefail
host=$1 port=$2 user=$3 flyway_db=$4 schema_db=$5

Q="SELECT CONCAT_WS('|', TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, EXTRA)
     FROM information_schema.COLUMNS c WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
      AND TABLE_NAME IN (SELECT TABLE_NAME FROM information_schema.TABLES
                          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE');
   SELECT CONCAT_WS('|', TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), NON_UNIQUE, INDEX_TYPE)
     FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
    GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE, INDEX_TYPE;
   SELECT CONCAT('VIEW|', TABLE_NAME) FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE();"

dump() { mysql -h "$host" -P "$port" -u "$user" -N "$1" -e "$Q" | LC_ALL=C sort; }

a=$(mktemp) b=$(mktemp)
trap 'rm -f "$a" "$b"' EXIT
dump "$flyway_db" > "$a"
dump "$schema_db" > "$b"
if diff "$a" "$b" > /dev/null; then
  echo "schema.sql 과 Flyway 결과가 같아요 ($(wc -l < "$a" | tr -d ' ')줄: 칸·인덱스·뷰)"
else
  echo "schema.sql 과 Flyway 결과가 달라요. '<' = Flyway(V 파일)에만, '>' = schema.sql 에만:"
  diff "$a" "$b" || true
  exit 1
fi
