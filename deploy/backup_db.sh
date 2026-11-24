#!/usr/bin/env bash
# 학교 DB 자동 백업 (2026-11-24). 서버의 ~/safe-route/ 에서 cron으로 돈다. deploy.yml이 deploy.sh와 같이 올린다.
#
#   bash ~/safe-route/backup_db.sh daily     # 사람 기록 표만 (작고 빠름). 매일 03:30
#   bash ~/safe-route/backup_db.sh full      # 모든 표 (공공데이터 포함, 큼). 매주 일요일 04:10
#   bash ~/safe-route/backup_db.sh install   # 위 두 줄을 내 crontab에 넣는다 (처음 한 번, 다시 해도 같은 줄은 하나만)
#   bash ~/safe-route/backup_db.sh status    # 마지막 백업들과 기록 보기
#
# - DB 접속 정보는 ~/safe-route/app.env(DB_URL·DB_USERNAME·DB_PASSWORD)에서 읽는다. 비밀번호는 명령줄에 안 보이게 환경변수로 넘긴다
# - mysqldump는 mysql:8.0 도커 이미지의 것을 쓴다(서버에 mysql 클라이언트를 깔지 않음). 학교 DB도 8.0
# - 우리 계정 권한(SELECT·SHOW VIEW 등)으로 되는 옵션만 쓴다: 트리거·테이블스페이스·GTID 정보는 빼고,
#   --single-transaction 으로 표를 잠그지 않고 한 시점의 내용을 뜬다(공용 DB라 다른 팀을 막지 않게)
# - 결과: ~/safe-route/backup/daily/ (14개 보관), ~/safe-route/backup/full/ (2개 보관). 폴더·파일은 본인만 읽는다(개인정보)
# - 끝까지 떴는지 확인한다: mysqldump가 마지막에 쓰는 "Dump completed" 줄과 gzip 검사. 실패하면 임시 파일을 지우고 1로 끝난다
# - 서버 밖 복사는 노트북에서 scripts/pull_backup.sh, 복구 시험은 scripts/check_backup.sh (docs/backup.md)
set -euo pipefail

HOME_DIR="${SAFE_ROUTE_HOME:-$HOME/safe-route}"
ENV_FILE="${ENV_FILE:-$HOME_DIR/app.env}"
BACKUP_DIR="${BACKUP_DIR:-$HOME_DIR/backup}"
IMAGE="${MYSQL_IMAGE:-mysql:8.0}"
KEEP_DAILY="${KEEP_DAILY:-14}"
KEEP_FULL="${KEEP_FULL:-2}"
LOG="$BACKUP_DIR/backup.log"

# 사람이 만든 기록 (서버가 쓰는 표). 공공데이터 표는 스크립트로 다시 넣을 수 있어 full 에만 들어간다.
# 아직 없는 표(guest_token·api_call_count·flyway_schema_history 는 #303·#315·#316 배포 뒤 생김)는 건너뛴다
DAILY_TABLES=(
  user_profile walk_profile walk_speed_sample trip_log user_map_layer guardian_share guest_token
  road_report road_report_photo assistant_unmet odsay_call_count api_call_count flyway_schema_history
  map_layer road_event data_source
)

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

# app.env 에서 KEY=값 하나를 읽는다 (따옴표 없이 한 줄씩 쓴 파일)
env_value() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

read_db() {
  [ -r "$ENV_FILE" ] || { echo "환경변수 파일이 없어요: $ENV_FILE" >&2; exit 1; }
  local url; url="$(env_value DB_URL)"
  # jdbc:mysql://주소:포트/DB이름?옵션
  if ! [[ "$url" =~ ^jdbc:mysql://([^:/?]+):([0-9]+)/([^?]+) ]]; then
    echo "DB_URL 모양을 읽지 못했어요 (jdbc:mysql://주소:포트/DB이름)" >&2
    exit 1
  fi
  DB_HOST="${BASH_REMATCH[1]}"; DB_PORT="${BASH_REMATCH[2]}"; DB_NAME="${BASH_REMATCH[3]}"
  DB_USER="$(env_value DB_USERNAME)"
  DB_PASS="$(env_value DB_PASSWORD)"
}

# 도커 안의 mysql 클라이언트. 비밀번호는 -e MYSQL_PWD 로(명령줄·프로세스 목록에 안 보임)
mysql_in_docker() {
  MYSQL_PWD="$DB_PASS" docker run --rm -i -e MYSQL_PWD "$IMAGE" "$@"
}

existing_tables() {
  local list; list="$(printf "'%s'," "${DAILY_TABLES[@]}")"
  mysql_in_docker mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -B "$DB_NAME" \
    -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (${list%,})"
}

backup() {
  local kind="$1" keep tables=()
  read_db
  mkdir -p "$BACKUP_DIR/$kind"; chmod 700 "$BACKUP_DIR" "$BACKUP_DIR/$kind"
  # 같은 때 두 번 돌지 않게 (mkdir 은 원자적)
  LOCK="$BACKUP_DIR/.lock"
  if ! mkdir "$LOCK" 2>/dev/null; then
    log "[$kind] 다른 백업이 돌고 있어 건너뜀 ($LOCK 이 남아 있으면 지운 뒤 다시)"
    exit 1
  fi
  trap 'rm -rf "$LOCK"; rm -f "${tmp:-}"' EXIT

  if [ "$kind" = daily ]; then
    keep="$KEEP_DAILY"
    while IFS= read -r t; do [ -n "$t" ] && tables+=("$t"); done < <(existing_tables)
    [ "${#tables[@]}" -gt 0 ] || { log "[$kind] 실패: 백업할 표를 찾지 못함"; exit 1; }
  else
    keep="$KEEP_FULL"
  fi

  local name; name="safe-route-$kind-$(date '+%Y%m%d-%H%M').sql.gz"
  local out="$BACKUP_DIR/$kind/$name"
  tmp="$out.part"
  local start; start=$(date +%s)
  umask 077
  if ! mysql_in_docker mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
        --single-transaction --quick --no-tablespaces --skip-triggers --set-gtid-purged=OFF \
        --hex-blob --default-character-set=utf8mb4 "$DB_NAME" ${tables[@]+"${tables[@]}"} | gzip -6 > "$tmp"; then
    log "[$kind] 실패: mysqldump 오류 (위 메시지 확인)"
    exit 1
  fi
  # 끝까지 떴는지: gzip 이 온전하고, mysqldump 마지막 줄 "-- Dump completed" 가 있어야 한다
  if ! gzip -t "$tmp" || ! gzip -dc "$tmp" | tail -n 3 | grep -q 'Dump completed'; then
    log "[$kind] 실패: 백업 파일이 끝까지 쓰이지 않음"
    exit 1
  fi
  mv "$tmp" "$out"; tmp=""
  local size; size=$(du -h "$out" | cut -f1)
  log "[$kind] 완료: $name ($size, ${#tables[@]}개 표 지정(0이면 전체), $(( $(date +%s) - start ))초)"

  # 오래된 것 지우기: 최신 $keep 개만 남긴다
  ls -1t "$BACKUP_DIR/$kind"/*.sql.gz 2>/dev/null | tail -n +"$((keep + 1))" | while read -r old; do
    rm -f "$old"; log "[$kind] 오래된 백업 지움: $(basename "$old")"
  done
}

install_cron() {
  local me; me="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  local mark="# safe-route-backup"
  local lines="30 3 * * * bash $me daily >/dev/null 2>&1 $mark
10 4 * * 0 bash $me full >/dev/null 2>&1 $mark"
  { crontab -l 2>/dev/null | grep -v -- "$mark" || true; echo "$lines"; } | crontab -
  echo "crontab에 넣었어요 (서버 시간 기준, TZ=$(date +%Z)):"
  crontab -l | grep -- "$mark"
}

status() {
  for k in daily full; do
    echo "== $k"; ls -lht "$BACKUP_DIR/$k" 2>/dev/null | head -5 || echo "(없음)"
  done
  echo "== 최근 기록"; tail -n 10 "$LOG" 2>/dev/null || echo "(없음)"
}

mkdir -p "$BACKUP_DIR"; chmod 700 "$BACKUP_DIR"
case "${1:-}" in
  daily|full) backup "$1" ;;
  install) install_cron ;;
  status) status ;;
  *) echo "쓰는 법: bash $0 daily|full|install|status" >&2; exit 2 ;;
esac
