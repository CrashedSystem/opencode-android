#!/usr/bin/env python3
"""Keep the Android port in step with the OpenCode iOS app.

The iOS checkout lives in .upstream/opencode-ios (cloned on first use).
docs/port/sync.json records the upstream commit the port is synced to and
classifies every upstream file as port, review, or ignore. A Kotlin file
claims the iOS file it mirrors with @file:PortedFrom("<ios path>", "<commit>"),
where the commit is the upstream revision that file was last synced against.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "docs/port/sync.json"
UPSTREAM = ROOT / ".upstream/opencode-ios"
PLANS = ROOT / "docs/port/sync"
SOURCES = ROOT / "app/src"
MARKER = re.compile(r'@file:PortedFrom\(\s*"([^"]+)"\s*,\s*"([0-9a-f]{7,40})"\s*\)')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="fetch upstream and list iOS changes the port has not absorbed")
    commands.add_parser("check", help="fail unless every upstream file is ported, deferred, reviewed, or ignored")
    commands.add_parser("progress", help="ported, deferred, and missing counts weighted by Swift lines")
    plan = commands.add_parser("plan", help="write a per-commit porting worklist to docs/port/sync/")
    plan.add_argument("rev", nargs="?", help="upstream revision to plan up to (default: origin/<branch>)")
    advance = commands.add_parser("advance", help="record REV as synced once every changed file is ported or deferred")
    advance.add_argument("rev")
    args = parser.parse_args()

    config = json.loads(CONFIG.read_text())
    ensure_upstream(config, fetch=args.command in ("status", "plan"))
    {"status": status, "check": check, "progress": progress, "plan": plan_worklist, "advance": advance_sync}[args.command](config, args)


def status(config, _args):
    synced = config["upstream"]["commit"]
    head = resolve(f"origin/{config['upstream']['branch']}")
    print(f"synced   {describe(synced)}")
    print(f"upstream {describe(head)}")
    commits = git("rev-list", "--reverse", f"{synced}..{head}").split()
    if not commits:
        print("\nThe port is synced with upstream.")
        return
    print(f"\n{len(commits)} upstream commit(s) not yet ported:")
    for commit in commits:
        print(f"  {describe(commit)}")
    report_changes(config, changed_files(synced, head))
    print("\nRun scripts/ios-sync.py plan to write the porting worklist.")


def report_changes(config, changes):
    claims = marker_claims(config)
    groups = {"port": [], "review": []}
    for path, kind in changes:
        category = classify(path, config)
        if category in groups:
            groups[category].append((path, kind))
    if groups["port"]:
        print("\nPort these files:")
        for path, kind in groups["port"]:
            print(f"  {kind} {path} -> {targets(path, claims, config, kind)}")
    if groups["review"]:
        print("\nReview these for behavior or spec changes:")
        for path, kind in groups["review"]:
            print(f"  {kind} {path}")


def check(config, _args):
    synced = config["upstream"]["commit"]
    problems = gate(config, synced)
    for title, items in problems.items():
        if items:
            print(f"{title} ({len(items)}):")
            for item in items:
                print(f"  {item}")
    failures = sum(len(items) for items in problems.values())
    if failures:
        print(f"\nios-sync check failed with {failures} problem(s) at {synced[:7]}.")
        sys.exit(1)
    print(f"ios-sync check passed at {synced[:7]}.")


def gate(config, synced):
    files = upstream_files(synced)
    claims = marker_claims(config)
    problems = {
        "Upstream files with no category in sync.json": [],
        "Port files neither ported nor deferred": [],
        "Port files both ported and deferred": [],
        "Ports that claim a file missing upstream": [],
        "Ports older than the synced commit (the iOS file changed since)": [],
        "Claimed Android files that do not exist": [],
    }
    for path in files:
        category = classify(path, config)
        if category is None:
            problems["Upstream files with no category in sync.json"].append(path)
        elif category == "port":
            deferred = deferral(path, config)
            if path in claims and deferred:
                problems["Port files both ported and deferred"].append(path)
            elif path not in claims and not deferred:
                problems["Port files neither ported nor deferred"].append(path)
    present = set(files)
    for path, ports in claims.items():
        if path not in present:
            problems["Ports that claim a file missing upstream"].extend(f"{android} claims {path}" for android, _ in ports)
            continue
        for android, commit in ports:
            if path in changed_paths(commit, synced):
                problems["Ports older than the synced commit (the iOS file changed since)"].append(f"{android} synced {path} at {commit[:7]}")
            if not (ROOT / android).exists():
                problems["Claimed Android files that do not exist"].append(android)
    return problems


def progress(config, _args):
    synced = config["upstream"]["commit"]
    claims = marker_claims(config)
    totals = {"ported": [0, 0], "deferred": [0, 0], "missing": [0, 0]}
    ported = []
    missing = []
    for path in upstream_files(synced):
        if classify(path, config) != "port":
            continue
        lines = swift_lines(synced, path)
        state = "ported" if path in claims else "deferred" if deferral(path, config) else "missing"
        totals[state][0] += 1
        totals[state][1] += lines
        if state == "ported":
            ported.append((path, ", ".join(android for android, _ in claims[path])))
        if state == "missing":
            missing.append((lines, path))
    all_lines = sum(lines for _, lines in totals.values()) or 1
    for state, (count, lines) in totals.items():
        print(f"{state:9} {count:4} files {lines:7} Swift lines {100 * lines / all_lines:5.1f}%")
    if ported:
        print("\nPorted:")
        for path, android in ported:
            print(f"  {path} -> {android}")
    if missing:
        print("\nMissing, largest first:")
        for lines, path in sorted(missing, reverse=True):
            print(f"  {lines:6} {path}")


def plan_worklist(config, args):
    synced = config["upstream"]["commit"]
    head = resolve(args.rev or f"origin/{config['upstream']['branch']}")
    commits = git("rev-list", "--reverse", f"{synced}..{head}").split()
    if not commits:
        print("Nothing to plan, the port is synced.")
        return
    claims = marker_claims(config)
    out = [
        f"# Port upstream {synced[:7]}..{head[:7]}",
        "",
        f"{len(commits)} commit(s). Port them oldest first. For each port file, read the upstream diff with",
        "`git -C .upstream/opencode-ios show <commit> -- <path>`, apply the same change to the Kotlin target,",
        f"and set that file's `@file:PortedFrom` commit to `{head[:7]}` once it matches upstream.",
        f"Finish with `scripts/ios-sync.py advance {head[:7]}` and `scripts/ios-sync.py check`.",
        "",
    ]
    for commit in commits:
        subject = git("log", "-1", "--format=%s", commit).strip()
        out.append(f"## {commit[:7]} {subject}")
        out.append("")
        body = git("log", "-1", "--format=%b", commit).strip()
        if body:
            out.extend(["```", body, "```", ""])
        rows = []
        for path, kind in changed_files(f"{commit}~1", commit):
            category = classify(path, config)
            if category == "port":
                rows.append(f"- [ ] port `{kind}` `{path}` -> {targets(path, claims, config, kind)}")
            elif category == "review":
                rows.append(f"- [ ] review `{kind}` `{path}`")
            elif category is None:
                rows.append(f"- [ ] categorize `{kind}` `{path}` in docs/port/sync.json")
        out.extend(rows or ["- nothing to port (ignored paths only)"])
        out.append("")
    PLANS.mkdir(parents=True, exist_ok=True)
    target = PLANS / f"plan-{synced[:7]}-{head[:7]}.md"
    target.write_text("\n".join(out))
    print(f"Wrote {target.relative_to(ROOT)} ({len(commits)} commits).")


def advance_sync(config, args):
    target = resolve(args.rev)
    synced = config["upstream"]["commit"]
    if git("merge-base", "--is-ancestor", synced, target, check=False) is None:
        sys.exit(f"{target[:7]} does not descend from the synced commit {synced[:7]}.")
    problems = gate(config, target)
    stale = problems["Ports older than the synced commit (the iOS file changed since)"]
    unported = [
        path for path, _ in changed_files(synced, target)
        if classify(path, config) == "port" and path in problems["Port files neither ported nor deferred"]
    ]
    if stale or unported:
        for item in stale:
            print(f"  stale: {item}")
        for path in unported:
            print(f"  not ported: {path}")
        sys.exit(f"Cannot advance to {target[:7]} until these files are ported or deferred.")
    config["upstream"]["commit"] = target
    CONFIG.write_text(json.dumps(config, indent=2) + "\n")
    print(f"Synced commit is now {describe(target)}.")


def ensure_upstream(config, fetch):
    if not UPSTREAM.exists():
        UPSTREAM.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--quiet", config["upstream"]["url"], str(UPSTREAM)], check=True)
    elif fetch:
        git("fetch", "--quiet", "origin")


def classify(path, config):
    for category in ("review", "ignore", "port"):
        if any(PurePosixPath(path).full_match(pattern) for pattern in config[category]):
            return category
    return None


def deferral(path, config):
    return next((reason for pattern, reason in config["deferred"].items() if PurePosixPath(path).full_match(pattern)), None)


def marker_claims(config):
    claims = {}
    for source in sorted(SOURCES.rglob("*.kt")):
        for match in MARKER.finditer(source.read_text()):
            claims.setdefault(match.group(1), []).append((str(source.relative_to(ROOT)), resolve(match.group(2))))
    for path, asset in config["assets"].items():
        claims.setdefault(path, []).append((asset["android"], resolve(asset["commit"])))
    return claims


def targets(path, claims, config, kind="modified"):
    if kind == "deleted":
        if path in claims:
            return "deleted upstream, remove or re-home " + ", ".join(f"`{android}`" for android, _ in claims[path])
        return "deleted upstream, nothing to port"
    if path in claims:
        return ", ".join(f"`{android}` (synced {commit[:7]})" for android, commit in claims[path])
    reason = deferral(path, config)
    return f"deferred: {reason}" if reason else "NEW, no Kotlin counterpart yet"


def upstream_files(rev):
    return git("ls-tree", "-r", "--name-only", rev).splitlines()


_changed = {}


def changed_paths(old, new):
    if (old, new) not in _changed:
        _changed[(old, new)] = set() if old == new else set(git("diff", "--name-only", old, new).splitlines())
    return _changed[(old, new)]


def changed_files(old, new):
    rows = git("diff", "--name-status", "-M", old, new).splitlines()
    files = []
    for row in rows:
        parts = row.split("\t")
        kind = parts[0][0]
        files.append((parts[-1], {"A": "added", "D": "deleted", "R": "renamed", "M": "modified"}.get(kind, kind)))
    return files


def swift_lines(rev, path):
    if not path.endswith(".swift"):
        return 0
    return git("show", f"{rev}:{path}").count("\n")


def resolve(rev):
    return git("rev-parse", "--verify", f"{rev}^{{commit}}").strip()


def describe(rev):
    return git("log", "-1", "--format=%h %cs %s", rev).strip()


def git(*args, check=True):
    result = subprocess.run(["git", *args], cwd=UPSTREAM, capture_output=True, text=True)
    if result.returncode != 0:
        if check:
            sys.exit(f"git {' '.join(args)} failed: {result.stderr.strip()}")
        return None
    return result.stdout


if __name__ == "__main__":
    main()
