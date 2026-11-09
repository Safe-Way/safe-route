# -*- coding: utf-8 -*-
"""비서 토큰 줄이기(R2·R3) 데이터 점검 (A파트, 2026-11-09). 제미나이·서버를 부르지 않아 비용이 없다.

1. 규칙 조각(src/main/resources/assistant/rules/*.txt)이 옛 SYSTEM(GeminiBrain.java)의 규칙 줄을
   하나도 빠뜨리거나 바꾸지 않고 딱 한 번씩 담는지. 제목 줄([..])과 새로 넣은 줄은 따로 알려 준다.
   R3 뒤로 GeminiBrain에는 SYSTEM이 없어서, 없으면 SYSTEM이 마지막으로 있던 커밋(SYSTEM_REF)에서 읽는다.
   조각 이름 → 파일 이름은 RulePieces.java의 FILES를 따른다(한글 파일 이름은 리눅스 빌드에서 빠져서 영어로 둠).
2. 키워드 표(tool_groups.json)를 ToolPicker와 같은 방식(띄어쓰기 지운 말에 키워드가 들어 있으면 걸림, 여러 묶음은 합침,
   이어지는 말이면 바로 전 도구·확인 대기 도구가 든 묶음을 더함, 아무것도 안 걸리면 7개 다)으로 흉내 내서
   eval_questions.json 35개와 tool_picker_cases.json의 말마다 필요한 도구가 다 걸리는지, 몇 개·몇 자를 보내는지.

실행: python3 scripts/check_assistant_split.py   (빠진 것이 있으면 끝 상태가 1)
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources", "assistant")
JAVA = os.path.join(ROOT, "src", "main", "java", "com", "saferoute", "assistant")
BRAIN = os.path.join(JAVA, "GeminiBrain.java")
PIECES = os.path.join(JAVA, "RulePieces.java")
# SYSTEM이 GeminiBrain에 마지막으로 있던 develop 커밋 (#128 머지, 2026-11-09)
SYSTEM_REF = "3c940f3"
# RulePieces.java 를 못 읽을 때 쓰는 같은 표 (조각 이름 → rules/파일 이름.txt)
RULE_FILES = {"공통": "common", "길": "route", "가게": "store", "가까운 곳": "nearby", "내 정보": "me", "저장": "save"}
ALL = ["search_place", "recommend_trip", "find_nearby", "get_my_info", "update_my_info", "set_map_layers",
       "report_problem"]
# ToolPickerEvalTests 와 같음: 앞 질문이 확인을 기다리게 한 저장 도구
EVAL_PENDING = {8: "set_map_layers", 15: "report_problem"}


def system_lines():
    pattern = r'static final String SYSTEM = """\n(.*?)""";'
    m = re.search(pattern, open(BRAIN, encoding="utf-8").read(), re.S)
    where = "GeminiBrain.java"
    if not m:
        rel = os.path.relpath(BRAIN, ROOT)
        old = subprocess.run(["git", "-C", ROOT, "show", f"{SYSTEM_REF}:{rel}"], capture_output=True, text=True).stdout
        m = re.search(pattern, old, re.S)
        where = f"{SYSTEM_REF} GeminiBrain.java"
    if not m:
        sys.exit("옛 SYSTEM 글을 찾지 못했어요")
    return [l.strip() for l in m.group(1).split("\n") if l.strip()], where


def rule_files():
    """RulePieces.FILES (조각 이름 → 파일 이름). 못 읽으면 RULE_FILES"""
    if os.path.exists(PIECES):
        m = re.search(r"FILES\s*=\s*Map\.of\((.*?)\);", open(PIECES, encoding="utf-8").read(), re.S)
        if m:
            parts = re.findall(r'COMMON|"([^"]*)"', m.group(1))
            vals = ["공통" if p == "" else p for p in parts]
            if len(vals) % 2 == 0:
                return dict(zip(vals[::2], vals[1::2]))
    return dict(RULE_FILES)


def rule_chunks():
    d = os.path.join(RES, "rules")
    out = {}
    for name, f in rule_files().items():
        path = os.path.join(d, f + ".txt")
        if not os.path.exists(path):
            sys.exit(f"규칙 조각 '{name}'의 파일 rules/{f}.txt 가 없어요")
        out[name] = [l.strip() for l in open(path, encoding="utf-8") if l.strip()]
    return out


def chars(lines):
    return sum(len(l) + 1 for l in lines)


def check_rules():
    sys_lines, where = system_lines()
    chunks = rule_chunks()
    rules = [l for l in sys_lines if not l.startswith("[")]
    seen = {}
    for name, lines in chunks.items():
        for l in lines:
            if not l.startswith("["):
                seen.setdefault(l, []).append(name)
    missing = [l for l in rules if l not in seen]
    twice = {l: v for l, v in seen.items() if len(v) > 1}
    new = [l for l in seen if l not in rules]
    files = rule_files()
    print(f"[규칙 조각] 옛 SYSTEM({where}) {chars(sys_lines)}자·{len(rules)}줄 → 조각 {len(chunks)}개 "
          + ", ".join(f"{k}({files[k]}.txt) {chars(v)}자" for k, v in chunks.items()))
    print(f"  빠진 줄 {len(missing)} · 두 번 든 줄 {len(twice)} · 새로 넣은 줄 {len(new)}")
    for l in missing:
        print("  빠짐:", l)
    for l, v in twice.items():
        print("  두 번:", v, l)
    for l in new:
        print("  새 줄:", l)
    return chunks, not missing and not twice


def load_groups():
    return json.load(open(os.path.join(RES, "tool_groups.json"), encoding="utf-8"))


def pick(groups, text, after=(), pending=None):
    flat = (text or "").replace(" ", "")
    follow = set(after) | ({pending} if pending else set())
    hit = [g for g in groups if any(w in flat for w in g["words"]) or any(t in follow for t in g["tools"])]
    if not hit:
        return hit, list(ALL)
    names = {t for g in hit for t in g["tools"]}
    return hit, [t for t in ALL if t in names]


def check_picker(chunks):
    groups = load_groups()
    rows = []
    ev = json.load(open(os.path.join(ROOT, "docs", "assistant", "eval_questions.json"), encoding="utf-8"))
    for q in ev["questions"]:
        if q["text"].startswith("("):
            continue
        pend = EVAL_PENDING.get(q["id"]) if q.get("context") else None
        rows.append(("점검 %d" % q["id"], q["text"], q.get("expect_tools", []), q.get("last_tools", []), pend))
    cs = json.load(open(os.path.join(ROOT, "docs", "assistant", "tool_picker_cases.json"), encoding="utf-8"))
    for i, c in enumerate(cs["cases"], 1):
        rows.append(("말 %d" % i, c["text"], c["need"], c.get("after", []), c.get("pending")))

    bad, sent_tools, sent_rules, all7 = [], 0, 0, 0
    full_rules = sum(chars(v) for v in chunks.values())
    for tag, text, need, after, pend in rows:
        hit, tools = pick(groups, text, after, pend)
        names = ["공통"] + sorted({r for g in hit for r in g.get("rules", [])}) if hit else list(chunks)
        rc = sum(chars(chunks[n]) for n in set(names) if n in chunks)
        sent_tools += len(tools)
        sent_rules += rc
        all7 += len(tools) == len(ALL)
        lack = [t for t in need if t not in tools]
        if lack:
            bad.append(f"  {tag} \"{text}\": 필요 {need}, 고름 {tools} (묶음 {[g['group'] for g in hit] or '안 걸림'})")
    n = len(rows)
    print(f"[도구 고르기] 말 {n}개 (점검 질문 {len(ev['questions'])}개 중 오늘 제안 뺀 것 + 시험 말 {len(cs['cases'])}개)")
    print(f"  필요한 도구가 빠진 말 {len(bad)}개 · 7개 다 보낸 말 {all7}개 · 말당 도구 평균 {sent_tools / n:.1f}개(지금 7개)"
          f" · 규칙 평균 {sent_rules / n:.0f}자(조각 전부 {full_rules}자)")
    for b in bad:
        print(b)
    return not bad


def main():
    chunks, ok1 = check_rules()
    ok2 = check_picker(chunks)
    sys.exit(0 if ok1 and ok2 else 1)


if __name__ == "__main__":
    main()
