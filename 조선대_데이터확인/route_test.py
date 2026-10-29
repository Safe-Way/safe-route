"""보행망 경로 시험: 무작위 300쌍의 우회율(경로 길이 ÷ 직선거리)과 실패 비율"""
import json, random, warnings
warnings.filterwarnings("ignore")
import networkx as nx, numpy as np, osmnx as ox
G = ox.load_graphml("out/walk.graphml")
Gu = ox.convert.to_undirected(G)
big = Gu.subgraph(max(nx.connected_components(Gu), key=len)).copy()
nodes = list(Gu.nodes)
random.seed(0)
ratios, fail, far = [], 0, 0
for _ in range(300):
    a, b = random.sample(nodes, 2)
    ya, xa, yb, xb = Gu.nodes[a]["y"], Gu.nodes[a]["x"], Gu.nodes[b]["y"], Gu.nodes[b]["x"]
    d0 = ox.distance.great_circle(ya, xa, yb, xb)
    if d0 < 300:
        continue
    try:
        d = nx.shortest_path_length(Gu, a, b, weight="length")
        ratios.append(d / d0)
    except nx.NetworkXNoPath:
        fail += 1
r = np.array(ratios)
out = {"쌍": len(r) + fail, "경로_없음": fail, "우회율_중앙값": round(float(np.median(r)), 3),
       "우회율_p90": round(float(np.percentile(r, 90)), 3), "우회율_1.5_초과_비율_%": round(100 * float((r > 1.5).mean()), 1)}
print(json.dumps(out, ensure_ascii=False))
json.dump(out, open("out/route_test.json", "w"), ensure_ascii=False, indent=1)
