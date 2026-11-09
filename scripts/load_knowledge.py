# -*- coding: utf-8 -*-
"""비서 지식 조각(docs/assistant/knowledge.json)을 학교 DB assistant_knowledge(031)에 넣고 찾기 시험을 한다 (R4, A파트).

1. 검사: id가 겹치지 않는지, 칸이 다 있는지, body가 300자 이하인지.
2. 031 표가 없으면 만든다(CREATE TABLE IF NOT EXISTS), 조각을 넣거나 고친다, json에 없는 id는 지운다.
3. 찾기 시험: tests의 질문마다 서버가 할 찾기(MATCH(keywords, body) AGAINST(질문))로 위 3개를 보고,
   기대한 조각이 그 안에 있는지 본다.

실행: python3 scripts/load_knowledge.py            # 넣고 시험
      python3 scripts/load_knowledge.py --dry-run  # DB에 안 넣고 1번 검사만
"""
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs", "assistant", "knowledge.json")
DDL = os.path.join(ROOT, "src", "main", "resources", "db", "migration", "031_assistant_knowledge.sql")
MAX_BODY = 300
TOP = 3


def read_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def q(v):
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def check(data):
    errors, seen = [], set()
    for it in data["items"]:
        for k in ("id", "topic", "keywords", "body", "source"):
            if not it.get(k):
                errors.append(f"{it.get('id')}번: {k} 칸이 비었어요")
        if it["id"] in seen:
            errors.append(f"{it['id']}번이 두 번 있어요")
        seen.add(it["id"])
        if len(it["body"]) > MAX_BODY:
            errors.append(f"{it['id']}번 body가 {len(it['body'])}자예요 ({MAX_BODY}자 이하)")
    for t in data["tests"]:
        if t["expect"] not in seen:
            errors.append(f"시험 '{t['q']}'이 기대하는 {t['expect']}번이 없어요")
    return errors


def main():
    data = json.load(open(SRC, encoding="utf-8"))
    errors = check(data)
    lens = [len(it["body"]) for it in data["items"]]
    print(f"조각 {len(data['items'])}개 · body {min(lens)}~{max(lens)}자(평균 {sum(lens) / len(lens):.0f}자) · 시험 {len(data['tests'])}개")
    if errors:
        print("\n".join("  " + e for e in errors))
        sys.exit(1)
    if "--dry-run" in sys.argv:
        return

    env = read_env()
    m = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    fd, cnf = tempfile.mkstemp(suffix=".cnf")
    try:
        with os.fdopen(fd, "w") as f:
            f.write("[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n"
                    % (env["DB_USERNAME"], env["DB_PASSWORD"], m.group(1), m.group(2) or "3306"))
        os.chmod(cnf, 0o600)
        cmd = ["mysql", "--defaults-extra-file=" + cnf, "--default-character-set=utf8mb4", m.group(3)]

        today = date.today().isoformat()
        ids = ",".join(str(it["id"]) for it in data["items"])
        sql = open(DDL, encoding="utf-8").read() + "\n"
        sql += "INSERT INTO assistant_knowledge (id, topic, keywords, body, source, updated_at) VALUES\n"
        sql += ",\n".join(f"({it['id']},{q(it['topic'])},{q(it['keywords'])},{q(it['body'])},{q(it['source'])},"
                          f"{q(today)})" for it in data["items"])
        sql += ("\nON DUPLICATE KEY UPDATE updated_at = IF(topic <=> VALUES(topic) AND keywords <=> VALUES(keywords)"
                " AND body <=> VALUES(body) AND source <=> VALUES(source), updated_at, VALUES(updated_at)),"
                " topic = VALUES(topic), keywords = VALUES(keywords), body = VALUES(body), source = VALUES(source);\n")
        sql += f"DELETE FROM assistant_knowledge WHERE id NOT IN ({ids});\n"
        r = subprocess.run(cmd, input=sql, capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit("DB에 넣다가 실패: " + r.stderr.strip())
        n = subprocess.run(cmd + ["-B", "-N", "-e", "SELECT COUNT(*) FROM assistant_knowledge"],
                           capture_output=True, text=True).stdout.strip()
        print(f"DB assistant_knowledge {n}줄")

        hit = 0
        for t in data["tests"]:
            r = subprocess.run(cmd + ["-B", "-N", "-e",
                                      f"SELECT id, topic, ROUND(MATCH(keywords, body) AGAINST({q(t['q'])}), 2) s "
                                      f"FROM assistant_knowledge WHERE MATCH(keywords, body) AGAINST({q(t['q'])}) "
                                      f"ORDER BY s DESC LIMIT {TOP}"], capture_output=True, text=True)
            rows = [line.split("\t") for line in r.stdout.splitlines() if line]
            ok = any(int(row[0]) == t["expect"] for row in rows)
            hit += ok
            got = ", ".join(f"{row[0]} {row[1]}({row[2]})" for row in rows) or "없음"
            print(f"  {'통과' if ok else '실패'} \"{t['q']}\" 기대 {t['expect']}번 → {got}")
        print(f"찾기 시험 {hit}/{len(data['tests'])} (위 {TOP}개 안에 기대한 조각)")
        sys.exit(0 if hit == len(data["tests"]) else 1)
    finally:
        if os.path.exists(cnf):
            os.remove(cnf)


if __name__ == "__main__":
    main()
