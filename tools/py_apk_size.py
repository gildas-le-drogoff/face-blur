#!/usr/bin/env python3
"""APK/AAB size analyzer. No deps (stdlib only). Optional apkanalyzer.

Usage:
  ./tools/apk_size.py [apk_path] [-n 30] [--no-apkanalyzer]

Categories every zip entry, prints: totals, category table, res breakdown,
lib/ABI breakdown, top-N files. If Android apkanalyzer is on PATH (or
ANDROID_HOME set), adds per-package DEX code-size ranking (the real answer to
"which library costs the most").
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

DEFAULT_APK = "app/build/intermediates/apk/debug/app-debug.apk"

KIB = 1024


def human(n: int) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < KIB:
            return f"{n:.1f}{unit}"
        n /= KIB
    return f"{n:.1f}TiB"


def category_of(name: str) -> str:
    """One bucket per entry. Keeps res/lib sub-detail for later grouping."""
    if name.endswith(".dex"):
        return "dex"
    if name == "resources.arsc":
        return "resources.arsc"
    if name.startswith("res/"):
        return "res"
    if name.startswith("assets/"):
        return "assets"
    if name.startswith("lib/"):
        return "lib"
    if name.startswith("META-INF/"):
        return "META-INF"
    if name.startswith("kotlin/"):
        return "kotlin-metadata"
    return "root-files"


def res_subkind(name: str) -> str:
    tail = name[4:]  # strip "res/"
    head = tail.split("/", 1)[0]  # e.g. drawable-xxhdpi-v4
    base = head.split("-")[0]  # e.g. drawable
    return f"res/{base}"


def lib_abi(name: str) -> str:
    return f"lib/{name.split('/')[1]}" if name.count("/") >= 2 else "lib/?"


def collect(zf: zipfile.ZipFile) -> list[tuple[str, int, int]]:
    out = []
    for info in zf.infolist():
        if info.is_dir():
            continue
        out.append((info.filename, info.compress_size, info.file_size))
    return out


def bucket(entries, key):
    comp, uncomp = defaultdict(int), defaultdict(int)
    for name, c, u in entries:
        k = key(name)
        comp[k] += c
        uncomp[k] += u
    return comp, uncomp


def print_table(title, comp, uncomp, top=99):
    rows = sorted(comp.items(), key=lambda kv: kv[1], reverse=True)[:top]
    print(f"\n=== {title} ===")
    print(f"{'category':<28}{'download':>12}{'installed':>12}{'%':>7}")
    for k, c in rows:
        pct = 100 * c / sum(comp.values())
        print(f"{k:<28}{human(c):>12}{human(uncomp[k]):>12}{pct:>6.1f}%")


def top_files(entries, n):
    rows = sorted(entries, key=lambda e: e[1], reverse=True)[:n]
    print(f"\n=== Top {n} files (download size) ===")
    print(f"{'size':>10}  path")
    for name, c, _ in rows:
        print(f"{human(c):>10}  {name}")


def find_apkanalyzer() -> str | None:
    ap = shutil.which("apkanalyzer")
    if ap:
        return ap
    ah = Path("/opt/android-sdk")
    cand = ah / "cmdline-tools/latest/bin/apkanalyzer"
    return str(cand) if cand.exists() else None


def dex_per_package(apk: Path, tool: str, n: int) -> None:
    """apkanalyzer dex packages: attribute each CLASS (C line) by its first
    3 package segments -> no double-count vs cumulative P lines."""
    try:
        out = subprocess.run(
            [tool, "dex", "packages", str(apk)],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        print("\n[apkanalyzer not found - skipping DEX package breakdown]")
        return
    if out.returncode != 0:
        print(f"\n[apkanalyzer failed: {out.stderr.strip()[:120]}]")
        return

    per = defaultdict(int)
    for line in out.stdout.splitlines():
        parts = line.split()
        # C d <defMethods> <refMethods> <definedSize> <class.fqn>
        if len(parts) < 6 or parts[0] != "C":
            continue
        try:
            size_i = int(parts[4])
        except ValueError:
            continue
        segs = parts[5].split(".")
        key = ".".join(segs[:3]) if len(segs) >= 3 else parts[5]
        per[key] += size_i

    if not per:
        print("\n[no DEX package data parsed]")
        return
    rows = sorted(per.items(), key=lambda kv: kv[1], reverse=True)[:n]
    total = sum(per.values())
    print(f"\n=== DEX defined code size by library (top {n}) ===")
    print(f"{'package (3 segments)':<42}{'defined':>12}{'%':>7}")
    for k, s in rows:
        print(f"{k:<42}{human(s):>12}{100 * s / total:>6.1f}%")


def main() -> int:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("apk", nargs="?", default=DEFAULT_APK)
    p.add_argument("-n", type=int, default=30, help="top-N files/packages")
    p.add_argument("--no-apkanalyzer", action="store_true")
    args = p.parse_args()

    apk = Path(args.apk)
    if not apk.exists():
        print(
            f"APK not found: {apk}\nBuild first: ./gradlew assembleDebug",
            file=sys.stderr,
        )
        return 1

    with zipfile.ZipFile(apk) as zf:
        entries = collect(zf)

    total_dl = sum(c for _, c, _ in entries)
    total_ins = sum(u for _, _, u in entries)
    print(f"APK: {apk}")
    print(f"Download (compressed):   {human(total_dl)}")
    print(f"Installed (uncompressed):{human(total_ins)}")

    comp, uncomp = bucket(entries, category_of)
    print_table("By category (download size)", comp, uncomp)

    res_entries = [e for e in entries if e[0].startswith("res/")]
    if res_entries:
        rc, ru = bucket(res_entries, res_subkind)
        print_table("res/ breakdown", rc, ru, top=15)

    lib_entries = [e for e in entries if e[0].startswith("lib/")]
    if lib_entries:
        lc, lu = bucket(lib_entries, lib_abi)
        print_table("lib/ by ABI", lc, lu)

    top_files(entries, args.n)

    if not args.no_apkanalyzer:
        tool = find_apkanalyzer()
        if tool:
            dex_per_package(apk, tool, args.n)
        else:
            print("\n[apkanalyzer not found - install cmdline-tools for DEX breakdown]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
