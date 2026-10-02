#!/usr/bin/env python3
"""GalaxyCraft development harness: runs the patched Dolphin with SMG2 + the Syati module.

Never touches ~/.config/dolphin-emu or the user's screen: Dolphin gets its own user directory
(~/.local/share/galaxycraft-dev, seeded from tools/dolphin-dev/), runs on the headless platform,
and takes screenshots itself. The Wii Remote reads the FIFO <userdir>/Pipes/gxpad.

  gxdev.py start [--gui]       build nothing; boot syati/build/galaxycraft.json
  gxdev.py stop
  gxdev.py ctl "mbx; shot boot" [--wait S]   dev control channel (see DevControl.h)
  gxdev.py pad "PRESS A; RELEASE A"          raw Pipes commands
  gxdev.py press A [secs]                    press and release a pipe button
  gxdev.py stick X Y [secs]                  Nunchuk stick, -1..1 (Y up), then back to centre
  gxdev.py point X Y                         IR pointer, -1..1 (Y up)
"""
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
USER_DIR = Path.home() / ".local/share/galaxycraft-dev"
SEED = ROOT / "tools/dolphin-dev"
PAD = USER_DIR / "Pipes/gxpad"
PID_FILE = USER_DIR / "dolphin.pid"
LOG_FILE = USER_DIR / "dolphin.log"
DESCRIPTOR = ROOT / "syati/build/galaxycraft.json"
BINARIES = ROOT / "dolphin/build/Binaries"
CTL = Path("/dev/shm/galaxycraft_ctl")
CTL_OUT = Path("/dev/shm/galaxycraft_ctl.out")


def die(msg):
    print(f"gxdev: {msg}", file=sys.stderr)
    sys.exit(1)


def prepare_user_dir():
    config = USER_DIR / "Config"
    config.mkdir(parents=True, exist_ok=True)
    for ini in SEED.glob("*.ini"):
        shutil.copy(ini, config / ini.name)  # re-seeded every start: the repo is the truth
    PAD.parent.mkdir(exist_ok=True)
    if not PAD.exists():
        os.mkfifo(PAD)


def running_pid():
    try:
        pid = int(PID_FILE.read_text())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def start(gui):
    if running_pid():
        die("already running (gxdev.py stop first)")
    if not DESCRIPTOR.exists():
        die(f"missing {DESCRIPTOR}; run syati/build.sh")
    prepare_user_dir()
    exe = BINARIES / ("dolphin-emu" if gui else "dolphin-emu-nogui")
    cmd = [str(exe), "-u", str(USER_DIR), "-e", str(DESCRIPTOR)]
    if not gui:
        cmd[1:1] = ["-p", "headless"]
    env = dict(os.environ, GALAXYCRAFT="1")
    CTL.unlink(missing_ok=True)
    with open(LOG_FILE, "w") as log:
        proc = subprocess.Popen(cmd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)
    PID_FILE.write_text(str(proc.pid))
    time.sleep(2)
    if proc.poll() is not None:
        die(f"dolphin exited with {proc.returncode}; see {LOG_FILE}")
    print(f"started pid {proc.pid}, log {LOG_FILE}")


def stop():
    pid = running_pid()
    if not pid:
        print("not running")
        return
    os.kill(pid, signal.SIGTERM)
    for _ in range(50):
        if not running_pid():
            break
        time.sleep(0.1)
    else:
        os.kill(pid, signal.SIGKILL)
    PID_FILE.unlink(missing_ok=True)
    print("stopped")


def split_cmds(text):
    return [c.strip() for c in text.replace(";", "\n").splitlines() if c.strip()]


def ctl(text, wait):
    if not running_pid():
        die("not running")
    cmds = split_cmds(text)
    CTL_OUT.write_text("")
    tmp = CTL.with_suffix(".tmp")
    tmp.write_text("\n".join(cmds) + "\n")
    tmp.rename(CTL)  # atomic: Dolphin never reads half a command file
    # Memory commands answer in the field that consumes the file; shot/save/load answer later,
    # one "ok ..." line each, from Dolphin's host thread.
    host_jobs = sum(c.split()[0] in ("shot", "save", "load") for c in cmds)
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        if not CTL.exists() and CTL_OUT.read_text().count("ok ") >= host_jobs:
            time.sleep(0.1)
            out = CTL_OUT.read_text()
            break
        time.sleep(0.05)
    else:
        out = CTL_OUT.read_text() + "gxdev: timed out (is the game running?)\n"
    sys.stdout.write(out)
    shots = [c.split()[1] for c in cmds if c.startswith("shot ") and len(c.split()) == 2]
    for name in shots:
        for png in (USER_DIR / "ScreenShots").rglob(f"{name}.png"):
            print(f"screenshot: {png}")


def pad(text):
    try:
        fd = os.open(PAD, os.O_WRONLY | os.O_NONBLOCK)
    except OSError as e:
        die(f"cannot open {PAD} ({e.strerror}); is Dolphin running?")
    with os.fdopen(fd, "w") as f:
        for c in split_cmds(text):
            f.write(c + "\n")


def main(argv):
    if len(argv) < 2:
        die(__doc__)
    op, args = argv[1], argv[2:]
    if op == "start":
        start("--gui" in args)
    elif op == "stop":
        stop()
    elif op == "ctl" and args:
        wait = float(args[args.index("--wait") + 1]) if "--wait" in args else 5.0
        ctl(args[0], wait)
    elif op == "pad" and args:
        pad(args[0])
    elif op == "press" and args:
        pad(f"PRESS {args[0]}")
        time.sleep(float(args[1]) if len(args) > 1 else 0.15)
        pad(f"RELEASE {args[0]}")
    elif op == "stick" and len(args) >= 2:
        x, y = float(args[0]), float(args[1])
        pad(f"SET MAIN {(x + 1) / 2:.3f} {(1 - y) / 2:.3f}")
        time.sleep(float(args[2]) if len(args) > 2 else 0.5)
        pad("SET MAIN 0.5 0.5")
    elif op == "point" and len(args) == 2:
        x, y = float(args[0]), float(args[1])
        pad(f"SET C {(x + 1) / 2:.3f} {(1 - y) / 2:.3f}")
    else:
        die(__doc__)


if __name__ == "__main__":
    main(sys.argv)
