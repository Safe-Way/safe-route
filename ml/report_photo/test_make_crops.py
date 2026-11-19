# -*- coding: utf-8 -*-
"""make_crops.py 테스트. 가짜 CVAT XML 과 색칠한 사진으로 돌린다 (AI Hub 원본 없이).

실행: .venv/bin/python -I -m unittest discover -s ml/report_photo -v
"""
import csv
import os
import shutil
import sys
import tempfile
import types
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_crops as mc  # noqa: E402

from PIL import Image  # noqa: E402

XML = """<?xml version="1.0" encoding="utf-8"?>
<annotations>
  <version>1.1</version>
  <image id="0" name="frame_a.jpg" width="400" height="200">
    <box label="barricade" occluded="0" xtl="10" ytl="20" xbr="110" ybr="120" z_order="0"></box>
    <box label="car" occluded="1" xtl="200" ytl="50" xbr="300" ybr="150" z_order="0"></box>
    <box label="bollard" occluded="0" xtl="0" ytl="0" xbr="10" ybr="10" z_order="0"></box>
    <box label="pole" occluded="0" xtl="300" ytl="0" xbr="390" ybr="190" z_order="0"></box>
  </image>
  <image id="1" name="frame_b.jpg" width="400" height="200">
    <polygon label="sidewalk" occluded="0" points="50,60;150,60;150,180;50,180" z_order="0">
      <attribute name="attribute">damaged</attribute>
    </polygon>
  </image>
  <image id="2" name="frame_c.jpg" width="400" height="200">
    <box label="person" occluded="0" xtl="100" ytl="20" xbr="180" ybr="190" z_order="0"></box>
  </image>
  <image id="3" name="../../escape.jpg" width="400" height="200">
    <box label="potted_plant" occluded="0" xtl="100" ytl="20" xbr="180" ybr="190" z_order="0"></box>
  </image>
  <image id="4" name="missing.jpg" width="400" height="200">
    <box label="barricade" occluded="0" xtl="100" ytl="20" xbr="180" ybr="190" z_order="0"></box>
  </image>
</annotations>
"""


def jpg_bytes(color, size=(400, 200)):
    import io
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "JPEG")
    return buf.getvalue()


class UnitTest(unittest.TestCase):
    def test_class_of_prefers_attribute(self):
        self.assertEqual(mc.class_of(["sidewalk:damaged", "sidewalk"]), ("broken", "sidewalk:damaged"))
        self.assertEqual(mc.class_of(["sidewalk"]), (None, "sidewalk"))
        self.assertEqual(mc.class_of(["barricade"]), ("construction", "barricade"))

    def test_square_crop_stays_inside(self):
        # 가장자리 박스: 안쪽으로 밀려서 사진 안에 있어야 함
        x1, y1, x2, y2 = mc.square_crop((0, 0, 10, 10), 400, 200, 1.6)
        self.assertEqual((x1, y1), (0, 0))
        self.assertEqual(x2 - x1, y2 - y1)
        # 사진보다 큰 박스: 짧은 변(200)에 맞춤
        x1, y1, x2, y2 = mc.square_crop((0, 0, 400, 200), 400, 200, 1.6)
        self.assertEqual((x2 - x1, y2 - y1), (200, 200))
        self.assertTrue(0 <= x1 and x2 <= 400 and 0 <= y1 and y2 <= 200)

    def test_safe_stem(self):
        self.assertEqual(mc.safe_stem("../../etc/passwd.jpg"), "passwd")
        self.assertEqual(mc.safe_stem("a\\b\\인도 사진(1).JPG"), "인도_사진_1")
        self.assertEqual(mc.safe_stem("..."), "img")

    def test_split_is_stable_and_roughly_80_10_10(self):
        self.assertEqual(mc.split_of("x/frame_a"), mc.split_of("x/frame_a"))
        n = {"train": 0, "val": 0, "test": 0}
        for i in range(2000):
            n[mc.split_of("k%d" % i)] += 1
        self.assertTrue(1500 < n["train"] < 1700, n)
        self.assertTrue(120 < n["val"] < 280, n)
        self.assertTrue(120 < n["test"] < 280, n)

    def test_zip_name_cp949_without_utf8_flag(self):
        raw = "15.인도보행영상/라벨.xml".encode("cp949")
        info = types.SimpleNamespace(flag_bits=0, filename=raw.decode("cp437"))
        self.assertEqual(mc.zip_name(info), "15.인도보행영상/라벨.xml")
        info = types.SimpleNamespace(flag_bits=0x800, filename="그대로.xml")
        self.assertEqual(mc.zip_name(info), "그대로.xml")


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "sample")
        os.makedirs(os.path.join(self.src, "bbox"))
        with open(os.path.join(self.src, "bbox", "labels.xml"), "w", encoding="utf-8") as f:
            f.write(XML)
        for name, color in (("frame_a.jpg", "red"), ("frame_b.jpg", "green"),
                            ("frame_c.jpg", "blue"), ("escape.jpg", "white")):
            with open(os.path.join(self.src, "bbox", name), "wb") as f:
                f.write(jpg_bytes(color))
        self.out = os.path.join(self.tmp, "out")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def rows(self):
        with open(os.path.join(self.out, "manifest.csv"), encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def check_result(self):
        rows = self.rows()
        got = sorted((r["image"], r["class"], r["label"]) for r in rows)
        self.assertEqual(got, sorted([
            ("frame_a.jpg", "construction", "barricade"),
            ("frame_a.jpg", "none", "car"),               # 가려져도 기본은 씀
            ("frame_b.jpg", "broken", "sidewalk:damaged"),  # 폴리곤 + 속성
            ("frame_c.jpg", "none", "person"),
            ("frame_c.jpg", "none", "(사진 전체)"),        # 신고 대상이 없는 사진은 전체도 한 장
            ("../../escape.jpg", "obstacle", "potted_plant"),
        ]))
        # bollard(10px)는 작은 박스, pole 은 안 씀, missing.jpg 는 사진 없음 → 결과에 없음
        for r in rows:
            path = os.path.join(self.out, r["split"], r["class"], r["file"])
            self.assertTrue(os.path.realpath(path).startswith(os.path.realpath(self.out) + os.sep))
            with Image.open(path) as im:
                self.assertEqual(im.size, (224, 224))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "escape.jpg")))

    def test_folder_source(self):
        self.assertEqual(mc.main([self.src, "--out", self.out]), 0)
        self.check_result()
        # 같은 원본 사진의 조각은 같은 묶음
        rows = [r for r in self.rows() if r["image"] == "frame_a.jpg"]
        self.assertEqual(len({r["split"] for r in rows}), 1)
        # 바리케이드 조각은 빨간 사진에서 잘림
        r = [r for r in rows if r["class"] == "construction"][0]
        with Image.open(os.path.join(self.out, r["split"], "construction", r["file"])) as im:
            px = im.getpixel((112, 112))
        self.assertTrue(px[0] > 200 and px[1] < 60 and px[2] < 60, px)

    def test_zip_source_reads_without_extracting(self):
        zpath = os.path.join(self.tmp, "Bbox_1_new.zip")
        with zipfile.ZipFile(zpath, "w") as z:
            for root, _d, files in os.walk(self.src):
                for f in files:
                    full = os.path.join(root, f)
                    z.write(full, "15.인도보행영상/바운딩박스/" + os.path.relpath(full, self.src))
        self.assertEqual(mc.main([zpath, "--out", self.out]), 0)
        self.check_result()
        self.assertTrue(all(r["source"] == "Bbox_1_new" for r in self.rows()))

    def test_options(self):
        mc.main([self.src, "--out", self.out, "--skip-occluded", "--min-box", "5"])
        labels = sorted(r["label"] for r in self.rows())
        self.assertNotIn("car", labels)        # 가려진 차 빠짐
        self.assertIn("bollard", labels)       # 작은 박스 기준을 낮추면 들어옴

    def test_refuses_to_overwrite_without_flag(self):
        mc.main([self.src, "--out", self.out])
        with self.assertRaises(SystemExit):
            mc.main([self.src, "--out", self.out])
        self.assertEqual(mc.main([self.src, "--out", self.out, "--overwrite"]), 0)
        self.check_result()

    def test_list_labels(self):
        src = mc.Source(self.src)
        cnt = mc.list_labels([src])
        self.assertEqual(cnt["barricade"], 2)
        self.assertEqual(cnt["sidewalk:damaged"], 1)
        self.assertEqual(cnt["sidewalk"], 1)


if __name__ == "__main__":
    unittest.main()
