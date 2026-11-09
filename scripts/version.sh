#!/bin/sh
# Prints the app version for a commit (default HEAD), derived from git history
# so every commit on main has its own version without a committed version file.
# Same rules as iOS's scripts/version.sh, from this repo's own baseline.
#
# From the baseline (BASE_COMMIT is BASE_VERSION), each first-parent commit:
#   - bumps major when its message marks a breaking change ("type!:" or
#     "BREAKING CHANGE"),
#   - bumps minor when it changes MINOR_LINES or more lines (generated and
#     lock files don't count),
#   - bumps patch otherwise.
# Commits before the baseline keep BASE_VERSION.
set -eu

BASE_COMMIT=acc23eab
BASE_VERSION=0.1.0
MINOR_LINES=400

target=${1:-HEAD}
cd "$(dirname "$0")/.."

if ! git merge-base --is-ancestor "$BASE_COMMIT" "$target" 2>/dev/null; then
  echo "$BASE_VERSION"
  exit 0
fi

major=${BASE_VERSION%%.*}
rest=${BASE_VERSION#*.}
minor=${rest%%.*}
patch=${rest#*.}

for commit in $(git rev-list --reverse --first-parent "$BASE_COMMIT..$target"); do
  message=$(git log -1 --format=%B "$commit")
  if printf '%s' "$message" | head -1 | grep -Eq '^[a-z]+(\([^)]*\))?!:' ||
     printf '%s' "$message" | grep -q 'BREAKING CHANGE'; then
    major=$((major + 1)); minor=0; patch=0
    continue
  fi
  lines=$(git diff --numstat "$commit^1" "$commit" -- . \
      ':(exclude)*.lock' ':(exclude)*.lockb' ':(exclude)**/bun.lock' |
    awk '$1 != "-" { n += $1 + $2 } END { print n + 0 }')
  if [ "$lines" -ge "$MINOR_LINES" ]; then
    minor=$((minor + 1)); patch=0
  else
    patch=$((patch + 1))
  fi
done

echo "$major.$minor.$patch"
