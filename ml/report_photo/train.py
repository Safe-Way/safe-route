# -*- coding: utf-8 -*-
"""신고 사진 분류 모델 학습·시험·ONNX 내보내기 (2026-11-19, A파트).

make_crops.py 가 만든 사진(OUT/{train,val,test}/{종류}/*.jpg, manifest.csv)으로
ImageNet 으로 미리 학습된 MobileNetV3-Small(torchvision)의 마지막 층만 6종류로 바꿔 다시 가르친다.
  1단계: 앞부분(특징 뽑는 층)은 그대로 두고 마지막 층만 학습 (--head-epochs)
  2단계: 전체를 작은 학습률로 조금 더 학습 (--full-epochs)
val 에서 가장 좋았던 모델을 남기고 test 로 종류별 맞힌 비율·헷갈린 표를 낸다.
--onnx 를 주면 서버(ONNX Runtime Java)에서 쓸 model.onnx 와 labels.json 을 만들고,
PyTorch 와 ONNX Runtime 답이 같은지 확인한다.

출력 칸은 늘 6개(CLASSES 순서)라 서버 쪽 코드는 데이터가 늘어도 그대로 쓴다. 학습 사진이 없는 종류는 나오지 않는다.

실행 (저장소 .venv 에 torch·torchvision·onnxruntime 설치)
  .venv/bin/python -I ml/report_photo/train.py data/raw/aihub/crops_v1 --out data/raw/aihub/models/v1 --onnx
  # 이미 학습한 model.pt 를 ONNX 로만 (onnx 패키지 필요)
  .venv/bin/python -I ml/report_photo/train.py data/raw/aihub/crops_v1 --out data/raw/aihub/models/v1 --export-only
"""
import argparse
import collections
import csv
import json
import os
import random
import sys
import time

import torch
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset
from torchvision import models, transforms

CLASSES = ("construction", "broken", "obstacle", "vehicle", "curb", "none")
NAMES = {"construction": "공사", "broken": "보도 파손", "obstacle": "막는 물건", "vehicle": "차량·오토바이",
         "curb": "턱", "none": "관계없음"}
MEAN = [0.485, 0.456, 0.406]   # ImageNet (미리 학습할 때 쓴 값, 서버도 같은 값으로 맞춘다)
STD = [0.229, 0.224, 0.225]
SIZE = 224


class Crops(Dataset):
    def __init__(self, root, split, tf):
        self.items = []
        with open(os.path.join(root, "manifest.csv"), encoding="utf-8") as f:
            for r in csv.DictReader(f):
                if r["split"] == split and r["class"] in CLASSES:
                    self.items.append((os.path.join(root, split, r["class"], r["file"]), CLASSES.index(r["class"])))
        self.tf = tf

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        path, y = self.items[i]
        with Image.open(path) as im:
            return self.tf(im.convert("RGB")), y

    def counts(self):
        return collections.Counter(y for _p, y in self.items)


def build_model(pretrained=True):
    m = models.mobilenet_v3_small(weights="IMAGENET1K_V1" if pretrained else None)
    m.classifier[3] = nn.Linear(m.classifier[3].in_features, len(CLASSES))
    return m


def evaluate(model, loader, device):
    model.eval()
    conf = [[0] * len(CLASSES) for _ in CLASSES]   # conf[정답][예측]
    with torch.no_grad():
        for x, y in loader:
            pred = model(x.to(device)).argmax(1).cpu()
            for t, p in zip(y.tolist(), pred.tolist()):
                conf[t][p] += 1
    return conf


def scores(conf):
    """종류별 (정답 수, 재현율, 정밀도), 정답이 있는 종류들의 평균 F1, 전체 정확도"""
    out = {}
    f1s = []
    total = sum(map(sum, conf))
    right = sum(conf[i][i] for i in range(len(CLASSES)))
    for i, c in enumerate(CLASSES):
        n = sum(conf[i])
        predicted = sum(conf[j][i] for j in range(len(CLASSES)))
        rec = conf[i][i] / n if n else None
        prec = conf[i][i] / predicted if predicted else None
        out[c] = (n, rec, prec)
        if n:
            f1 = 0.0 if not rec or not prec else 2 * rec * prec / (rec + prec)
            f1s.append(f1)
    return out, (sum(f1s) / len(f1s) if f1s else 0.0), (right / total if total else 0.0)


def print_report(title, conf):
    per, macro_f1, acc = scores(conf)
    print("\n[%s] 정확도 %.1f%%, 평균 F1 %.3f" % (title, acc * 100, macro_f1))
    print("%-14s %6s %8s %8s" % ("종류", "정답수", "재현율", "정밀도"))
    for c in CLASSES:
        n, rec, prec = per[c]
        if n or (prec is not None):
            print("%-14s %6d %8s %8s" % (c, n, "-" if rec is None else "%.1f%%" % (rec * 100),
                                         "-" if prec is None else "%.1f%%" % (prec * 100)))
    shown = [i for i, c in enumerate(CLASSES) if sum(conf[i]) or sum(conf[j][i] for j in range(len(CLASSES)))]
    print("헷갈린 표 (줄 = 정답, 칸 = 예측): " + " ".join(CLASSES[i][:6] for i in shown))
    for i in shown:
        print("  %-12s %s" % (CLASSES[i], " ".join("%6d" % conf[i][j] for j in shown)))
    return macro_f1, acc


def run_epochs(model, loader, val_loader, device, params, lr, epochs, weight, label, best):
    opt = torch.optim.AdamW(params, lr=lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, max(1, epochs * len(loader)))
    loss_fn = nn.CrossEntropyLoss(weight=weight.to(device))
    for ep in range(1, epochs + 1):
        model.train()
        t0 = time.time()
        total = n = 0
        for x, y in loader:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            loss = loss_fn(model(x), y)
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item() * len(y)
            n += len(y)
        _per, f1, acc = scores(evaluate(model, val_loader, device))
        mark = ""
        if f1 > best["f1"]:
            best.update(f1=f1, state={k: v.detach().cpu().clone() for k, v in model.state_dict().items()},
                        where="%s %d" % (label, ep))
            mark = " ← 지금까지 가장 좋음"
        print("  %s %d/%d: 학습 손실 %.3f, val 정확도 %.1f%% 평균 F1 %.3f (%.0f초)%s"
              % (label, ep, epochs, total / max(n, 1), acc * 100, f1, time.time() - t0, mark))


def export_onnx(model, out, device):
    model = model.to("cpu").eval()
    path = os.path.join(out, "model.onnx")
    dummy = torch.randn(1, 3, SIZE, SIZE)
    torch.onnx.export(model, dummy, path, input_names=["image"], output_names=["logits"],
                      dynamic_axes={"image": {0: "batch"}, "logits": {0: "batch"}}, opset_version=17, dynamo=False)
    with open(os.path.join(out, "labels.json"), "w", encoding="utf-8") as f:
        json.dump({"classes": list(CLASSES), "names": NAMES, "input": [1, 3, SIZE, SIZE], "layout": "NCHW RGB 0~1",
                   "mean": MEAN, "std": STD, "output": "logits (softmax 하면 확률)"}, f, ensure_ascii=False, indent=2)
    # PyTorch 와 ONNX Runtime 답이 같은지
    import onnxruntime as ort
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    x = torch.randn(4, 3, SIZE, SIZE)
    with torch.no_grad():
        a = model(x).numpy()
    b = sess.run(None, {"image": x.numpy()})[0]
    diff = float(abs(a - b).max())
    print("\nONNX 저장 %s (%.1f MB), PyTorch 와 최대 차이 %.2e" % (path, os.path.getsize(path) / 1e6, diff))
    if diff > 1e-3:
        raise SystemExit("ONNX 답이 PyTorch 와 다릅니다")
    return path


def main(argv=None):
    ap = argparse.ArgumentParser(description="신고 사진 분류 모델 학습 (MobileNetV3-Small)")
    ap.add_argument("crops", help="make_crops.py 결과 폴더")
    ap.add_argument("--out", required=True, help="모델 저장 폴더 (예: data/raw/aihub/models/sample)")
    ap.add_argument("--head-epochs", type=int, default=3)
    ap.add_argument("--full-epochs", type=int, default=5)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--cpu", action="store_true", help="맥 GPU(MPS)를 쓰지 않음")
    ap.add_argument("--onnx", action="store_true", help="model.onnx·labels.json 도 만듦")
    ap.add_argument("--export-only", action="store_true", help="학습하지 않고 OUT/model.pt 를 ONNX 로만 내보냄")
    a = ap.parse_args(argv)
    if a.export_only:
        ck = torch.load(os.path.join(a.out, "model.pt"), map_location="cpu")
        model = build_model(pretrained=False)
        model.load_state_dict(ck["state_dict"])
        export_onnx(model, a.out, "cpu")
        return 0

    random.seed(a.seed)
    torch.manual_seed(a.seed)
    device = torch.device("mps" if torch.backends.mps.is_available() and not a.cpu else "cpu")
    os.makedirs(a.out, exist_ok=True)

    norm = transforms.Normalize(MEAN, STD)
    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(SIZE, scale=(0.6, 1.0)),
        transforms.RandomHorizontalFlip(),
        transforms.ColorJitter(0.3, 0.3, 0.3),
        transforms.ToTensor(), norm])
    eval_tf = transforms.Compose([transforms.Resize((SIZE, SIZE)), transforms.ToTensor(), norm])
    sets = {sp: Crops(a.crops, sp, train_tf if sp == "train" else eval_tf) for sp in ("train", "val", "test")}
    print("장치 %s" % device)
    for sp, ds in sets.items():
        c = ds.counts()
        print("  %-5s %5d장: %s" % (sp, len(ds), ", ".join("%s %d" % (CLASSES[i], c[i]) for i in range(len(CLASSES)) if c[i])))
    if not len(sets["train"]) or not len(sets["val"]):
        raise SystemExit("train·val 사진이 없습니다")

    # 종류마다 사진 수가 크게 달라(공사 10장, 차량 700장) 적은 종류의 틀림을 더 크게 센다: 1/√개수
    cnt = sets["train"].counts()
    w = torch.tensor([1.0 / (cnt[i] ** 0.5) if cnt[i] else 0.0 for i in range(len(CLASSES))])
    w = w / w[w > 0].mean()
    print("종류 가중치: " + ", ".join("%s %.2f" % (CLASSES[i], w[i]) for i in range(len(CLASSES)) if cnt[i]))

    tl = DataLoader(sets["train"], batch_size=a.batch, shuffle=True, num_workers=0)
    vl = DataLoader(sets["val"], batch_size=64, num_workers=0)
    model = build_model().to(device)
    best = {"f1": -1.0, "state": None, "where": ""}

    print("\n1단계: 마지막 층만 학습")
    for p in model.features.parameters():
        p.requires_grad = False
    run_epochs(model, tl, vl, device, [p for p in model.parameters() if p.requires_grad], 1e-3,
               a.head_epochs, w, "마지막 층", best)
    print("2단계: 전체 학습")
    for p in model.parameters():
        p.requires_grad = True
    run_epochs(model, tl, vl, device, model.parameters(), 1e-4, a.full_epochs, w, "전체", best)

    model.load_state_dict(best["state"])
    torch.save({"state_dict": best["state"], "classes": CLASSES, "mean": MEAN, "std": STD, "size": SIZE,
                "best": best["where"]}, os.path.join(a.out, "model.pt"))
    print("\n가장 좋았던 때: %s (val 평균 F1 %.3f) → %s" % (best["where"], best["f1"], os.path.join(a.out, "model.pt")))

    result = {}
    for sp in ("val", "test"):
        if len(sets[sp]):
            conf = evaluate(model, DataLoader(sets[sp], batch_size=64, num_workers=0), device)
            f1, acc = print_report(sp, conf)
            result[sp] = {"accuracy": acc, "macro_f1": f1, "confusion": conf}
    result["classes"] = list(CLASSES)
    with open(os.path.join(a.out, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    if a.onnx:
        export_onnx(model, a.out, device)
    return 0


if __name__ == "__main__":
    sys.exit(main())
