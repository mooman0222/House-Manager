#!/usr/bin/env python3
"""候補物件レポート: 住所 → 災害リスク / 建築条件 / 生活環境 / 価格の目安 (Markdown)

usage: REINFOLIB_API_KEY=... python3 report.py "東京都文京区本郷7-3-1" --type mansion [--price 6800 --area 65 --built 2010]
       (.env に REINFOLIB_API_KEY=... を書いてもよい)
"""
import argparse, datetime, gzip, json, math, os, re, statistics, sys, time, threading, urllib.parse, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "https://www.reinfolib.mlit.go.jp/ex-api/external/"
CACHE = Path(__file__).parent / ".cache"
LAND_TYPE = {"mansion": "07", "house": "02", "land": "01"}
LAND_LABEL = {"mansion": "中古マンション等", "house": "宅地(土地と建物)", "land": "宅地(土地)"}
GAP = 0.35  # 不動産情報ライブラリQ&A Q.3「間隔を空けて」対応の最低開始間隔。MCP並のバースト並列は避ける
_lock = threading.Lock()
_last_start = [0.0]


def _wait_turn():
    with _lock:
        wait = _last_start[0] + GAP - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        _last_start[0] = time.monotonic()


def api_key():
    k = os.environ.get("REINFOLIB_API_KEY")
    if not k and (Path(__file__).parent / ".env").exists():
        for line in (Path(__file__).parent / ".env").read_text().splitlines():
            if line.startswith("REINFOLIB_API_KEY="):
                k = line.split("=", 1)[1].strip().strip('"')
    if not k:
        sys.exit("REINFOLIB_API_KEY が未設定ですわ (.env か環境変数に)")
    return k


def http_json(url, headers=None, cache_key=None, retries=3):
    if cache_key:
        f = CACHE / (re.sub(r"[^\w.-]", "_", cache_key) + ".json")
        if f.exists():
            return json.loads(f.read_text())
    attempt = 0
    while True:
        _wait_turn()
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                body = r.read()
                if r.headers.get("Content-Encoding") == "gzip" or body[:2] == b"\x1f\x8b":
                    body = gzip.decompress(body)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                body = b'{"type":"FeatureCollection","features":[]}'
            elif e.code == 429 or 500 <= e.code <= 599:
                if attempt >= retries:
                    sys.exit(f"HTTP {e.code} {url} (再試行上限)")
                attempt += 1
                time.sleep(2 ** attempt)  # 2s, 4s, 8s
                continue
            else:
                sys.exit(f"HTTP {e.code} {url}\n{e.read()[:300]}")
        break
    data = json.loads(body)
    if cache_key:
        CACHE.mkdir(exist_ok=True)
        f.write_text(json.dumps(data, ensure_ascii=False))
    return data


def geocode(addr):
    url = "https://msearch.gsi.go.jp/address-search/AddressSearch?q=" + urllib.parse.quote(addr)
    res = http_json(url)
    if not res:
        sys.exit(f"住所が見つかりませんの: {addr}")
    lon, lat = res[0]["geometry"]["coordinates"]
    return lat, lon, res[0]["properties"]["title"]


def tile(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def cover_tiles(lat, lon, z, radius_m):
    """半径radius_mの円を覆うタイル列挙。中心＋はみ出す隣接のみ (最大3x3)。around=1固定(9枚)の削減用。"""
    if radius_m <= 0:
        return [tile(lat, lon, z)]
    d_lat = radius_m / 111320.0
    d_lon = radius_m / (111320.0 * max(math.cos(math.radians(lat)), 0.2))
    x1, y1 = tile(max(-85.0, min(85.0, lat - d_lat)), lon - d_lon, z)
    x2, y2 = tile(max(-85.0, min(85.0, lat + d_lat)), lon + d_lon, z)
    xs = range(min(x1, x2), min(max(x1, x2), min(x1, x2) + 2) + 1)
    ys = range(min(y1, y2), min(max(y1, y2), min(y1, y2) + 2) + 1)
    return [(x, y) for x in xs for y in ys]


def dist_m(lat1, lon1, lat2, lon2):
    p = math.pi / 180
    a = 0.5 - math.cos((lat2 - lat1) * p) / 2 + math.cos(lat1 * p) * math.cos(lat2 * p) * (1 - math.cos((lon2 - lon1) * p)) / 2
    return 12742000 * math.asin(math.sqrt(a))


def in_ring(lon, lat, ring):
    inside = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def contains(geom, lon, lat):
    polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"] if geom["type"] == "MultiPolygon" else []
    return any(in_ring(lon, lat, p[0]) and not any(in_ring(lon, lat, h) for h in p[1:]) for p in polys)


def line_dist_m(geom, lat, lon):
    lines = [geom["coordinates"]] if geom["type"] == "LineString" else geom["coordinates"] if geom["type"] == "MultiLineString" else []
    best = math.inf
    for line in lines:
        for (x1, y1), (x2, y2) in zip(line, line[1:]):
            # 線分上の最近点 (局所平面近似)
            dx, dy = (x2 - x1) * math.cos(math.radians(lat)), y2 - y1
            px, py = (lon - x1) * math.cos(math.radians(lat)), lat - y1
            t = max(0, min(1, (px * dx + py * dy) / (dx * dx + dy * dy))) if dx or dy else 0
            best = min(best, dist_m(lat, lon, y1 + t * (y2 - y1), x1 + t * (x2 - x1)))
    return best


def point_of(geom):
    c = geom["coordinates"]
    while isinstance(c[0], list):
        c = c[0]
    return c[1], c[0]  # lat, lon


class Lib:
    def __init__(self, key, lat, lon):
        self.h = {"Ocp-Apim-Subscription-Key": key}
        self.lat, self.lon = lat, lon

    def _url(self, api, z, x, y, params):
        q = dict(response_format="geojson", z=z, x=x, y=y, **params)
        qs = urllib.parse.urlencode(q)
        return BASE + api + "?" + qs, api + "_" + qs

    def multi(self, reqs):
        """複数APIのまとめ取得 (MCP get_multi_api相当の自前版)。

        reqs: [(api, z, tiles|None, params)] の列。tilesがNoneなら中心1タイル、
        cover_radius_mを使う場合は ("cover", radius) 形式で渡す (下のhere_all/near_all参照)。
        同一URLは1回に束ね、キャッシュ済みは通信せず、未取得分だけ最大3並列で取得する。
        """
        jobs = {}  # url -> (api, cache_key)
        order = []  # (req_idx, url)
        norm = []
        for api, z, how, params in reqs:
            if isinstance(how, tuple) and how and how[0] == "cover":
                tiles = cover_tiles(self.lat, self.lon, z, how[1])
            elif how is None:
                tiles = [tile(self.lat, self.lon, z)]
            else:
                x0, y0 = tile(self.lat, self.lon, z)
                tiles = [(x0 + dx, y0 + dy) for dx in range(-how, how + 1) for dy in range(-how, how + 1)]
            norm.append((api, z, tiles, params))
            for x, y in tiles:
                url, ck = self._url(api, z, x, y, params)
                if url not in jobs:
                    jobs[url] = (api, ck)
                order.append((len(norm) - 1, url))
        feats_by_url = {}
        missing = [(u, ck) for u, (_, ck) in jobs.items()
                   if not (CACHE / (re.sub(r"[^\w.-]", "_", ck) + ".json")).exists()]
        if missing:
            def fetch(pair):
                u, ck = pair
                return u, http_json(u, self.h, cache_key=ck).get("features") or []
            with ThreadPoolExecutor(max_workers=min(3, len(missing))) as ex:
                for u, feats in ex.map(fetch, missing):
                    feats_by_url[u] = feats
        for u, (_, ck) in jobs.items():
            if u not in feats_by_url:
                f = CACHE / (re.sub(r"[^\w.-]", "_", ck) + ".json")
                feats_by_url[u] = json.loads(f.read_text()).get("features") or [] if f.exists() else []
        grouped = [[] for _ in norm]
        seen_per_req = [set() for _ in norm]
        for idx, u in order:
            for f in feats_by_url.get(u) or []:
                # タイル境界をまたぐ同一図形の重複収録を束ねる (属性が違えば別件として残る)
                sig = json.dumps(f, sort_keys=True, ensure_ascii=False)
                if sig not in seen_per_req[idx]:
                    seen_per_req[idx].add(sig)
                    grouped[idx].append(f)
        return grouped

    def tiles(self, api, z, around=0, **params):
        return self.multi([(api, z, around, params)])[0]

    def here_all(self, apis, z=15):
        """点包含の一括版。リクエスト数はAPI数と同じ (各1タイル) だが並列で壁時間を短縮。"""
        grouped = self.multi([(a, z, None, {}) for a in apis])
        return {a: [f["properties"] for f in feats if contains(f["geometry"], self.lon, self.lat)]
                for a, feats in zip(apis, grouped)}

    def here(self, api, z=15):
        return self.here_all([api], z)[api]

    def near_all(self, specs):
        """近傍点の一括版。z14＋半径カバーで従来 z15×9枚×N を削減する。specs: [(api, radius, params)]"""
        grouped = self.multi([(a, 14, ("cover", r), p) for a, r, p in specs])
        out = {}
        for (a, r, _), feats in zip(specs, grouped):
            items = []
            for f in feats:
                la, lo = point_of(f["geometry"])
                d = dist_m(self.lat, self.lon, la, lo)
                if d <= r:
                    items.append((round(d), f["properties"]))
            out[a] = sorted(items, key=lambda t: t[0])
        return out

    def near(self, api, z=15, radius=1000, **params):
        if z == 15 and "around" not in params:
            # 旧呼び出し (z15周辺9枚) は z14カバーに寄せる
            return self.near_all([(api, radius, params)])[api]
        around = params.pop("around", 1)
        out = []
        for f in self.tiles(api, z, around=around, **params):
            la, lo = point_of(f["geometry"])
            d = dist_m(self.lat, self.lon, la, lo)
            if d <= radius:
                out.append((round(d), f["properties"]))
        return sorted(out, key=lambda t: t[0])


def num(s):
    m = re.sub(r"[^\d.]", "", str(s or ""))
    return float(m) if m else None


def quarters_back(n):
    t = datetime.date.today()
    y, q = t.year, (t.month - 1) // 3 + 1
    y, q = (y, q - 1) if q > 1 else (y - 1, 4)  # 直近の完了四半期
    cur = f"{y}{q}"
    for _ in range(n - 1):
        y, q = (y, q - 1) if q > 1 else (y - 1, 4)
    return f"{y}{q}", cur


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("address")
    ap.add_argument("--type", choices=LAND_TYPE, required=True)
    ap.add_argument("--price", type=float, help="候補物件の価格(万円)")
    ap.add_argument("--area", type=float, help="専有/土地面積(㎡)")
    ap.add_argument("--built", type=int, help="築年(西暦)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()

    lat, lon, title = geocode(a.address)
    L = Lib(api_key(), lat, lon)
    P = print
    P(f"# {a.address}\n\n- ジオコーディング: {title} ({lat:.5f}, {lon:.5f})\n- 種別: {LAND_LABEL[a.type]}\n")

    P("## 災害リスク")
    _here = L.here_all(["XKT026", "XKT027", "XKT028", "XKT029", "XKT025", "XKT020", "XKT016", "XKT022", "XKT021",
                        "XKT002", "XKT014", "XKT023", "XKT004", "XKT005", "XKT013"])
    def hazard(label, api, fmt, z=15):
        hits = _here.get(api, [])
        P(f"- {label}: " + (" / ".join(sorted({fmt(p) for p in hits})) if hits else "該当なし"))
    hazard("洪水浸水想定(最大規模)", "XKT026", lambda p: f"{p.get('A31a_202')} 浸水深ランク{p.get('A31a_205')}")
    hazard("高潮浸水想定", "XKT027", lambda p: str(p.get("A49_003")))
    hazard("津波浸水想定", "XKT028", lambda p: str(p.get("A40_003")))
    hazard("土砂災害警戒区域", "XKT029", lambda p: f"{p.get('A33_005')} 現象{p.get('A33_001')} 区分{p.get('A33_002')}")
    hazard("液状化傾向", "XKT025", lambda p: f"レベル{p.get('liquefaction_tendency_level')} {p.get('note')} ({p.get('topographic_classification_name_ja')})")
    hazard("大規模盛土造成地", "XKT020", lambda p: f"{p.get('embankment_classification')} {p.get('embankment_number')}")
    hazard("災害危険区域", "XKT016", lambda p: json.dumps(p, ensure_ascii=False)[:120])
    hazard("急傾斜地崩壊危険区域", "XKT022", lambda p: json.dumps(p, ensure_ascii=False)[:120])
    hazard("地すべり防止区域", "XKT021", lambda p: json.dumps(p, ensure_ascii=False)[:120])

    P("\n## 建築条件")
    hazard("用途地域", "XKT002", lambda p: f"{p.get('use_area_ja')} 容積{p.get('u_floor_area_ratio_ja')} 建蔽{p.get('u_building_coverage_ratio_ja')}")
    hazard("防火・準防火", "XKT014", lambda p: str(p.get("fire_prevention_ja")))
    hazard("地区計画", "XKT023", lambda p: f"{p.get('plan_name')} ({p.get('plan_type_ja')})")
    roads = [(round(line_dist_m(f["geometry"], lat, lon)), f["properties"]) for f in L.tiles("XKT030", 15)]
    roads = sorted(r for r in roads if r[0] <= 50)
    P("- 都市計画道路(50m以内): " + (" / ".join(f"{p.get('planning_road_ja')} 約{d}m" for d, p in roads[:3]) if roads else "なし"))

    P("\n## 生活環境")
    hazard("小学校区", "XKT004", lambda p: str(p.get("A27_004_ja")))
    hazard("中学校区", "XKT005", lambda p: str(p.get("A32_004_ja")))
    _near = L.near_all([("XKT015", 1500, {}), ("XKT007", 1000, {}), ("XKT010", 1000, {}), ("XKT017", 1000, {})])
    st = _near.get("XKT015", [])
    seen, rows = set(), []
    for d, p in st:
        if p.get("S12_001_ja") in seen:
            continue
        seen.add(p.get("S12_001_ja"))
        pax = next((p[k] for k in (f"S12_{9+4*i:03d}" for i in range(12, -1, -1)) if isinstance(p.get(k), int) and p[k] > 0), None)  # 2023→2011 の順で最新値
        rows.append(f"{p.get('S12_001_ja')}({p.get('S12_003_ja')}) {d}m 乗降{pax:,}人/日" if isinstance(pax, int) else f"{p.get('S12_001_ja')}({p.get('S12_003_ja')}) {d}m")
    P("- 駅(1.5km以内): " + (" / ".join(rows[:4]) if rows else "なし"))
    for label, api, name in [("保育園・幼稚園", "XKT007", "preSchoolName_ja"), ("医療機関", "XKT010", "P04_002_ja"), ("図書館", "XKT017", "P27_005_ja")]:
        fs = _near.get(api, [])
        P(f"- {label}(1km以内): {len(fs)}件" + (" 最寄 " + ", ".join(f"{p.get(name)} {d}m" for d, p in fs[:3]) if fs else ""))
    pop = _here.get("XKT013", [])
    if pop:
        p = pop[0]
        yrs = sorted(k for k in p if re.fullmatch(r"PTN_\d{4}", k))
        P("- 将来推計人口(250mメッシュ): " + ", ".join(f"{k[4:]}年 {int(p[k]):,}" for k in yrs if p.get(k) is not None))
        old = [k for k in yrs if p.get(f"RTC_{k[4:]}") is not None]
        if old:
            P("  - 65歳以上比率: " + ", ".join(f"{k[4:]}年 {float(p[f'RTC_{k[4:]}']):.0%}" for k in old))
    else:
        P("- 将来推計人口: データなし")

    P("\n## 価格の目安 (近隣1km・直近8四半期・" + LAND_LABEL[a.type] + ")")
    frm, to = quarters_back(8)
    # XPT001は代表点集約のため距離絞り込み不可。z14カバー(1〜4枚)で取得する
    deals = L.near_all([("XPT001", 1500, {"from": frm, "to": to, "landTypeCode": LAND_TYPE[a.type]})]).get("XPT001", [])
    unit = []
    for d, p in deals:
        tot, ar = num(p.get("u_transaction_price_total_ja")), num(p.get("u_area_ja"))
        if tot and ar:
            unit.append((tot * (10000 if "万" in str(p.get("u_transaction_price_total_ja")) else 1) / ar, d, p))
    if not unit:
        P("- 該当取引なし")
    else:
        vals = sorted(u for u, _, _ in unit)
        med = statistics.median(vals)
        P(f"- 件数 {len(vals)} / ㎡単価 中央値 {med/10000:.1f}万円 (下位25% {vals[len(vals)//4]/10000:.1f} / 上位25% {vals[3*len(vals)//4]/10000:.1f})")
        if a.built:
            same = [u for u, _, p in unit if (num(p.get("u_construction_year_ja")) or 0) and abs(num(p.get("u_construction_year_ja")) - a.built) <= 5]
            if same:
                P(f"- 築年±5年に絞ると 件数 {len(same)} / 中央値 {statistics.median(same)/10000:.1f}万円/㎡")
        if a.price and a.area:
            mine = a.price * 10000 / a.area
            pct = sum(v < mine for v in vals) / len(vals)
            P(f"- 候補物件 {mine/10000:.1f}万円/㎡ → 近隣取引の安い方から{pct:.0%}の位置 (中央値比 {mine/med-1:+.0%})")
        P("- 直近の例(地区単位): " + " / ".join(f"{p.get('district_name_ja')} {p.get('point_in_time_name_ja')} {p.get('u_transaction_price_total_ja')} {p.get('u_area_ja')} 築{p.get('u_construction_year_ja')}" for _, d, p in sorted(unit, key=lambda t: (str(t[2].get("point_in_time_name_ja")), -t[1]), reverse=True)[:5]))


def selftest():
    # タイル座標 (東京駅 z15) と点包含の検算
    assert tile(35.681236, 139.767125, 15) == (29105, 12903), tile(35.681236, 139.767125, 15)
    sq = {"type": "Polygon", "coordinates": [[[0, 0], [2, 0], [2, 2], [0, 2]], [[0.5, 0.5], [1.5, 0.5], [1.5, 1.5], [0.5, 1.5]]]}
    assert contains(sq, 0.2, 0.2) and not contains(sq, 1, 1) and not contains(sq, 3, 3)
    ln = {"type": "LineString", "coordinates": [[139.76, 35.68], [139.78, 35.68]]}
    assert abs(line_dist_m(ln, 35.681, 139.77) - 111) < 5, line_dist_m(ln, 35.681, 139.77)
    assert quarters_back(8)[0] < quarters_back(8)[1]
    # 半径カバー: 中心付近は1枚、境界付近でも少数枚に収まる
    c1 = cover_tiles(35.681236, 139.767125, 14, 1000)
    assert 1 <= len(c1) <= 4, c1
    c0 = cover_tiles(35.681236, 139.767125, 15, 0)
    assert c0 == [tile(35.681236, 139.767125, 15)], c0
    print("selftest ok")


if __name__ == "__main__":
    main()
