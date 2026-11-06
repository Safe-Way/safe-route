"""비서 시험 질문 35개(docs/assistant/eval_questions.json)를 비서 창구에 보내고 답을 점검한다 (A파트 매일 점검).

- 시연 게스트(scripts/seed_demo.py)로 보낸다: A = …de01 휠체어, B = …de02 어르신, C = …de03 임시 휠체어.
  new는 매번 새 무작위 번호(처음 쓰는 사람).
- 점검: expect의 말이 답(say·ask)에 모두 들어 있는지, must_not의 말이 하나도 없는지.
  서버가 응답에 tools(부른 도구 이름 목록)를 넣어 주면 expect_tools 순서도 본다(없으면 건너뜀).
- "context": "N번 바로 뒤"인 질문은 같은 대화로 이어 보낸다(서버가 conversationId를 돌려주면 그대로 씀).
- 날씨·시각(weather, time)은 서버가 시험용으로 받아 주면 쓴다(body의 test 칸). 안 받으면 지금 날씨로 답한다.
- 앱처럼 휴대폰의 자주 가는 곳(favorites)을 같이 보낸다. 집 좌표는 서버에 없고 휴대폰에만 있어서, 안 보내면 "집에 가자"를 못 한다.
  A의 집은 seed_demo.py의 HOME_A와 같은 꾸민 값, B의 집도 꾸민 값(충장동 둘레)이다.
- 질문에 at([위도, 경도])이 있으면 그 질문만 그 자리에서 보낸다(예: 문화전당에서 집으로 갈 때).
- writes가 true인 질문(설정 바꾸기 확인, 신고 확인)은 학교 DB에 진짜로 저장되므로 --write를 줄 때만 보낸다.
- 결과는 표로 찍고 data/raw/eval/assistant_<날짜시각>.json에 남긴다. 실패한 것만 다시 보려면 --only 3,7

실행: python3 scripts/eval_assistant.py [--base https://safe-route.java21.net] [--only 1,2,3] [--write]
비서 창구(POST /api/assistant)가 아직 없으면 404로 알려 주고 멈춘다.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUESTIONS = os.path.join(ROOT, "docs", "assistant", "eval_questions.json")
OUT_DIR = os.path.join(ROOT, "data", "raw", "eval")
GUESTS = {"A": "00000000-0000-4000-8000-00000000de01", "B": "00000000-0000-4000-8000-00000000de02",
          "C": "00000000-0000-4000-8000-00000000de03"}
WHERE = {"A": (35.1480, 126.9260), "B": (35.1460, 126.9210), "C": (35.1480, 126.9260), "new": (35.1466, 126.9204)}
# 휴대폰에 저장한 자주 가는 곳 (꾸민 값). A의 집 = seed_demo.py HOME_A
FAVORITES = {"A": [{"name": "집", "lat": 35.148, "lon": 126.926}],
             "B": [{"name": "집", "lat": 35.150, "lon": 126.915}],
             "C": [{"name": "집", "lat": 35.148, "lon": 126.926}]}


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def post(base, body):
    req = urllib.request.Request(base.rstrip("/") + "/api/assistant", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except ValueError:
            return e.code, {}


def check(q, status, ans):
    text = " ".join(str(ans.get(k) or "") for k in ("say", "ask"))
    miss = [w for w in q.get("expect", []) if w not in text]
    bad = [w for w in q.get("must_not", []) if w in text]
    tools_ok = None
    if isinstance(ans.get("tools"), list) and q.get("expect_tools") is not None:
        tools_ok = [t for t in ans["tools"] if t in q["expect_tools"]] == q["expect_tools"]
    ok = status == 200 and not miss and not bad and tools_ok is not False
    return ok, miss, bad, tools_ok, text


def main():
    base = arg("--base", "https://safe-route.java21.net")
    only = {int(x) for x in arg("--only", "").split(",") if x}
    write = "--write" in sys.argv
    qs = json.load(open(QUESTIONS, encoding="utf-8"))["questions"]
    results, convo, new_guest = [], {}, str(uuid.uuid4())
    for q in qs:
        if only and q["id"] not in only:
            continue
        if q.get("writes") and not write:
            print(f"{q['id']:>2} 건너뜀 [{q['guest']}] {q['text'][:28]:<28} → 학교 DB에 저장돼서 --write를 줄 때만")
            continue
        who = q["guest"]
        guest = GUESTS.get(who) or new_guest
        lat, lon = q.get("at") or WHERE.get(who, WHERE["new"])
        body = {"guestId": guest, "text": q["text"], "lat": lat, "lon": lon,
                "now": datetime.now().isoformat(timespec="minutes"), "favorites": FAVORITES.get(who, []),
                "test": {"weather": q.get("weather"), "time": q.get("time"), "ai": q.get("ai")}}
        if q.get("context") and who in convo:
            body["conversationId"] = convo[who]
        status, ans = post(base, body)
        if status == 404 and not results:
            sys.exit(f"비서 창구가 아직 없어요 ({base}/api/assistant → 404). C파트가 만든 뒤 다시 돌려 주세요.")
        if ans.get("conversationId"):
            convo[who] = ans["conversationId"]
        ok, miss, bad, tools_ok, text = check(q, status, ans)
        results.append({"id": q["id"], "ok": ok, "status": status, "missing": miss, "forbidden": bad,
                        "tools_ok": tools_ok, "answer": text[:300], "question": q["text"]})
        mark = "통과" if ok else "실패"
        why = "" if ok else f"  빠진 말 {miss}" * bool(miss) + f"  없어야 할 말 {bad}" * bool(bad) + \
            ("  도구 순서 다름" if tools_ok is False else "") + (f"  HTTP {status}" if status != 200 else "")
        print(f"{q['id']:>2} {mark} [{who}] {q['text'][:28]:<28} → {text[:50]}{why}")
    passed = sum(r["ok"] for r in results)
    print(f"\n통과 {passed}/{len(results)}")
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"assistant_{datetime.now():%Y%m%d%H%M}.json")
    json.dump({"base": base, "at": datetime.now().isoformat(timespec="seconds"), "passed": passed,
               "total": len(results), "results": results}, open(path, "w", encoding="utf-8"), ensure_ascii=False,
              indent=1)
    print("기록:", os.path.relpath(path, ROOT))
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
