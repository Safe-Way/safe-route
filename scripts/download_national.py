"""전국 시설 데이터를 API에서 페이지 단위로 내려받는다.

- 결과: data/raw/national/<이름>/p00001.json ... (응답 원본 그대로)
- 이미 받은 페이지는 건너뛰므로 몇 번을 다시 실행해도 된다.
- 재난안전데이터(safetydata)는 키마다 하루 1000번 한도가 있다.
  오늘 쓴 횟수를 data/raw/national/_quota.json 에 적어 두고 한도 전에 멈춘다.
- 공공데이터포털(datagokr)은 한도를 넘으면 오류(코드 22)를 주므로, 그때 멈추고 다음에 이어 받는다.
- --wait 를 주면 한도에 걸린 데이터는 다음 날 0시 10분까지 기다렸다가 이어서 받는다.

실행: python3 scripts/download_national.py [--wait] [이름 ...]
"""
import datetime as dt
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "data", "raw", "national")
QUOTA_FILE = os.path.join(OUT, "_quota.json")
PAGE_SIZE = 1000
DAILY_LIMIT = 990  # 한도 1000에서 조금 여유를 둔다

SAFETYDATA = "https://www.safetydata.go.kr/V2/api/"
SAFEMAP = "https://www.safemap.go.kr/openapi2/"
DATA_GO_KR = "https://api.data.go.kr/openapi/"

# 이름, 주소, 키 이름("A|B"면 A가 없을 때 B), 출처 종류
DATASETS = [
    ("police", SAFETYDATA + "DSSP-IF-00080", "SAFETYDATA_POLICE_KEY", "safetydata"),
    ("security_light", SAFETYDATA + "DSSP-IF-00083", "SAFETYDATA_SECURITYLIGHT_KEY", "safetydata"),
    ("streetlight", SAFETYDATA + "DSSP-IF-00084", "SAFETYDATA_STREETLIGHT_KEY", "safetydata"),
    ("emergency_bell", SAFEMAP + "IF_0032", "SAFEMAP_KEY", "safemap"),
    ("convenience_store", SAFEMAP + "IF_0039", "SAFEMAP_KEY", "safemap"),
    ("child_safety_house", SAFEMAP + "IF_0055", "SAFEMAP_KEY", "safemap"),
    ("heat_shelter", SAFEMAP + "IF_0001", "SAFEMAP_KEY", "safemap"),
    # 한파쉼터: 생활안전지도(IF_0136)는 10,000건까지만 줘서 재난안전데이터로 바꿈
    ("cold_shelter_safetydata", SAFETYDATA + "DSSP-IF-10804", "SAFETYDATA_COLDSHELTER_KEY", "safetydata"),
    ("public_toilet", SAFEMAP + "IF_0132", "SAFEMAP_KEY", "safemap"),
    # 전국보안등정보표준데이터: 지자체가 직접 올려서 공통POI 보안등보다 촘촘함 (약 186만 건)
    ("security_light_std", DATA_GO_KR + "tn_pubr_public_scrty_lmp_api",
     "DATA_GO_KR_SECURITYLIGHT_KEY|DATA_GO_KR_KEY", "datagokr"),
    # 전국육교정보표준데이터: 휠체어 모드에서 엘리베이터·경사로 없는 육교를 빼려고 받음 (약 1,400건)
    ("footbridge", DATA_GO_KR + "tn_pubr_public_overpass_api",
     "DATA_GO_KR_FOOTBRIDGE_KEY|DATA_GO_KR_KEY", "datagokr"),
    # 전국횡단보도표준데이터: 보도턱 낮춤·점자블록·음향신호기·보행 신호 시간 (약 5만 9천 건)
    ("crosswalk", DATA_GO_KR + "tn_pubr_public_crosswalk_api",
     "DATA_GO_KR_CROSSWALK_KEY|DATA_GO_KR_KEY", "datagokr"),
]


def log(msg):
    print(dt.datetime.now().strftime("%m-%d %H:%M:%S"), msg, flush=True)


def read_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def load_quota():
    try:
        with open(QUOTA_FILE, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def save_quota(q):
    with open(QUOTA_FILE, "w", encoding="utf-8") as f:
        json.dump(q, f, ensure_ascii=False, indent=1)


def today():
    return dt.date.today().isoformat()


def used_today(q, key_name):
    return q.get(today(), {}).get(key_name, 0)


def add_use(q, key_name):
    day = q.setdefault(today(), {})
    day[key_name] = day.get(key_name, 0) + 1
    save_quota(q)


def fetch(base, key, page, source):
    params = {"serviceKey": key, "pageNo": page, "numOfRows": PAGE_SIZE}
    if source == "datagokr":
        params["type"] = "json"
    else:
        params["returnType"] = "JSON" if source == "safemap" else "json"
    url = base + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def parse(data, source):
    """(결과코드, 전체 건수, 항목 목록)"""
    code = data.get("header", {}).get("resultCode")
    if source in ("safemap", "datagokr"):
        body = data.get("body") or {}
        items = (body.get("items") or {}).get("item") or []
        if isinstance(items, dict):
            items = [items]
        return code, int(body.get("totalCount") or 0), items
    return code, int(data.get("totalCount") or 0), data.get("body") or []


def page_path(name, page):
    return os.path.join(OUT, name, "p%05d.json" % page)


def total_of(name):
    p = os.path.join(OUT, name, "_total.txt")
    return int(open(p).read()) if os.path.exists(p) else None


def download(name, base, key_env, source, env, q):
    """끝까지 받으면 True, 한도 때문에 멈추면 False"""
    os.makedirs(os.path.join(OUT, name), exist_ok=True)
    key = next((env[k] for k in key_env.split("|") if env.get(k)), None)
    if not key:
        log("%s: .env에 %s 가 없어 건너뜀" % (name, key_env))
        return True
    page = 1
    while True:
        total = total_of(name)
        if total is not None and page > (total + PAGE_SIZE - 1) // PAGE_SIZE:
            log("%s: 끝 (전체 %d건)" % (name, total))
            return True
        if os.path.exists(page_path(name, page)):
            page += 1
            continue
        if source == "safetydata" and used_today(q, key_env) >= DAILY_LIMIT:
            log("%s: 오늘 한도(%d번) 도달, %d쪽부터 다음에 이어 받음" % (name, DAILY_LIMIT, page))
            return False
        data = None
        for attempt in range(4):
            try:
                if source in ("safetydata", "datagokr"):
                    add_use(q, key_env)
                data = fetch(base, key, page, source)
                break
            except Exception as e:  # 네트워크 오류는 잠시 쉬고 다시
                log("%s p%d: 오류 %s, 다시 시도" % (name, page, e))
                time.sleep(5 * (attempt + 1))
        if data is None:
            log("%s p%d: 계속 실패해서 멈춤" % (name, page))
            return False
        code, total, items = parse(data, source)
        if code is None and source == "datagokr":
            # 공공데이터포털 오류는 OpenAPI_ServiceResponse 모양으로 온다
            err = (data.get("OpenAPI_ServiceResponse") or {}).get("cmmMsgHeader") or {}
            if err.get("returnReasonCode") == "22":
                log("%s: 오늘 호출 한도 도달, %d쪽부터 다음에 이어 받음" % (name, page))
            else:
                log("%s p%d: 응답 오류 %s" % (name, page, json.dumps(err, ensure_ascii=False)))
            return False
        if code not in ("00", "0"):
            log("%s p%d: 응답 오류 %s" % (name, page, json.dumps(data.get("header"), ensure_ascii=False)))
            return False
        if total_of(name) is None:
            with open(os.path.join(OUT, name, "_total.txt"), "w") as f:
                f.write(str(total))
            log("%s: 전체 %d건, %d쪽" % (name, total, (total + PAGE_SIZE - 1) // PAGE_SIZE))
        if not items:
            log("%s p%d: 빈 페이지, 끝으로 봄" % (name, page))
            return True
        tmp = page_path(name, page) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(items, f, ensure_ascii=False)
        os.replace(tmp, page_path(name, page))
        if page % 50 == 0:
            log("%s: %d쪽 받음" % (name, page))
        page += 1


def seconds_until_tomorrow():
    now = dt.datetime.now()
    nxt = dt.datetime.combine(now.date() + dt.timedelta(days=1), dt.time(0, 10))
    return (nxt - now).total_seconds()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    wait = "--wait" in sys.argv
    env = read_env()
    targets = [d for d in DATASETS if not args or d[0] in args]
    os.makedirs(OUT, exist_ok=True)
    while True:
        q = load_quota()
        pending = [d for d in targets if not download(*d, env, q)]
        if not pending:
            log("모두 끝")
            return
        if not wait:
            log("남은 것: %s (다음에 다시 실행)" % ", ".join(d[0] for d in pending))
            return
        targets = pending
        s = seconds_until_tomorrow()
        log("남은 것: %s. %.1f시간 기다렸다가 이어 받음" % (", ".join(d[0] for d in pending), s / 3600))
        time.sleep(s)


if __name__ == "__main__":
    main()
