"""Erzeugt app/src/main/assets/regions/ch_municipalities.bin aus swissBOUNDARIES3D (GeoPackage).

Aufruf: python -I tools/build_municipalities.py <swissBOUNDARIES3D.gpkg> [ausgabe.bin] [toleranz_m]

Quelle: swisstopo swissBOUNDARIES3D (LV95, EPSG:2056). Nur Python-stdlib.

Binaerformat "PMUN" v1 (alles little endian, Varints = LEB128, Zigzag fuer vorzeichenbehaftete):
  magic "PMUN", u8 version=1, varint anzahl
  je Gemeinde: u8 nameLen + UTF-8 Name, 2 Byte ASCII Kuerzel (Kanton bzw. Land),
               varint bbox minE, minN, maxE-minE, maxN-minN (LV95 Meter),
               varint Polygonzahl; je Polygon: varint Ringzahl (erster Ring = Aussenring, Rest = Loecher);
               je Ring: varint Punktzahl (ohne Schlusspunkt), erster Punkt zigzag-absolut, Rest zigzag-Delta.
"""
import sqlite3, struct, sys

KANTONE = ["ZH", "BE", "LU", "UR", "SZ", "OW", "NW", "GL", "ZG", "FR", "SO", "BS", "BL", "SH", "AR", "AI",
           "SG", "GR", "AG", "TG", "TI", "VD", "VS", "NE", "GE", "JU"]
LAENDER = {"LI": "FL", "DE": "DE", "IT": "IT"}


def parse_multipolygon(blob):
    """GPKG-Header ueberspringen, WKB (Polygon/MultiPolygon, ggf. mit Z) lesen -> Liste Polygone -> Liste Ringe -> [(x,y)]."""
    flags = blob[3]
    env = {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[(flags >> 1) & 7]
    pos = 8 + env
    def geom(pos):
        bo = '<' if blob[pos] == 1 else '>'
        t = struct.unpack_from(bo + 'I', blob, pos + 1)[0]
        pos += 5
        base = t % 1000 if t < 4000 else t & 0xFF
        hasz = (t // 1000 in (1, 3)) or bool(t & 0x80000000)
        dim = 3 if hasz else 2
        if base == 6:
            n = struct.unpack_from(bo + 'I', blob, pos)[0]; pos += 4
            polys = []
            for _ in range(n):
                p, pos = geom(pos)
                polys += p
            return polys, pos
        if base == 3:
            nr = struct.unpack_from(bo + 'I', blob, pos)[0]; pos += 4
            rings = []
            for _ in range(nr):
                np_ = struct.unpack_from(bo + 'I', blob, pos)[0]; pos += 4
                vals = struct.unpack_from(bo + 'd' * (np_ * dim), blob, pos); pos += 8 * np_ * dim
                rings.append([(vals[i * dim], vals[i * dim + 1]) for i in range(np_)])
            return [rings], pos
        raise ValueError("WKB-Typ %d" % t)
    return geom(pos)[0]


def dp(pts, tol):
    """Douglas-Peucker (iterativ) fuer offenen Linienzug."""
    n = len(pts)
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    t2 = tol * tol
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        best, bi = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            if l2 == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / l2
                t = max(0.0, min(1.0, t))
                d = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d > best:
                best, bi = d, i
        if best > t2:
            keep[bi] = True
            stack.append((a, bi)); stack.append((bi, b))
    return [p for p, k in zip(pts, keep) if k]


def simplify_ring(ring, tol):
    pts = ring[:-1] if ring[0] == ring[-1] else ring
    # Ring an zwei weit entfernten Punkten teilen, damit der Start nicht willkuerlich fest bleibt
    far = max(range(len(pts)), key=lambda i: (pts[i][0] - pts[0][0]) ** 2 + (pts[i][1] - pts[0][1]) ** 2)
    a = dp(pts[:far + 1], tol)
    b = dp(pts[far:] + [pts[0]], tol)
    out = a[:-1] + b[:-1]
    out = [(round(x), round(y)) for x, y in out]
    ded = [out[0]]
    for p in out[1:]:
        if p != ded[-1]:
            ded.append(p)
    if len(ded) > 1 and ded[0] == ded[-1]:
        ded.pop()
    return ded if len(ded) >= 3 else None


def area2(r):
    return abs(sum(r[i][0] * r[(i + 1) % len(r)][1] - r[(i + 1) % len(r)][0] * r[i][1] for i in range(len(r))))


def varint(v, out):
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80); v >>= 7
    out.append(v)


def zz(v, out):
    varint((v << 1) ^ (v >> 63) if v < 0 else v << 1, out)


def main():
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/regions/ch_municipalities.bin"
    tol = float(sys.argv[3]) if len(sys.argv) > 3 else 30.0
    con = sqlite3.connect("file:" + src + "?mode=ro", uri=True)
    rows = con.execute("select name,kantonsnummer,icc,geom from tlm_hoheitsgebiet "
                       "where objektart='Gemeindegebiet' order by bfs_nummer").fetchall()
    out = bytearray(b"PMUN\x01")
    varint(len(rows), out)
    verts = 0
    for name, kt, icc, blob in rows:
        code = KANTONE[kt - 1] if icc == "CH" else LAENDER[icc]
        polys = []
        for rings in parse_multipolygon(blob):
            sr = [simplify_ring(r, tol) for r in rings]
            if sr[0] is None or area2(sr[0]) < 2 * 2000:  # winzige Aussenring-Splitter (< 2000 m2) weglassen
                continue
            polys.append([sr[0]] + [r for r in sr[1:] if r and area2(r) >= 2 * 2000])
        if not polys:  # Gemeinde nur aus Splittern: groesstes Polygon unveraendert grob behalten
            continue
        xs = [p[0] for poly in polys for p in poly[0]]; ys = [p[1] for poly in polys for p in poly[0]]
        nb = name.encode("utf-8")
        out.append(len(nb)); out += nb; out += code.encode("ascii")
        varint(min(xs), out); varint(min(ys), out); varint(max(xs) - min(xs), out); varint(max(ys) - min(ys), out)
        varint(len(polys), out)
        for poly in polys:
            varint(len(poly), out)
            for ring in poly:
                varint(len(ring), out); verts += len(ring)
                px = py = 0
                for i, (x, y) in enumerate(ring):
                    zz(x - px, out); zz(y - py, out); px, py = x, y
    with open(dst, "wb") as f:
        f.write(out)
    print("Gemeinden: %d, Stuetzpunkte: %d, Groesse: %.2f MB (Toleranz %.0f m)" % (len(rows), verts, len(out) / 1e6, tol))


main()
