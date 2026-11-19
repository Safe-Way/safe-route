# -*- coding: utf-8 -*-
"""salvage_zip.py 테스트: 중간에 끊긴 tar(안에 zip 앞부분)에서 온전한 파일만 꺼내는지.

실행: .venv/bin/python -I -m unittest discover -s ml/report_photo -v
"""
import io
import os
import shutil
import sys
import tarfile
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import salvage_zip as sz  # noqa: E402


class SalvageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def make_tar(self, cut_ratio):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("Surface_001/", b"")
            z.writestr("Surface_001/라벨.xml", b"<annotations/>" * 50, compress_type=zipfile.ZIP_DEFLATED)
            z.writestr("Surface_001/a.jpg", os.urandom(3000), compress_type=zipfile.ZIP_STORED)
            z.writestr("../../evil.txt", b"nope", compress_type=zipfile.ZIP_STORED)
            z.writestr("Surface_002/b.jpg", os.urandom(5000), compress_type=zipfile.ZIP_STORED)
        data = buf.getvalue()
        tar_path = os.path.join(self.tmp, "download.tar")
        with tarfile.open(tar_path, "w", format=tarfile.USTAR_FORMAT) as t:
            info = tarfile.TarInfo("15.인도보행영상/서피스마스킹/Surface_1.zip.part0")
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))
        # 받다 끊긴 것처럼 뒤를 자른다
        with open(tar_path, "r+b") as f:
            f.truncate(int(512 + len(data) * cut_ratio))
        return tar_path

    def test_keeps_only_complete_files(self):
        out = os.path.join(self.tmp, "out")
        ok, skipped, _total, cut = sz.salvage(self.make_tar(0.85), out)
        self.assertEqual(skipped, 0)
        self.assertEqual(cut, "Surface_002/b.jpg")   # 마지막 파일은 잘려서 버림
        self.assertTrue(os.path.exists(os.path.join(out, "Surface_001", "라벨.xml")))
        with open(os.path.join(out, "Surface_001", "라벨.xml"), "rb") as f:
            self.assertEqual(f.read(), b"<annotations/>" * 50)   # deflate 풀림
        self.assertTrue(os.path.exists(os.path.join(out, "Surface_001", "a.jpg")))
        # '../../evil.txt' 는 OUT 안 evil.txt 로만
        self.assertTrue(os.path.exists(os.path.join(out, "evil.txt")))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "evil.txt")))
        self.assertEqual(ok, 3)

    def test_whole_file(self):
        out = os.path.join(self.tmp, "out")
        ok, _skipped, _total, cut = sz.salvage(self.make_tar(1.0), out)
        self.assertEqual(ok, 4)
        self.assertIsNone(cut)


if __name__ == "__main__":
    unittest.main()
