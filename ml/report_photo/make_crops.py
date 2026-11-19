# -*- coding: utf-8 -*-
"""AI Hub 인도보행 영상 라벨(CVAT XML)로 신고 사진 분류 모델의 학습 사진을 만든다 (2026-11-19, A파트).

왜 만드나
  신고 사진을 LLM에 보여 주고 종류를 묻는 대신, 작은 이미지 분류 모델(MobileNetV3 등)로 먼저 판단하려고 한다.
  이 스크립트는 그 학습 데이터를 만든다. 모델은 신고를 막지 않고 확인용으로만 쓴다(결정 57 유지).

원본 (data/raw/aihub/ 아래에 받은 그대로 둔다. git에는 안 올림)
  - AI Hub '인도보행 영상'(dataSetSn 189, 2019, 테스트웍스). 샘플(2019-01-004), Bbox_N_new.zip, Surface_N.zip
  - 라벨은 CVAT XML 1.1: <image name=… width=… height=…> 안에 <box label=… xtl ytl xbr ybr> 또는
    <polygon label=… points="x,y;x,y;…">, 속성은 <attribute name=…>값</attribute>
  - 사진은 1920×1080 jpg
  - 받은 파일은 남이 만든 자료라 믿지 않는다: python3 -I 로 돌리고, zip 은 풀지 않고 안에서 바로 읽으며,
    XML 의 파일 이름은 이름 부분만 써서 결과 경로를 만든다(폴더 밖으로 못 나가게).

하는 일
  1. 라벨을 우리 신고 종류(ReportType 코드)로 바꾼다: CLASS_MAP. 'label:속성값' 이 'label' 보다 먼저.
     construction 공사 · broken 보도 파손 · obstacle 막는 물건 · vehicle 차량·오토바이 · none 관계없음.
     curb(턱)는 이 데이터에 없음. vehicle 은 신고 종류가 아니라 '막는 물건' 중 금방 치워지는 것을 가리려는 칸.
     IGNORE 에 적은 라벨(기둥·나무 등 신고인지 애매한 것)은 쓰지 않는다.
  2. 박스(폴리곤은 감싸는 사각형) 둘레를 CONTEXT 배 넓혀 정사각형으로 잘라 SIZE px 로 줄여 저장한다.
     실제 신고 사진은 물건만 찍히지 않고 길이 함께 찍히므로 주변을 넉넉히 넣는다.
  3. 신고 대상 라벨(construction·broken·obstacle·curb)이 하나도 없는 사진은 사진 전체(가운데 정사각형)를
     none 으로 한 장 넣는다. 멀리 지나가는 차만 있는 거리 사진은 '문제없는 길'이다.
  4. 같은 원본 사진에서 나온 조각은 모두 같은 묶음(train 80 · val 10 · test 10)에 들어가게
     사진 이름의 해시로 나눈다(같은 장면이 학습·시험에 섞이지 않게).
  결과: OUT/{train,val,test}/{종류}/*.jpg 와 OUT/manifest.csv (어느 원본의 어느 박스인지)

실행
  # 라벨 종류와 개수만 보기 (새 zip 을 받으면 먼저 이걸로 CLASS_MAP 을 맞춘다)
  python3 -I ml/report_photo/make_crops.py --list-labels ~/Downloads/2019-01-004
  # 잘라 저장
  python3 -I ml/report_photo/make_crops.py ~/Downloads/2019-01-004 data/raw/aihub/inbound/…/Surface_1.zip \\
      --out data/raw/aihub/crops
"""
import argparse
import collections
import csv
import hashlib
import io
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image

# 라벨 → 신고 종류 (ReportType 코드). 'label:속성값' 이 있으면 그쪽이 먼저.
CLASS_MAP = {
    # 공사
    "barricade": "construction",
    "caution_zone:repair_zone": "construction",
    # 보도 파손 (Surface 노면 라벨)
    "sidewalk:damaged": "broken",
    "braille_guide_blocks:damaged": "broken",
    # 막는 물건: 길 위에 놓여 지나가기 어렵게 하는 것
    "movable_signage": "obstacle",   # 입간판
    "potted_plant": "obstacle",
    "carrier": "obstacle",           # 짐수레·손수레
    "chair": "obstacle",
    "table": "obstacle",
    "kiosk": "obstacle",
    "bollard": "obstacle",           # 휠체어에는 실제로 막는 물건
    "scooter": "obstacle",           # 세워 둔 킥보드
    # 차량·오토바이: 인도를 막은 차는 '막는 물건' 신고로 들어오지만 금방 치워지므로 따로 나눈다.
    # 서버가 '막는 물건 + 사진이 vehicle' 이면 하루 대신 몇 시간만 막는 데 쓰려고 (사용자 요청 2026-11-19).
    # 막았는지 여부는 신고한 사람이 판단한다(신고했다는 것 자체). 모델은 '차·오토바이 사진인지'만 본다.
    "car": "vehicle",
    "truck": "vehicle",
    "bus": "vehicle",
    "motorcycle": "vehicle",
    # 관계없음: 신고 대상이 아닌 것
    "person": "none",
    "traffic_light": "none",
    "traffic_sign": "none",
}
# 신고인지 애매해서 학습에 안 쓰는 라벨 (--list-labels 에서 '안 씀'으로 보임)
IGNORE = {
    "pole", "tree_trunk", "fire_hydrant", "bench", "parking_meter", "power_controller",
    "traffic_light_controller", "stop", "bicycle", "stroller", "wheelchair", "dog", "cat",
}
CLASSES = ("construction", "broken", "obstacle", "vehicle", "curb", "none")
POSITIVE = {"construction", "broken", "obstacle", "curb"}

IMAGE_EXT = (".jpg", ".jpeg", ".png")
MAX_XML_BYTES = 1 << 30      # XML 하나 1GB 넘으면 이상한 파일로 보고 건너뜀
MAX_IMAGE_BYTES = 50 << 20   # 사진 하나 50MB 넘으면 건너뜀
SPLITS = (("train", 80), ("val", 90), ("test", 100))


def safe_stem(name):
    """XML/zip 안의 이름에서 결과 파일 이름으로 쓸 안전한 부분만 남긴다 (폴더·특수문자 제거)."""
    base = os.path.basename(name.replace("\\", "/"))
    stem = os.path.splitext(base)[0]
    stem = re.sub(r"[^0-9A-Za-z가-힣_.-]", "_", stem).strip("._")
    return stem[:120] or "img"


def zip_name(info):
    """zip 안 이름을 바로 읽는다. UTF-8 표시가 없으면 cp437 로 읽힌 걸 UTF-8 → CP949 순서로 되돌린다."""
    if info.flag_bits & 0x800:
        return info.filename
    raw = info.filename.encode("cp437", errors="replace")
    for enc in ("utf-8", "cp949"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return info.filename


class Source:
    """폴더 또는 zip 하나. XML 목록과 사진 이름(파일 이름만) → 읽는 법을 준다."""

    def __init__(self, path):
        self.path = path
        self.is_zip = os.path.isfile(path) and zipfile.is_zipfile(path)
        self.xmls = []        # (표시 이름, 열기 함수)
        self.images = {}      # 파일 이름(소문자) → 읽기 함수
        if self.is_zip:
            self.zf = zipfile.ZipFile(path)
            for info in self.zf.infolist():
                if info.is_dir():
                    continue
                name = zip_name(info)
                low = name.lower()
                if low.endswith(".xml"):
                    if info.file_size <= MAX_XML_BYTES:
                        self.xmls.append((name, (lambda i=info: self.zf.open(i))))
                elif low.endswith(IMAGE_EXT) and info.file_size <= MAX_IMAGE_BYTES:
                    self.images.setdefault(os.path.basename(low), (lambda i=info: self.zf.read(i)))
        elif os.path.isdir(path):
            for root, _dirs, files in os.walk(path):
                for f in sorted(files):
                    full = os.path.join(root, f)
                    low = f.lower()
                    if low.endswith(".xml"):
                        if os.path.getsize(full) <= MAX_XML_BYTES:
                            self.xmls.append((os.path.relpath(full, path), (lambda p=full: open(p, "rb"))))
                    elif low.endswith(IMAGE_EXT) and os.path.getsize(full) <= MAX_IMAGE_BYTES:
                        self.images.setdefault(low, (lambda p=full: open(p, "rb").read()))
        else:
            raise SystemExit("폴더나 zip 이 아닙니다: %s" % path)
        self.xmls.sort(key=lambda x: x[0])

    def read_image(self, name):
        fn = self.images.get(os.path.basename(name.replace("\\", "/")).lower())
        return fn() if fn else None


def iter_frames(fp):
    """CVAT XML 에서 사진 한 장씩: (이름, 가로, 세로, [(라벨 키 목록, (x1,y1,x2,y2), 가려짐)])."""
    for _ev, el in ET.iterparse(fp, events=("end",)):
        if el.tag != "image":
            continue
        name = el.get("name") or ""
        try:
            w = int(float(el.get("width") or 0))
            h = int(float(el.get("height") or 0))
        except ValueError:
            w = h = 0
        objs = []
        for ob in el:
            if ob.tag not in ("box", "polygon", "polyline"):
                continue
            label = (ob.get("label") or "").strip().lower()
            attrs = [(a.text or "").strip().lower() for a in ob.findall("attribute") if (a.text or "").strip()]
            keys = ["%s:%s" % (label, v) for v in attrs] + [label]
            try:
                if ob.tag == "box":
                    bb = tuple(float(ob.get(k)) for k in ("xtl", "ytl", "xbr", "ybr"))
                else:
                    pts = [tuple(float(v) for v in p.split(",")) for p in (ob.get("points") or "").split(";") if p]
                    xs = [p[0] for p in pts]
                    ys = [p[1] for p in pts]
                    bb = (min(xs), min(ys), max(xs), max(ys))
            except (TypeError, ValueError, IndexError):
                continue
            objs.append((keys, bb, ob.get("occluded") == "1"))
        yield name, w, h, objs
        el.clear()


def class_of(keys):
    """라벨 키 목록 → (신고 종류 또는 None, 맞은 키). 앞에 있는 'label:속성값' 이 먼저."""
    for k in keys:
        if k in CLASS_MAP:
            return CLASS_MAP[k], k
    return None, keys[-1]


def square_crop(bb, w, h, context):
    """박스를 context 배 넓힌 정사각형. 사진 밖으로 나가면 안쪽으로 밀고, 사진보다 크면 짧은 변에 맞춘다."""
    x1, y1, x2, y2 = bb
    side = max(x2 - x1, y2 - y1) * context
    side = min(side, w, h)
    cx = (x1 + x2) / 2.0
    cy = (y1 + y2) / 2.0
    left = min(max(cx - side / 2.0, 0), w - side)
    top = min(max(cy - side / 2.0, 0), h - side)
    return int(round(left)), int(round(top)), int(round(left + side)), int(round(top + side))


def split_of(key):
    """원본 사진 이름 해시로 train/val/test 를 정한다 (다시 돌려도 같음)."""
    n = int(hashlib.md5(key.encode("utf-8")).hexdigest(), 16) % 100
    for name, upto in SPLITS:
        if n < upto:
            return name
    return SPLITS[-1][0]


def list_labels(sources):
    cnt = collections.Counter()
    frames = found = 0
    for src in sources:
        for xml_name, opener in src.xmls:
            with opener() as fp:
                for name, _w, _h, objs in iter_frames(fp):
                    frames += 1
                    if src.read_image(name) is not None:
                        found += 1
                    for keys, _bb, _occ in objs:
                        for k in keys:
                            cnt[k] += 1
    print("사진 %d장 (사진 파일 찾음 %d장)" % (frames, found))
    print("%-40s %8s  %s" % ("라벨(:속성값)", "개수", "쓰임"))
    for k, n in cnt.most_common():
        cls = CLASS_MAP.get(k)
        use = cls if cls else ("안 씀" if k.split(":")[0] in IGNORE else "정해지지 않음")
        print("%-40s %8d  %s" % (k, n, use))
    return cnt


def make_crops(sources, out, size=224, context=1.6, min_box=48, per_image=3, max_per_class=0, skip_occluded=False):
    counts = collections.Counter()   # (split, class) → 장 수
    per_class = collections.Counter()
    skipped = collections.Counter()
    rows = []
    made_dirs = set()

    def save(img, crop, split, cls, fname):
        d = os.path.join(out, split, cls)
        if d not in made_dirs:
            os.makedirs(d, exist_ok=True)
            made_dirs.add(d)
        img.crop(crop).resize((size, size), Image.BILINEAR).save(os.path.join(d, fname), "JPEG", quality=90)
        counts[(split, cls)] += 1
        per_class[cls] += 1

    for src in sources:
        src_tag = safe_stem(os.path.basename(src.path.rstrip("/")))
        for xml_name, opener in src.xmls:
            with opener() as fp:
                for name, w, h, objs in iter_frames(fp):
                    picks = []
                    taken = collections.Counter()
                    has_positive = False
                    for keys, bb, occ in objs:
                        cls, key = class_of(keys)
                        if cls is None:
                            continue
                        if cls in POSITIVE:
                            has_positive = True
                        if skip_occluded and occ:
                            skipped["가려짐"] += 1
                            continue
                        if max(bb[2] - bb[0], bb[3] - bb[1]) < min_box:
                            skipped["작은 박스"] += 1
                            continue
                        if taken[cls] >= per_image:
                            skipped["한 사진 %d개 넘음" % per_image] += 1
                            continue
                        if max_per_class and per_class[cls] + taken[cls] >= max_per_class:
                            skipped["종류별 상한"] += 1
                            continue
                        taken[cls] += 1
                        picks.append((cls, key, bb))
                    whole = not has_positive and (not max_per_class or per_class["none"] < max_per_class)
                    if not picks and not whole:
                        continue
                    data = src.read_image(name)
                    if data is None:
                        skipped["사진 파일 없음"] += 1
                        continue
                    try:
                        img = Image.open(io.BytesIO(data))
                        img = img.convert("RGB")
                    except (OSError, Image.DecompressionBombError) as e:
                        skipped["사진 못 읽음"] += 1
                        print("  사진 못 읽음 %s: %s" % (name, e), file=sys.stderr)
                        continue
                    W, H = img.size
                    if w and h and (w, h) != (W, H):
                        # 라벨 크기와 실제 사진 크기가 다르면 비율로 맞춘다
                        sx, sy = W / float(w), H / float(h)
                        picks = [(c, k, (b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy)) for c, k, b in picks]
                    stem = safe_stem(name)
                    split = split_of(src_tag + "/" + stem)
                    for i, (cls, key, bb) in enumerate(picks):
                        crop = square_crop(bb, W, H, context)
                        fname = "%s_%s_%d.jpg" % (src_tag, stem, i)
                        save(img, crop, split, cls, fname)
                        rows.append((split, cls, key, src_tag, xml_name, name) + crop + (fname,))
                    if whole:
                        side = min(W, H)
                        crop = ((W - side) // 2, (H - side) // 2, (W - side) // 2 + side, (H - side) // 2 + side)
                        fname = "%s_%s_whole.jpg" % (src_tag, stem)
                        save(img, crop, split, "none", fname)
                        rows.append((split, "none", "(사진 전체)", src_tag, xml_name, name) + crop + (fname,))

    with open(os.path.join(out, "manifest.csv"), "w", newline="", encoding="utf-8") as f:
        wr = csv.writer(f)
        wr.writerow(["split", "class", "label", "source", "xml", "image", "x1", "y1", "x2", "y2", "file"])
        wr.writerows(rows)
    return counts, skipped


def main(argv=None):
    ap = argparse.ArgumentParser(description="AI Hub 인도보행 영상 라벨 → 신고 사진 분류 학습 사진")
    ap.add_argument("sources", nargs="+", help="샘플 폴더 또는 Bbox/Surface zip")
    ap.add_argument("--out", help="결과 폴더 (예: data/raw/aihub/crops)")
    ap.add_argument("--list-labels", action="store_true", help="라벨 종류와 개수만 보여 주고 끝냄")
    ap.add_argument("--size", type=int, default=224)
    ap.add_argument("--context", type=float, default=1.6, help="박스 둘레를 몇 배로 넓혀 자를지")
    ap.add_argument("--min-box", type=int, default=48, help="이보다 작은 박스(px)는 안 씀")
    ap.add_argument("--per-image", type=int, default=3, help="한 사진에서 종류별 최대 몇 장")
    ap.add_argument("--max-per-class", type=int, default=0, help="종류별 최대 장 수 (0 = 제한 없음)")
    ap.add_argument("--skip-occluded", action="store_true", help="가려진 물체는 안 씀")
    ap.add_argument("--overwrite", action="store_true", help="결과 폴더의 train/val/test 를 지우고 다시 만듦")
    a = ap.parse_args(argv)

    sources = [Source(os.path.expanduser(p)) for p in a.sources]
    for s in sources:
        print("원본 %s: XML %d개, 사진 %d장" % (s.path, len(s.xmls), len(s.images)))
    if a.list_labels:
        list_labels(sources)
        return 0
    if not a.out:
        ap.error("--out 이 필요합니다")
    if os.path.exists(os.path.join(a.out, "manifest.csv")):
        if not a.overwrite:
            raise SystemExit("%s 에 이미 결과가 있습니다. 다시 만들려면 --overwrite" % a.out)
        for sp, _ in SPLITS:
            shutil.rmtree(os.path.join(a.out, sp), ignore_errors=True)
    os.makedirs(a.out, exist_ok=True)

    counts, skipped = make_crops(sources, a.out, a.size, a.context, a.min_box, a.per_image,
                                 a.max_per_class, a.skip_occluded)
    print("\n%-14s %7s %7s %7s %7s" % ("종류", "train", "val", "test", "합계"))
    for cls in CLASSES:
        n = [counts[(sp, cls)] for sp, _ in SPLITS]
        print("%-14s %7d %7d %7d %7d" % (cls, n[0], n[1], n[2], sum(n)))
    if skipped:
        print("건너뜀: " + ", ".join("%s %d" % kv for kv in skipped.most_common()))
    print("결과: %s" % os.path.join(a.out, "manifest.csv"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
