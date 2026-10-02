#!/usr/bin/env python3
"""Builds Steve for SMG2 into syati/build/ObjectData (loaded by Riivolution in place of Mario):
  Mario.arc       Mario.bdl replaced by Steve (Minecraft skin boxes on Mario's skeleton)
  MarioHandL.arc  MarioHandR.arc   Mario's gloves, emptied
  MarioFace.arc  MarioHair.arc  MarioCap.arc   his face (nose, eyes, moustache), hair and cap, emptied
Inputs come from this machine, never the repo: the game image ($GXC_GAME or the SMG2 .rvz in
~/Documents/Games/Dolphin Games), the skin (--skin, or Steve's from the Minecraft client jar), and
SuperBMD 2.5.0 under wine (downloaded into the toolchain if missing). Skips work if nothing changed.
  tools/steve/build.py [--skin PNG] [--out DIR]
"""
import argparse
import glob
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import rarc  # noqa: E402
import steve_model  # noqa: E402

TOOLCHAIN = os.environ.get("GXC_TOOLCHAIN", os.path.expanduser("~/.local/opt/gxc-toolchain"))
SUPERBMD_DIR = os.path.join(TOOLCHAIN, "SuperBMD")
SUPERBMD_URL = "https://github.com/RenolY2/SuperBMD/releases/download/v2.5.0/SuperBMD_2.5.0.zip"
SUPERBMD_SHA256 = "6f5e7ff25b9da61eda0ba3f4c9b5cb53b3702cdc5ca515c9e41c839f9398acd6"
MC_JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar")
MC_SKIN = "assets/minecraft/textures/entity/player/wide/steve.png"
DOLPHIN_TOOL = os.path.join(ROOT, "dolphin/build/Binaries/dolphin-tool")
# Archive -> (model inside it, what replaces it). Gloves, face, hair and cap are drawn on Mario's
# joints from their own archives, so Steve needs them gone.
MODELS = {"Mario": ("Mario.bdl", steve_model.steve),
          "MarioHandL": ("MarioHandL.bdl", steve_model.empty),
          "MarioHandR": ("MarioHandR.bdl", steve_model.empty),
          "MarioFace": ("MarioFace.bdl", steve_model.empty),
          "MarioHair": ("MarioHair.bdl", steve_model.empty),
          "MarioCap": ("MarioCap.bdl", steve_model.empty)}
TEX_HEADER = [{"Name": "steve", "Format": "RGB5A3", "AlphaSetting": 0, "WrapS": "ClampToEdge",
               "WrapT": "ClampToEdge", "PaletteFormat": "IA8", "MipMap": 0, "EdgeLOD": False,
               "BiasClamp": False, "MaxAniso": 0, "MinFilter": "Nearest", "MagFilter": "Nearest",
               "MinLOD": 0.0, "MaxLOD": 0.0, "LodBias": 0.0}]


def die(msg):
    sys.exit(f"steve/build.py: {msg}")


def superbmd():
    exe = os.path.join(SUPERBMD_DIR, "SuperBMD.exe")
    if not os.path.exists(exe):
        print(f"downloading SuperBMD into {SUPERBMD_DIR}")
        data = urllib.request.urlopen(SUPERBMD_URL, timeout=120).read()
        if hashlib.sha256(data).hexdigest() != SUPERBMD_SHA256:
            die("SuperBMD download does not match its SHA-256")
        zipfile.ZipFile(io.BytesIO(data)).extractall(SUPERBMD_DIR)
    return exe


def run_superbmd(exe, workdir, *args):
    win = lambda p: "Z:" + os.path.join(workdir, p).replace("/", "\\") if not p.startswith("-") else p
    env = dict(os.environ, WINEDEBUG="-all")
    out = subprocess.run(["wine", exe, *map(win, args)], cwd=workdir, env=env, capture_output=True, text=True)
    if out.returncode != 0 or "Exception" in out.stdout + out.stderr:
        die("SuperBMD failed:\n" + (out.stdout + out.stderr)[-2000:])


def game_image():
    game = os.environ.get("GXC_GAME") or next(iter(sorted(glob.glob(
        os.path.expanduser("~/Documents/Games/Dolphin Games/*.rvz")))), None)
    if not game or not os.path.isfile(game):
        die("no game image; set GXC_GAME")
    return game


def extract_arc(game, name, workdir):
    subprocess.run([DOLPHIN_TOOL, "extract", "-i", game, "-s", f"ObjectData/{name}.arc", "-o", workdir, "-q"],
                   check=True)
    path = glob.glob(os.path.join(workdir, "**", f"{name}.arc"), recursive=True)
    if not path:
        die(f"{name}.arc not found on the disc")
    with open(path[0], "rb") as f:
        return f.read()


def skin_png(path):
    if path:
        with open(path, "rb") as f:
            return f.read()
    with zipfile.ZipFile(MC_JAR) as jar:
        return jar.read(MC_SKIN)


def build_arc(exe, arc, model_name, make, skin, workdir):
    model = dict(rarc.list_files(arc))[model_name]
    stem = os.path.splitext(model_name)[0]
    with open(os.path.join(workdir, model_name), "wb") as f:
        f.write(model)
    run_superbmd(exe, workdir, model_name, f"{stem}_ref.dae")  # the skeleton, as SuperBMD sees it
    with open(os.path.join(workdir, f"{stem}_ref.dae")) as f:
        skeleton = steve_model.Skeleton(f.read())
    with open(os.path.join(workdir, "steve.png"), "wb") as f:
        f.write(skin)
    with open(os.path.join(workdir, f"{stem}.dae"), "w") as f:
        f.write(steve_model.collada(make(skeleton)))
    with open(os.path.join(workdir, "tex.json"), "w") as f:
        json.dump(TEX_HEADER, f)
    shutil.copy(os.path.join(HERE, "material.json"), os.path.join(workdir, "material.json"))
    os.remove(os.path.join(workdir, model_name))
    run_superbmd(exe, workdir, f"{stem}.dae", model_name, "-b", "-x", "tex.json", "-m", "material.json")
    with open(os.path.join(workdir, model_name), "rb") as f:
        return rarc.replace(arc, {model_name: f.read()})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--skin", help="64x64 Minecraft skin PNG (default: Steve)")
    ap.add_argument("--out", default=os.path.join(ROOT, "syati/build"))
    args = ap.parse_args()
    game, skin = game_image(), skin_png(args.skin)
    stamp = hashlib.sha256()
    for path in (__file__, steve_model.__file__, rarc.__file__, os.path.join(HERE, "material.json")):
        with open(path, "rb") as f:
            stamp.update(f.read())
    stamp.update(skin + game.encode() + str(os.path.getmtime(game)).encode())
    out_dir = os.path.join(args.out, "ObjectData")
    stamp_file = os.path.join(out_dir, ".steve-stamp")
    if os.path.exists(stamp_file) and open(stamp_file).read() == stamp.hexdigest():
        return
    exe = superbmd()
    os.makedirs(out_dir, exist_ok=True)
    for name, (model_name, make) in MODELS.items():
        with tempfile.TemporaryDirectory() as work:
            arc = build_arc(exe, extract_arc(game, name, work), model_name, make, skin, work)
        with open(os.path.join(out_dir, f"{name}.arc"), "wb") as f:
            f.write(arc)
        print(f"built {out_dir}/{name}.arc")
    with open(stamp_file, "w") as f:
        f.write(stamp.hexdigest())


if __name__ == "__main__":
    main()
