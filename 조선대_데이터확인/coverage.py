"""공공데이터(보안등·CCTV·편의점)를 OSM 보행망에 겹쳐 보기
- 길 조각마다: 30m 안 보안등 수, 50m 안 CCTV 대수, 50m 안 편의점 수
- 보안등 파일은 동구 것뿐이라, 가장 가까운 상가(200m 안)의 구 이름으로 길을 동구/그 밖으로 나눠 따로 잰다
결과: out/coverage.json, figs/night_layers.png
"""
import json
import warnings

warnings.filterwarnings("ignore")
import geopandas as gpd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import osmnx as ox
import pandas as pd

S, N, W, E = 35.125, 35.160, 126.915, 126.950
UTM = "EPSG:32652"


def inbox(df, lat="위도", lon="경도"):
    df = df[(df[lat].between(S, N)) & (df[lon].between(W, E))].copy()
    return gpd.GeoDataFrame(df, geometry=gpd.points_from_xy(df[lon], df[lat]), crs="EPSG:4326").to_crs(UTM)


light = pd.read_csv("raw/pubdata/donggu_securitylight_std.csv", encoding="utf-8-sig")
light_raw = len(light)
light = light.drop_duplicates(subset=["위도", "경도"])
light = inbox(light)
cctv = pd.read_csv("raw/pubdata/gwangju_cctv_15084631.csv", encoding="cp949")
cctv = inbox(cctv)
cctv_pts = cctv.dissolve(by=["위도", "경도"], aggfunc={"카메라대수": "sum"}).reset_index()
shop = pd.read_csv("raw/pubdata/sbiz_bbox_chosun.csv", encoding="utf-8-sig", low_memory=False)
shop = inbox(shop)
conv = shop[shop["상권업종소분류명"] == "편의점"]

G = ox.load_graphml("out/walk.graphml")
edges = ox.convert.graph_to_gdfs(ox.convert.to_undirected(G), nodes=False).to_crs(UTM).reset_index(drop=True)
edges["mid"] = edges.geometry.interpolate(0.5, normalized=True)

# 길이 어느 구에 있나: 가운데 점에서 가장 가까운 상가의 시군구 (200m 넘으면 '모름')
mid = gpd.GeoDataFrame(edges[["length"]], geometry=edges["mid"], crs=UTM)
near = gpd.sjoin_nearest(mid, shop[["시군구명", "geometry"]], how="left", max_distance=200, distance_col="d")
near = near[~near.index.duplicated()]
edges["gu"] = near["시군구명"].fillna("모름").values


def count_within(points, r, weight=None):
    buf = gpd.GeoDataFrame(geometry=edges.geometry.buffer(r), crs=UTM)
    j = gpd.sjoin(buf, points, how="left", predicate="intersects")
    if weight:
        return j.groupby(level=0)[weight].sum().reindex(edges.index).fillna(0).values
    return j.groupby(level=0)["index_right"].count().reindex(edges.index).fillna(0).values


edges["lights30"] = count_within(light, 30)
edges["cctv50"] = count_within(cctv_pts, 50, weight="카메라대수")
edges["conv50"] = count_within(conv, 50)
edges["lights_per_100m"] = edges["lights30"] / np.maximum(edges["length"], 1) * 100


def share(df, cond):
    return round(100 * df[cond]["length"].sum() / df["length"].sum(), 1)


res = {"보안등": {"동구_원본_행": light_raw, "중복좌표_제거_후_범위안": len(light)},
       "CCTV": {"범위안_행": len(cctv), "범위안_지점": len(cctv_pts), "범위안_카메라": int(cctv["카메라대수"].sum())},
       "편의점_범위안": len(conv),
       "구별_길이_km": edges.groupby("gu")["length"].sum().div(1000).round(1).to_dict()}
for name, df in (("동구_길", edges[edges.gu == "동구"]), ("동구_밖_길", edges[edges.gu != "동구"])):
    res[name] = {"길이_km": round(df["length"].sum() / 1000, 1),
                 "보안등_30m_안_있는_길_%": share(df, df.lights30 > 0),
                 "CCTV_50m_안_있는_길_%": share(df, df.cctv50 > 0),
                 "편의점_50m_안_있는_길_%": share(df, df.conv50 > 0),
                 "셋_다_없는_길_%": share(df, (df.lights30 == 0) & (df.cctv50 == 0) & (df.conv50 == 0)),
                 "100m당_보안등_중앙값": round(float(df["lights_per_100m"].median()), 2)}
# 큰길에는 보안등이 아니라 가로등(빠진 데이터)이 있다 → 길 종류별로 보기
hw = edges["highway"].map(lambda v: v[0] if isinstance(v, list) else v)
dong = edges[edges.gu == "동구"]
res["동구_길종류별_보안등_30m_안_%"] = {
    g: share(dong[hw[dong.index].isin(s)], dong[hw[dong.index].isin(s)].lights30 > 0)
    for g, s in {"골목·생활도로": {"residential", "service", "living_street", "unclassified"},
                 "큰길": {"primary", "secondary", "tertiary", "trunk"},
                 "보행 전용": {"footway", "path", "pedestrian", "steps"}}.items()}
json.dump(res, open("out/coverage.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps(res, ensure_ascii=False, indent=1))

plt.rcParams["font.family"] = "AppleGothic"
fig, ax = plt.subplots(figsize=(10, 10))
e4 = edges.to_crs(4326)
e4[edges.lights30 == 0].plot(ax=ax, color="#adb5bd", linewidth=0.7)
e4[edges.lights30 > 0].plot(ax=ax, color="#f59f00", linewidth=1.2)
light.to_crs(4326).plot(ax=ax, color="#f08c00", markersize=1.5, label=f"보안등 {len(light)} (동구만)")
cctv_pts.to_crs(4326).plot(ax=ax, color="#1971c2", markersize=14, marker="s", label=f"CCTV 지점 {len(cctv_pts)}")
conv.to_crs(4326).plot(ax=ax, color="#2f9e44", markersize=22, marker="^", label=f"편의점 {len(conv)}")
ax.plot(126.931, 35.141, marker="*", color="k", markersize=16, label="조선대")
ax.plot([], [], color="#f59f00", label="보안등 30m 안 길")
ax.plot([], [], color="#adb5bd", label="보안등 없음(또는 동구 밖)")
ax.legend(loc="lower left", fontsize=9)
ax.set_title("밤길 안전 층 데이터 (공공데이터 + OSM 보행망)")
ax.set_axis_off()
fig.savefig("figs/night_layers.png", dpi=130, bbox_inches="tight")
print("saved")
