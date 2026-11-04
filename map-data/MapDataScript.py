import json
import math
NODE_FILE = "indoor_map_graph.json"         
OUTPUT_EDGE_FILE = "edges_with_distance.json"  
with open(NODE_FILE, "r") as f:
    nodes = json.load(f)

edges = set()
edge_list = []

def compute_distance(p1, p2):
    return round(math.hypot(p2[0] - p1[0], p2[1] - p1[1]), 3)

for from_id, from_node in nodes.items():
    from_coords = (from_node["x"], from_node["y"])
    for to_id in from_node["connections"]:
        # Make Sure Each Edge Added Once Only
        edge_key = tuple(sorted((from_id, to_id)))
        if edge_key not in edges:
            to_coords = (nodes[to_id]["x"], nodes[to_id]["y"])
            distance = compute_distance(from_coords, to_coords)
            edge_list.append({
                "from": from_id,
                "to": to_id,
                "distance": distance
            })
            edges.add(edge_key)

with open(OUTPUT_EDGE_FILE, "w") as f:
    json.dump(edge_list, f, indent=2)
    
print(f"✅ Edges File Generation Successfuly with {len(edge_list)} edges，Output File Name：{OUTPUT_EDGE_FILE}")
