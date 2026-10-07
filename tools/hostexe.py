"""What the build scripts need from the system they run on, the same on Linux and Windows:
running a Windows-only tool (SuperBMD: directly on Windows, with wine on Linux), the patched
DolphinTool, and the default game image."""
import glob
import os
import subprocess

WINDOWS = os.name == "nt"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def windows_path(path):
    """A path as a Windows program sees it: itself on Windows, wine's Z: drive on Linux."""
    path = os.path.abspath(path)
    return path if WINDOWS else "Z:" + path.replace("/", "\\")


def run_with_wine(exe, args, **kw):
    """Runs a Windows program: directly on Windows, with wine on Linux (paths in args as
    windows_path() gives them)."""
    if WINDOWS:
        return subprocess.run([exe, *args], **kw)
    kw["env"] = dict(kw.get("env") or os.environ, WINEDEBUG="-all")
    return subprocess.run(["wine", exe, *args], **kw)


def dolphin_tool():
    """The patched Dolphin's DolphinTool (dolphin/build.sh on Linux; the CMake build or the
    folder CI makes on Windows)."""
    if not WINDOWS:
        return os.path.join(ROOT, "dolphin/build/Binaries/dolphin-tool")
    candidates = [os.path.join(ROOT, p) for p in (
        "dolphin/build/Binaries/DolphinTool.exe", "dolphin/build-win/Binaries/DolphinTool.exe",
        "dist/dolphin-win32-x64/DolphinTool.exe")]
    return next((c for c in candidates if os.path.exists(c)), candidates[0])


def default_game():
    """$GXC_GAME, or the first SMG2 .rvz in ~/Documents/Games/Dolphin Games; None if neither."""
    if os.environ.get("GXC_GAME"):
        return os.environ["GXC_GAME"]
    found = sorted(glob.glob(os.path.expanduser("~/Documents/Games/Dolphin Games/*.rvz")))
    return found[0] if found else None
