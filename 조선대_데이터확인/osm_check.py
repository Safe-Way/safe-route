"""조선대 주변 OSM 보행망 품질 확인
- 범위: 위도 35.125~35.160, 경도 126.915~126.950 (조선대 중심, 약 3.2 x 3.9 km)
- 결과: out/osm_stats.json, figs/osm_walk_types.png, figs/osm_lit.png
"""
import json
import os
import warnings
from collections import Counter

warnings.filterwarnings("ignore")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import osmnx as ox

N, S, E, W = 35.160, 35.125, 126.950, 126.915
BBOX = (W, S, E, N)                       # osmnx 2.x: (left, bottom, right, top)
os.makedirs("out", exist_ok=True)
os.makedirs("figs", exist_ok=True)
ox.settings.useful_tags_way = list(ox.settings.useful_tags_way) + [
    "lit", "sidewalk", "sidewalk:both", "sidewalk:left", "sidewalk:right", "surface", "incline", "footway",
    "covered", "tunnel", "wheelchair", "smoothness", "crossing"]
ox.settings.log_console = False

# 맥 기본 파이썬은 SSL이 오래돼 OSM 서버에 직접 못 붙는다 → raw/highway.osm은 curl로 받은 Overpass 원본
# (way["highway"](35.125,126.915,35.160,126.950); 2026-10-28)
G_all = ox.graph_from_xml("raw/highway.osm", bidirectional=True, simplify=False, retain_all=True)
G_all = ox.truncate.truncate_graph_bbox(G_all, BBOX, truncate_by_edge=False)   # 범위 밖으로 뻗은 길(등산로 등) 자르기
NOT_WALK = {"motorway", "motorway_link", "construction", "proposed", "abandoned", "platform", "raceway",
            "bus_guideway", "busway", "escalator", "elevator", "razed"}
NOT_DRIVE = {"footway", "pedestrian", "path", "steps", "corridor", "cycleway", "track", "bridleway", "elevator",
             "construction", "proposed", "abandoned", "platform"}


def hw_of(d):
    h = d.get("highway")
    return h[0] if isinstance(h, list) else h


def sub(keep):
    H = G_all.copy()
    H.remove_edges_from([(u, v, k) for u, v, k, d in H.edges(keys=True, data=True) if not keep(d)])
    H.remove_nodes_from([n for n in list(H.nodes) if H.degree(n) == 0])
    return ox.simplify_graph(H)


G = sub(lambda d: hw_of(d) not in NOT_WALK and d.get("foot") != "no" and d.get("access") not in ("private", "no"))
Gd = sub(lambda d: hw_of(d) not in NOT_DRIVE and d.get("access") not in ("private", "no"))
G = ox.distance.add_edge_lengths(G)
Gd = ox.distance.add_edge_lengths(Gd)
Gu = ox.convert.to_undirected(G)
edges = ox.convert.graph_to_gdfs(Gu, nodes=False)
nodes = ox.convert.graph_to_gdfs(Gu, edges=False)


def first(v):
    return v[0] if isinstance(v, list) else v


edges["hw"] = edges["highway"].map(first)
L = edges["length"].sum()
by_type = edges.groupby("hw")["length"].sum().sort_values(ascending=False)

GROUP = {
    "보행 전용": {"footway", "pedestrian", "path", "steps", "corridor", "cycleway", "track", "bridleway"},
    "골목·생활도로": {"residential", "service", "living_street", "unclassified", "alley"},
    "큰길(차도)": {"primary", "secondary", "tertiary", "trunk", "primary_link", "secondary_link", "tertiary_link",
               "trunk_link", "motorway", "motorway_link"},
}
grp = {g: float(edges[edges.hw.isin(s)]["length"].sum()) for g, s in GROUP.items()}
grp["기타"] = float(L - sum(grp.values()))


def cover(col):
    """그 태그가 붙은 길의 길이 비율(%)"""
    if col not in edges:
        return 0.0
    return round(100 * edges[edges[col].notna()]["length"].sum() / L, 1)


lit_vals = Counter(first(v) for v in edges.get("lit", []) if isinstance(v, (str, list)))
big = edges[edges.hw.isin(GROUP["큰길(차도)"])]
side_cols = [c for c in ("sidewalk", "sidewalk:both", "sidewalk:left", "sidewalk:right") if c in edges]
big_side = big[big[side_cols].notna().any(axis=1)]["length"].sum() / max(big["length"].sum(), 1) if side_cols else 0

# 연결성: 가장 큰 덩어리 비율, 막다른 길
import networkx as nx
comps = sorted(nx.connected_components(Gu), key=len, reverse=True)
deg = dict(Gu.degree())
dead = sum(1 for d in deg.values() if d == 1)

# 점 데이터: 가로등·CCTV·횡단보도가 OSM에 있는지
# raw/points.json: curl로 받은 Overpass 결과 (가로등·횡단보도·CCTV·편의점·경찰·화장실)
els = json.load(open("raw/points.json"))["elements"]
pts = {}
for name, (k, v) in {"가로등(highway=street_lamp)": ("highway", "street_lamp"),
                     "CCTV(man_made=surveillance)": ("man_made", "surveillance"),
                     "횡단보도(highway=crossing)": ("highway", "crossing"),
                     "편의점(shop=convenience)": ("shop", "convenience"),
                     "경찰(amenity=police)": ("amenity", "police"),
                     "화장실(amenity=toilets)": ("amenity", "toilets")}.items():
    pts[name] = sum(1 for e in els if e.get("tags", {}).get(k) == v)
pts["편의점_중_24시간_표시"] = sum(1 for e in els if e.get("tags", {}).get("shop") == "convenience"
                            and "24/7" in e.get("tags", {}).get("opening_hours", ""))

stats = {
    "범위": {"north": N, "south": S, "east": E, "west": W},
    "보행망": {"노드": Gu.number_of_nodes(), "링크": Gu.number_of_edges(), "총길이_km": round(L / 1000, 1),
            "차도망_총길이_km": round(sum(d["length"] for _, _, d in ox.convert.to_undirected(Gd).edges(data=True)) / 1000, 1)},
    "종류별_길이_km": {k: round(v / 1000, 2) for k, v in by_type.items()},
    "묶음별_비율_%": {k: round(100 * v / L, 1) for k, v in grp.items()},
    "태그_붙은_길이_비율_%": {c: cover(c) for c in ("lit", "sidewalk", "surface", "incline", "width", "wheelchair", "covered", "name")},
    "lit_값": dict(lit_vals),
    "큰길_중_인도정보_있는_비율_%": round(100 * big_side, 1),
    "계단_개수": int((edges.hw == "steps").sum()),
    "연결성": {"덩어리_수": len(comps), "가장_큰_덩어리_노드_비율_%": round(100 * len(comps[0]) / Gu.number_of_nodes(), 1),
            "막다른_노드_비율_%": round(100 * dead / Gu.number_of_nodes(), 1)},
    "OSM_점_데이터_개수": pts,
}
with open("out/osm_stats.json", "w") as f:
    json.dump(stats, f, ensure_ascii=False, indent=1)
print(json.dumps(stats, ensure_ascii=False, indent=1))

# 그림 1: 길 종류
col = {"보행 전용": "#d6336c", "골목·생활도로": "#1c7ed6", "큰길(차도)": "#868e96", "기타": "#f59f00"}


def gname(h):
    for g, s in GROUP.items():
        if h in s:
            return g
    return "기타"


plt.rcParams["font.family"] = "AppleGothic"
fig, ax = plt.subplots(figsize=(9, 9))
for g, c in col.items():
    sub = edges[edges.hw.map(gname) == g]
    if len(sub):
        sub.plot(ax=ax, color=c, linewidth=0.9 if g != "큰길(차도)" else 1.6, label=f"{g} {100 * sub['length'].sum() / L:.0f}%")
ax.plot(126.931, 35.141, marker="*", color="k", markersize=14, label="조선대")
ax.legend(loc="lower left")
ax.set_title("조선대 주변 OSM 보행망 (길 종류)")
ax.set_axis_off()
fig.savefig("figs/osm_walk_types.png", dpi=130, bbox_inches="tight")

# 그림 2: lit 태그
fig, ax = plt.subplots(figsize=(9, 9))
edges.plot(ax=ax, color="#ced4da", linewidth=0.7)
if "lit" in edges:
    lit = edges[edges["lit"].notna()]
    if len(lit):
        lit.plot(ax=ax, color="#f08c00", linewidth=2, label=f"lit 태그 있음 ({cover('lit')}%)")
ax.plot(126.931, 35.141, marker="*", color="k", markersize=14, label="조선대")
ax.legend(loc="lower left")
ax.set_title("OSM 'lit'(가로등) 태그가 붙은 길")
ax.set_axis_off()
fig.savefig("figs/osm_lit.png", dpi=130, bbox_inches="tight")
ox.save_graphml(G, "out/walk.graphml")
print("saved")
