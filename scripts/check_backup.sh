#!/usr/bin/env bash
# 백업 복구 시험 (한 달에 한 번 권장. 2026-11-24). 백업 파일을 빈 MySQL 8.0(도커)에 넣어 보고 표마다 줄 수를 센다.
# "백업이 있다"가 아니라 "되살릴 수 있다"를 확인하는 것. 학교 DB에는 쓰지 않는다.
#
#   bash scripts/check_backup.sh ~/safe-route-backup/daily/<파일>.sql.gz            # 넣어 보고 줄 수만
#   bash scripts/check_backup.sh ~/safe-route-backup/daily/<파일>.sql.gz --compare  # 지금 학교 DB 줄 수와 나란히 (.env, 읽기만)
#
# 백업 뒤에 늘어난 줄이 있으면 지금 DB 쪽이 더 많은 것이 정상이다. 백업 쪽이 0인데 지금 DB가 많으면 백업이 빠진 것.
set -euo pipefail
FILE="${1:?백업 파일(.sql.gz)을 넣어 주세요}"
COMPARE="${2:-}"
NAME="sr-restore-check-$$"
PORT="${CHECK_PORT:-33099}"

gzip -t "$FILE" || { echo "gzip 이 깨졌어요: $FILE" >&2; exit 1; }
gzip -dc "$FILE" | tail -n 3 | grep -q 'Dump completed' || { echo "끝까지 쓰이지 않은 백업이에요: $FILE" >&2; exit 1; }

docker run -d --rm --name "$NAME" -e MYSQL_ROOT_PASSWORD=restore-check -e MYSQL_DATABASE=restore \
  -p "127.0.0.1:$PORT:3306" mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do docker exec "$NAME" mysqladmin ping -uroot -prestore-check >/dev/null 2>&1 && break; sleep 2; done
sleep 5

start=$(date +%s)
gzip -dc "$FILE" | docker exec -i "$NAME" mysql -uroot -prestore-check --default-character-set=utf8mb4 restore 2> >(grep -v Warning >&2)
echo "넣기 끝 ($(( $(date +%s) - start ))초)"

counts() {  # $1 = 실행할 mysql 명령 앞부분
  "$@" -N -B -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY 1" \
    | while read -r t; do printf '%s\t%s\n' "$t" "$("$@" -N -B -e "SELECT COUNT(*) FROM \`$t\`" < /dev/null)"; done
}
restored="$(counts docker exec "$NAME" mysql -uroot -prestore-check restore 2>/dev/null)"

if [ "$COMPARE" = "--compare" ]; then
  get() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
  url="$(get DB_URL)"
  [[ "$url" =~ ^jdbc:mysql://([^:/?]+):([0-9]+)/([^?]+) ]] || { echo ".env 의 DB_URL 을 읽지 못했어요" >&2; exit 1; }
  live() { MYSQL_PWD="$(get DB_PASSWORD)" mysql -h "${BASH_REMATCH[1]}" -P "${BASH_REMATCH[2]}" -u "$(get DB_USERNAME)" "${BASH_REMATCH[3]}" "$@" 2>/dev/null; }
  printf '%-28s %10s %10s\n' "표" "백업" "지금 DB"
  echo "$restored" | while IFS=$'\t' read -r t n; do
    now="$(live -N -B -e "SELECT COUNT(*) FROM \`$t\`" < /dev/null || echo '?')"
    mark=""; [ "$n" = 0 ] && [ "$now" != 0 ] && [ "$now" != '?' ] && mark="  ← 백업이 비었음"
    printf '%-28s %10s %10s%s\n' "$t" "$n" "$now" "$mark"
  done
else
  printf '%-28s %10s\n' "표" "백업 줄 수"
  echo "$restored" | while IFS=$'\t' read -r t n; do printf '%-28s %10s\n' "$t" "$n"; done
fi
echo "표 $(echo "$restored" | grep -c .)개를 되살렸어요. 시험용 DB는 지워요"
