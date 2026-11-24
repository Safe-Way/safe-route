#!/usr/bin/env bash
# 학교 서버의 최신 DB 백업을 노트북으로 가져온다 (서버 밖 복사, 주 1회 권장. 2026-11-24).
# 서버가 하나라 서버·디스크가 망가지면 서버 안 백업도 같이 사라지므로, 일주일에 한 번은 밖에 둔다.
#
#   SR_SSH=<계정>@<서버 주소> SR_SSH_PORT=<포트> bash scripts/pull_backup.sh
#
# - 접속 정보는 저장소에 적지 않는다(저장소 공개). 실행할 때 환경변수로 넣는다. 비밀번호는 한 번 묻는다
# - 최신 daily 하나와 최신 full 하나를 ~/safe-route-backup/ 에 받는다(저장소 밖, 본인만 읽음). daily 8개·full 4개만 남긴다
# - 개인정보가 들어 있으므로 이 폴더를 클라우드 동기화 폴더나 저장소 안에 두지 않는다
set -euo pipefail
: "${SR_SSH:?SR_SSH=<계정>@<서버 주소> 를 넣어 주세요}"
PORT="${SR_SSH_PORT:-22}"
DEST="${BACKUP_LOCAL_DIR:-$HOME/safe-route-backup}"
mkdir -p "$DEST/daily" "$DEST/full"; chmod 700 "$DEST"

# ssh 한 번으로 두 파일을 tar 로 묶어 받는다 (비밀번호 한 번)
ssh -p "$PORT" "$SR_SSH" 'cd ~/safe-route/backup && d=$(ls -1t daily/*.sql.gz 2>/dev/null | head -1) && f=$(ls -1t full/*.sql.gz 2>/dev/null | head -1); [ -n "$d$f" ] || { echo "서버에 백업이 없어요" >&2; exit 1; }; tar cf - $d $f' \
  | tar xf - -C "$DEST"
chmod -R go-rwx "$DEST"

for k in daily full; do
  keep=8; [ "$k" = full ] && keep=4
  ls -1t "$DEST/$k"/*.sql.gz 2>/dev/null | tail -n +"$((keep + 1))" | while read -r old; do rm -f "$old"; done
done
echo "받았어요: $DEST"
ls -lht "$DEST"/daily "$DEST"/full | head -12
for f in "$DEST"/daily/*.sql.gz "$DEST"/full/*.sql.gz; do
  [ -e "$f" ] && { gzip -t "$f" && gzip -dc "$f" | tail -n 3 | grep -q 'Dump completed' && echo "온전함: $(basename "$f")" || echo "깨짐: $(basename "$f")"; }
done
