#!/usr/bin/env python3
"""Pre/post-flight check for a staging -> production promotion.

Compares every non-Git-tracked artifact named in manifest.yml. Run it BEFORE a promotion to see what still
needs copying, and AFTER the restart to prove production ended up where it should be.

    python deploy/check_deploy.py              # report only
    python deploy/check_deploy.py --fix        # copy the 'sync' entries staging -> production
    python deploy/check_deploy.py --json       # machine-readable

Comparison is line-ending insensitive, and YAML files are compared by parsed VALUE, so cosmetic reflow from
Bukkit's config saver (inline lists expanded, ints quoted, comments dropped) is never reported as a drift.
Exit code is non-zero if anything needs attention, so this can gate a deploy script.
"""
from __future__ import annotations
import argparse, fnmatch, json, os, shutil, sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: pip install pyyaml")

ROOT = Path(__file__).resolve().parent
OK, DIFF, MISSING, INFO = "OK", "DIFF", "MISSING", "INFO"


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")


def same(a: Path, b: Path) -> bool:
    """YAML by parsed value where possible; otherwise text, ignoring line endings."""
    if a.suffix in (".yml", ".yaml"):
        try:
            return yaml.safe_load(read(a)) == yaml.safe_load(read(b))
        except yaml.YAMLError:
            pass  # malformed YAML: fall through to a text compare rather than crashing the check
    return read(a) == read(b)


def props(p: Path) -> dict[str, str]:
    out = {}
    for line in read(p).splitlines():
        if line.strip() and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            out[k.strip()] = v.strip()
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix", action="store_true", help="copy 'sync' entries staging -> production")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--manifest", default=str(ROOT / "manifest.yml"))
    args = ap.parse_args()

    man = yaml.safe_load(Path(args.manifest).read_text(encoding="utf-8"))
    stag, prod = Path(man["servers"]["staging"]), Path(man["servers"]["production"])
    rows: list[tuple[str, str, str]] = []

    # --- files that must match -------------------------------------------------
    for entry in man.get("sync", []):
        rel = entry["path"]
        s, p = stag / rel, prod / rel
        note = f" ({entry['note']})" if entry.get("note") else ""
        if not s.exists():
            rows.append((MISSING, rel, "absent on STAGING - manifest may be stale" + note))
        elif not p.exists():
            rows.append((MISSING, rel, "absent on PRODUCTION" + note))
            if args.fix:
                p.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(s, p)
                rows[-1] = (INFO, rel, "COPIED to production" + note)
        elif not same(s, p):
            rows.append((DIFF, rel, "differs from staging" + note))
            if args.fix:
                shutil.copyfile(s, p)
                rows[-1] = (INFO, rel, "COPIED to production" + note)
        else:
            rows.append((OK, rel, "matches staging"))

    # --- files that must differ (and keep production's own values) --------------
    for entry in man.get("env", []):
        rel = entry["path"]
        s, p = stag / rel, prod / rel
        if not p.exists():
            rows.append((MISSING, rel, "absent on PRODUCTION"))
            continue
        if s.exists() and same(s, p):
            rows.append((DIFF, rel, "IDENTICAL to staging - environment file was likely overwritten"))
        bad = []
        for key, want in (entry.get("assert") or {}).items():
            got = props(p).get(key)
            if got != str(want):
                bad.append(f"{key}={got!r} expected {want!r}")
        rows.append((DIFF, rel, "; ".join(bad)) if bad else (OK, rel, "production values intact"))

    # --- required plugin jars ---------------------------------------------------
    have = {f.name for f in (prod / "plugins").glob("*.jar")}
    for jar in man.get("plugins", []):
        rows.append((OK, f"plugins/{jar}", "present") if jar in have
                    else (MISSING, f"plugins/{jar}", "NOT INSTALLED on production"))
    smp = [j for j in have if j.lower().startswith("smpcore")]
    rows.append((OK, "plugins/SMPCore*.jar", f"exactly one ({smp[0]})") if len(smp) == 1
                else (DIFF, "plugins/SMPCore*.jar", f"expected exactly 1, found {len(smp)}: {sorted(smp)}"))

    # --- jars whose version lives inside the file, not in its name --------------
    import zipfile
    for entry in man.get("jar_versions", []):
        rel, want = entry["path"], str(entry["contains"])
        p_jar = prod / rel
        if not p_jar.exists():
            rows.append((MISSING, rel, "NOT INSTALLED on production"))
            continue
        try:
            with zipfile.ZipFile(p_jar) as zf:
                blob = zf.read(entry["entry"]).decode("utf-8", "replace")
            ok = want in blob
        except Exception as exc:
            rows.append((DIFF, rel, f"could not read {entry['entry']}: {exc}"))
            continue
        rows.append((OK, rel, f"build {want}") if ok
                    else (DIFF, rel, f"build is NOT {want} - outdated runtime"))

    # --- guard: nothing in never_touch was clobbered by a sync entry ------------
    synced = [e["path"] for e in man.get("sync", [])]
    for pattern in man.get("never_touch", []):
        for s in synced:
            if fnmatch.fnmatch(s, pattern):
                rows.append((DIFF, pattern, f"manifest would sync protected path {s!r}"))

    if args.json:
        print(json.dumps([{"status": a, "path": b, "detail": c} for a, b, c in rows], indent=2))
    else:
        width = max(len(r[1]) for r in rows)
        for status, path, detail in rows:
            mark = {OK: "  ok  ", DIFF: " DIFF ", MISSING: " MISS ", INFO: " FIXED"}[status]
            print(f"[{mark}] {path:<{width}}  {detail}")
        bad = [r for r in rows if r[0] in (DIFF, MISSING)]
        print(f"\n{len(rows) - len(bad)}/{len(rows)} checks passed."
              + (f"  {len(bad)} need attention." if bad else "  Production matches the manifest."))
    return 1 if any(r[0] in (DIFF, MISSING) for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
