#!/usr/bin/env python3
"""Keep the Android port in step with the OpenCode iOS app.

The iOS checkout lives in .upstream/opencode-ios (cloned on first use).
docs/port/sync.json records the upstream commit the port is synced to and
classifies every upstream file as port, review, or ignore. A Kotlin file
claims the iOS file it mirrors with @file:PortedFrom("<ios path>", "<commit>"),
where the commit is the upstream revision that file was last synced against.

A port file is ported (a full claim), deferred (`deferred`: not tracked; a
partial claim on it is a placeholder for callers and stays pinned at the commit
it was carved from), or partly ported (`partial`: a partial claim that is kept
in sync, with the reason its remainder waits). Upstream commits listed in
`skipped` were deliberately not ported: a claim is not stale for their changes.
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
MARKER = re.compile(r'@file:PortedFrom\(\s*"([^"]+)"\s*,\s*"([0-9a-f]{7,40})"\s*(?:,\s*partial\s*=\s*"([^"]*)"\s*)?\)')


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
    mark = commands.add_parser("mark", help="set the @file:PortedFrom commit of ported files to REV")
    mark.add_argument("rev")
    mark.add_argument("paths", nargs="*", help="iOS or Kotlin paths (default: every port file REV changed)")
    args = parser.parse_args()

    config = json.loads(CONFIG.read_text())
    ensure_upstream(config, fetch=args.command in ("status", "plan"))
    {"status": status, "check": check, "progress": progress, "plan": plan_worklist, "advance": advance_sync, "mark": mark_ported}[args.command](config, args)


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
    skipped = skipped_commits(config)
    for commit in commits:
        print(f"  {describe(commit)}" + (f"  [skipped: {skipped[commit]}]" if commit in skipped else ""))
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
        "Partial reasons in sync.json without a partial port": [],
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
            ported = is_ported(path, claims)
            if ported and deferred:
                problems["Port files both ported and deferred"].append(path)
            elif not ported and not deferred and not (path in claims and remainder(path, config)):
                notes = "; ".join(partial for _, _, partial in claims.get(path, []) if partial)
                problems["Port files neither ported nor deferred"].append(f"{path} (partial: {notes})" if notes else path)
    for path in config.get("partial", {}):
        if path not in claims or is_ported(path, claims):
            problems["Partial reasons in sync.json without a partial port"].append(path)
    present = set(files)
    for path, ports in claims.items():
        if path not in present:
            # A file added after the synced commit, claimed during a sync in progress.
            missing = [android for android, commit, _ in ports if not (is_ancestor(synced, commit) and exists_at(commit, path))]
            problems["Ports that claim a file missing upstream"].extend(f"{android} claims {path}" for android in missing)
            continue
        for android, commit, partial in ports:
            placeholder = partial and deferral(path, config)
            # A claim ahead of the synced commit is a sync in progress, not stale.
            if not placeholder and is_ancestor(commit, synced) and path in changed_paths(commit, synced, config):
                problems["Ports older than the synced commit (the iOS file changed since)"].append(f"{android} synced {path} at {commit[:7]}")
            if not (ROOT / android).exists():
                problems["Claimed Android files that do not exist"].append(android)
    return problems


def progress(config, _args):
    synced = config["upstream"]["commit"]
    claims = marker_claims(config)
    totals = {"ported": [0, 0], "partial": [0, 0], "deferred": [0, 0], "missing": [0, 0]}
    missing = []
    for path in upstream_files(synced):
        if classify(path, config) != "port":
            continue
        lines = swift_lines(synced, path)
        if is_ported(path, claims):
            state = "ported"
        elif deferral(path, config):
            state = "deferred"
        else:
            state = "partial" if path in claims else "missing"
            missing.append((lines, path, state))
        totals[state][0] += 1
        totals[state][1] += lines
    all_lines = sum(lines for _, lines in totals.values()) or 1
    for state, (count, lines) in totals.items():
        print(f"{state:9} {count:4} files {lines:7} Swift lines {100 * lines / all_lines:5.1f}%")
    if missing:
        print("\nNot yet ported, largest first:")
        for lines, path, state in sorted(missing, reverse=True):
            waits = remainder(path, config)
            note = f" (partial; the rest waits: {waits})" if waits else " (partial)" if state == "partial" else ""
            print(f"  {lines:6} {path}{note}")
    placeholders = sorted((path, android, commit) for path, ports in claims.items() for android, commit, partial in ports if partial and deferral(path, config))
    if placeholders:
        print("\nPlaceholders for deferred files, pinned where they were carved:")
        for path, android, commit in placeholders:
            print(f"  {android} at {commit[:7]} ({path})")


def plan_worklist(config, args):
    synced = config["upstream"]["commit"]
    head = resolve(args.rev or f"origin/{config['upstream']['branch']}")
    commits = git("rev-list", "--reverse", f"{synced}..{head}").split()
    if not commits:
        print("Nothing to plan, the port is synced.")
        return
    claims = marker_claims(config)
    skipped = skipped_commits(config)
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
        if commit in skipped:
            out.extend([f"Skipped in sync.json: {skipped[commit]}", ""])
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
    claims = marker_claims(config)
    unported = [
        path for path, kind in changed_files(synced, target)
        if kind != "deleted" and classify(path, config) == "port"
        and not is_ported(path, claims) and not deferral(path, config)
        and not (path in claims and remainder(path, config))
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


def mark_ported(config, args):
    target = resolve(args.rev)
    claims = marker_claims(config)
    if args.paths:
        ios_paths = set()
        for path in args.paths:
            if path.endswith(".kt"):
                android = str((Path.cwd() / path).resolve().relative_to(ROOT))
                ios_paths.update(ios for ios, ports in claims.items() if any(file == android for file, _, _ in ports))
            else:
                ios_paths.add(path)
    else:
        ios_paths = {path for path, kind in changed_files(f"{target}~1", target) if kind != "deleted" and classify(path, config) == "port"}
    for path in sorted(ios_paths):
        ports = [file for file, _, _ in claims.get(path, []) if file.endswith(".kt")]
        if not ports:
            print(f"  skipped {path}: {deferral(path, config) and 'deferred' or 'no Kotlin file claims it'}")
            continue
        for file in ports:
            source = ROOT / file
            text = source.read_text()
            updated = MARKER.sub(
                lambda match: match.group(0).replace(f'"{match.group(2)}"', f'"{target[:7]}"', 1) if match.group(1) == path else match.group(0),
                text,
            )
            if updated != text:
                source.write_text(updated)
                print(f"  {file} -> {target[:7]} ({path})")


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
    """Why a file is not tracked. A `partial` entry tracks a file a deferred folder would otherwise cover."""
    if remainder(path, config):
        return None
    return next((reason for pattern, reason in config["deferred"].items() if PurePosixPath(path).full_match(pattern)), None)


def remainder(path, config):
    """Why the unported rest of a partly ported file waits, from sync.json `partial`."""
    return config.get("partial", {}).get(path)


def marker_claims(config):
    claims = {}
    for source in sorted(SOURCES.rglob("*.kt")):
        for match in MARKER.finditer(source.read_text()):
            claim = (str(source.relative_to(ROOT)), resolve(match.group(2)), match.group(3) or "")
            claims.setdefault(match.group(1), []).append(claim)
    for path, asset in config["assets"].items():
        claims.setdefault(path, []).append((asset["android"], resolve(asset["commit"]), ""))
    return claims


def is_ported(path, claims):
    return any(not partial for _, _, partial in claims.get(path, []))


def targets(path, claims, config, kind="modified"):
    if kind == "deleted":
        if path in claims:
            return "deleted upstream, remove or re-home " + ", ".join(f"`{android}`" for android, _, _ in claims[path])
        return "deleted upstream, nothing to port"
    reason = deferral(path, config)
    if reason:
        pinned = ", ".join(f"`{android}` at {commit[:7]}" for android, commit, _ in claims.get(path, []))
        return f"deferred: {reason}" + (f" (placeholder {pinned} stays pinned; add only what callers need)" if pinned else "")
    if path in claims:
        return ", ".join(
            f"`{android}` (synced {commit[:7]}{', partial: ' + partial if partial else ''})"
            for android, commit, partial in claims[path]
        )
    return "NEW, no Kotlin counterpart yet"


def upstream_files(rev):
    return git("ls-tree", "-r", "--name-only", rev).splitlines()


_changed = {}
_ancestors = {}


def is_ancestor(old, new):
    if (old, new) not in _ancestors:
        _ancestors[(old, new)] = git("merge-base", "--is-ancestor", old, new, check=False) is not None
    return _ancestors[(old, new)]


def changed_paths(old, new, config=None):
    """Files changed from `old` to `new`, leaving out commits sync.json skips."""
    skipped = skipped_commits(config) if config else {}
    if (old, new) not in _changed:
        if old == new:
            _changed[(old, new)] = set()
        elif not skipped:
            _changed[(old, new)] = set(git("diff", "--name-only", old, new).splitlines())
        else:
            commits = [commit for commit in git("rev-list", f"{old}..{new}").split() if commit not in skipped]
            _changed[(old, new)] = set().union(*(commit_paths(commit) for commit in commits))
    return _changed[(old, new)]


def exists_at(rev, path):
    return git("cat-file", "-e", f"{rev}:{path}", check=False) is not None


_commit_paths = {}


def commit_paths(commit):
    if commit not in _commit_paths:
        _commit_paths[commit] = set(git("diff-tree", "--no-commit-id", "--name-only", "-r", commit).splitlines())
    return _commit_paths[commit]


_skipped = None


def skipped_commits(config):
    """Upstream commits deliberately not ported, by full SHA, with the reason."""
    global _skipped
    if _skipped is None:
        _skipped = {resolve(rev): reason for rev, reason in config.get("skipped", {}).items()}
    return _skipped


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
