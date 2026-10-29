#!/bin/zsh
# 한파쉼터를 다시 받아서, 끝까지 받았을 때만 DB에 넣는다.
# 오늘 받은 데이터는 _old_cold_shelter_<날짜> 폴더로 옮겨 두고, 실패하면 되돌린다.
# 실행: scripts/retry_cold_shelter.sh [기다릴 시각, 예: 2026-10-29T00:20]
cd "$(dirname "$0")/.." || exit 1
RAW=data/raw/national
LOG=$RAW/retry_cold_shelter.log

if [[ -n "$1" ]]; then
  target=$(date -j -f "%Y-%m-%dT%H:%M" "$1" +%s)
  now=$(date +%s)
  (( target > now )) && sleep $(( target - now ))
fi

backup=$RAW/_old_cold_shelter_$(date +%Y%m%d%H%M)
echo "$(date '+%m-%d %H:%M') 한파쉼터 다시 받기 시작 (이전 데이터: $backup)" >> $LOG
mv $RAW/cold_shelter $backup
python3 -u scripts/download_national.py cold_shelter >> $LOG 2>&1

total=$(cat $RAW/cold_shelter/_total.txt 2>/dev/null)
pages=$(ls $RAW/cold_shelter/p*.json 2>/dev/null | wc -l | tr -d ' ')
if [[ -z "$total" ]] || (( pages < (total + 999) / 1000 )); then
  echo "$(date '+%m-%d %H:%M') 다 받지 못해서 이전 데이터로 되돌림" >> $LOG
  rm -rf $RAW/cold_shelter && mv $backup $RAW/cold_shelter
  exit 1
fi

echo "$(date '+%m-%d %H:%M') 전체 ${total}건 받음, DB에 넣는 중" >> $LOG
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=import \
  -Dspring-boot.run.arguments=--types=cold_shelter 2>&1 | grep -E 'FacilityImporter|ERROR' >> $LOG
echo "$(date '+%m-%d %H:%M') 끝" >> $LOG
