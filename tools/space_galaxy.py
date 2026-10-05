#!/usr/bin/env python3
"""Builds GalaxyCraft's own galaxy, GalaxyCraftSpace: empty space under SMG2's starry sky, where
the player's planets are the only ground. Made from a galaxy on the disc (RedBlueExGalaxy) with
every object taken out but its sky and one start for Mario, so no game file ships in the repo.

    space_galaxy.py --out syati/build    (writes <out>/StageData/GalaxyCraftSpace/*.arc)
"""
import argparse
import glob
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bcsv  # noqa: E402
import rarc  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOLPHIN_TOOL = os.path.join(ROOT, "dolphin/build/Binaries/dolphin-tool")
NAME = "GalaxyCraftSpace"
BASE = "RedBlueExGalaxy"
KEEP_OBJECTS = {"GalaxySky"}  # the starry sky; everything else in the base galaxy goes


def game_image():
    game = os.environ.get("GXC_GAME") or next(iter(sorted(glob.glob(
        os.path.expanduser("~/Documents/Games/Dolphin Games/*.rvz")))), None)
    if not game or not os.path.isfile(game):
        sys.exit("space_galaxy: no game image; set GXC_GAME")
    return game


def extract(game, path, workdir):
    subprocess.run([DOLPHIN_TOOL, "extract", "-i", game, "-s", path, "-o", workdir, "-q"], check=True)
    found = glob.glob(os.path.join(workdir, "**", os.path.basename(path)), recursive=True)
    if not found:
        sys.exit(f"space_galaxy: {path} not found on the disc")
    with open(found[0], "rb") as f:
        return f.read()


def table(blob, keep=lambda row: False, edit=None):
    """The same table with only the rows keep() accepts, each passed through edit()."""
    fields, size, rows = bcsv.read(blob)
    rows = [edit(dict(r)) if edit else r for r in rows if keep(r)]
    return bcsv.write(fields, size, rows)


def map_arc(arc):
    files = {}
    for path, blob in rarc.list_paths(arc):
        if "/jmp/" not in path or path.endswith("/StageInfo"):
            continue  # cameras and the stage's kind stay
        if path.endswith("Placement/Common/ObjInfo"):
            files[path] = table(blob, lambda r: r["name"] in KEEP_OBJECTS)
        elif path.endswith("Start/Common/StartInfo"):
            # Mario's one start, at the origin; the mod moves him onto his planet once linked.
            def at_origin(r):
                r.update(pos_x=0.0, pos_y=0.0, pos_z=0.0, dir_y=0.0)
                return r
            files[path] = table(blob, lambda r: r["MarioNo"] == 0, at_origin)
        else:
            files[path] = table(blob)
    return rarc.replace_paths(arc, files)


def scenario_arc(arc):
    files = {}
    for path, blob in rarc.list_paths(arc):
        name = os.path.basename(path)
        if name == "ZoneList.bcsv":
            files[path] = table(blob, lambda r: True, lambda r: {**r, "ZoneName": NAME})
        elif name == "ScenarioData.bcsv":
            # One scenario, no star; the column named after the zone (its layers) is renamed too.
            fields, size, rows = bcsv.read(blob)
            zone_col = "0x%08x" % bcsv.jhash(BASE)
            new_col = "0x%08x" % bcsv.jhash(NAME)
            fields = [(new_col if f[0] == zone_col else f[0],) + tuple(f[1:]) for f in fields]
            row = dict(rows[0])
            row[new_col] = 1  # common layer only
            row.pop(zone_col, None)
            row.update(ScenarioName="GalaxyCraft", PowerStarId=0, Comet="", CometLimitTimer=0)
            files[path] = bcsv.write(fields, size, [row])
    return rarc.replace_paths(arc, files)


def use_resource_arc(arc):
    # Only the common resources (the sky's); the scenarios' (the base galaxy's objects) go.
    return rarc.replace_paths(arc, {p: table(b) for p, b in rarc.list_paths(arc) if "scenario" in p})


def light_arc(arc):
    path = next(p for p, _ in rarc.list_paths(arc))
    return rarc.replace_paths(arc, {}, {path: f"{NAME}Light.bcsv"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    out = os.path.join(ap.parse_args().out, "StageData", NAME)
    os.makedirs(out, exist_ok=True)
    game = game_image()
    build = {"Map": map_arc, "Scenario": scenario_arc, "UseResource": use_resource_arc,
             "Light": light_arc, "Design": lambda a: a}
    with tempfile.TemporaryDirectory() as work:
        for part, make in build.items():
            arc = extract(game, f"StageData/{BASE}/{BASE}{part}.arc", work)
            with open(os.path.join(out, f"{NAME}{part}.arc"), "wb") as f:
                f.write(make(rarc.yaz0_decompress(arc)))
    print(f"space_galaxy: {out}")


if __name__ == "__main__":
    main()
