# -*- coding: utf-8 -*-
"""받다 끊긴 AI Hub download.tar 에서 끝까지 받아진 파일만 꺼낸다 (2026-11-19, A파트).

AI Hub 는 이어받기(Range)를 받지 않고 연결을 20~30분마다 끊어서 10GB zip 을 끝까지 받기 어렵다.
그런데 인도보행 영상 zip 은 작은 폴더(Surface_001/ = 라벨 XML 1개 + 사진·마스크 약 120장) 단위로
앞에서부터 차례로 들어 있어서, 앞부분만 받아도 온전한 폴더 몇 개를 쓸 수 있다.
zip 의 목차(central directory)는 맨 끝에 있어 보통 프로그램은 못 여니, 각 파일 앞의 머리(local header)를
차례로 읽어 끝까지 받아진 파일만 CRC 를 확인하고 꺼낸다. 마지막에 잘린 파일은 버린다.

받은 tar 는 남이 만든 자료라 믿지 않는다: python3 -I 로 돌리고, 이름의 '..'·절대 경로는 지우고 OUT 안에만 쓴다.
지원: tar 첫 항목이 zip 조각(…zip.part0)인 경우, 압축 방식 저장(0)·deflate(8), ZIP64 크기.

실행
  python3 -I ml/report_photo/salvage_zip.py data/raw/aihub/inbound/download.tar data/raw/aihub/surface_1_head
"""
import os
import struct
import sys
import zlib

TAR_HEADER = 512
MAX_FILE = 200 << 20   # 파일 하나 200MB 넘으면 이상한 것으로 보고 멈춤


def sizes_from_zip64(extra, csize, usize):
    i = 0
    while i + 4 <= len(extra):
        hid, hl = struct.unpack("<HH", extra[i:i + 4])
        if hid == 1:
            vals = struct.unpack("<" + "Q" * (hl // 8), extra[i + 4:i + 4 + hl - hl % 8])
            k = 0
            if usize == 0xFFFFFFFF and k < len(vals):
                usize = vals[k]
                k += 1
            if csize == 0xFFFFFFFF and k < len(vals):
                csize = vals[k]
        i += 4 + hl
    return csize, usize


def safe_parts(name):
    return [p for p in name.replace("\\", "/").split("/") if p not in ("", ".", "..")]


def salvage(src, out):
    size = os.path.getsize(src)
    ok = skipped = 0
    total = 0
    cut = None
    with open(src, "rb") as f:
        head = f.read(TAR_HEADER)
        first = head[:100].split(b"\0")[0].decode("utf-8", "replace")
        pos = TAR_HEADER if ".zip.part" in first else 0
        while True:
            f.seek(pos)
            h = f.read(30)
            if len(h) < 30 or h[:4] != b"PK\x03\x04":
                break
            _v, flags, method, _t, _d, crc, csize, usize, nlen, xlen = struct.unpack("<HHHHHIIIHH", h[4:])
            raw = f.read(nlen)
            extra = f.read(xlen)
            name = raw.decode("utf-8" if flags & 0x800 else "cp949", "replace")
            csize, usize = sizes_from_zip64(extra, csize, usize)
            if flags & 0x8 and csize == 0:
                cut = name + " (크기가 데이터 뒤에 적힌 형식이라 더 못 읽음)"
                break
            data_at = pos + 30 + nlen + xlen
            if data_at + csize > size:
                cut = name
                break
            pos = data_at + csize
            parts = safe_parts(name)
            if name.endswith("/") or not parts:
                continue
            if usize > MAX_FILE or csize > MAX_FILE:
                print("너무 큼, 건너뜀: %s" % name)
                skipped += 1
                continue
            f.seek(data_at)
            blob = f.read(csize)
            if method == 8:
                blob = zlib.decompress(blob, -15)
            elif method != 0:
                print("모르는 압축 방식 %d, 건너뜀: %s" % (method, name))
                skipped += 1
                continue
            if zlib.crc32(blob) & 0xFFFFFFFF != crc:
                print("CRC 틀림, 건너뜀: %s" % name)
                skipped += 1
                continue
            dst = os.path.join(out, *parts)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as g:
                g.write(blob)
            ok += 1
            total += len(blob)
    return ok, skipped, total, cut


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        raise SystemExit("사용법: salvage_zip.py 받은.tar 꺼낼폴더")
    src, out = argv
    os.makedirs(out, exist_ok=True)
    ok, skipped, total, cut = salvage(src, out)
    print("꺼낸 파일 %d개 (%.0f MB), 건너뜀 %d" % (ok, total / 1e6, skipped))
    if cut:
        print("여기서 끊김: %s" % cut)
    return 0


if __name__ == "__main__":
    sys.exit(main())
