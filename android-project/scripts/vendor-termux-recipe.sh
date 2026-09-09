#!/usr/bin/env bash
#
# Fetches termux-packages' `nodejs-lts` build recipe (build.sh + every
# patch it applies) straight from termux/termux-packages at a pinned
# commit, verifies the tarball's checksum, and extracts just
# `packages/nodejs-lts/` + `LICENSE.md` into a build-time-only
# directory -- nothing under here is committed to this repo.
#
# Replaces the old `android-project/vendor/termux-packages/` tree,
# which held this same recipe checked straight into git history. Same
# reasoning as vendor-code-server.sh's own header: this recipe is
# upstream content this repo doesn't modify, so re-fetching it fresh
# whenever it's actually needed (build-libnode.yml, see below) is
# strictly better than paying for it in every future `git clone` of
# this repo forever. The recipe is small (~150KB) compared to
# code-server's workbench, but the principle -- and the pinned-commit
# + checksum verification discipline -- is the same either way.
#
# Usage:
#   ./scripts/vendor-termux-recipe.sh
#
# Unlike vendor-code-server.sh (manual, run-by-hand-before-a-build),
# this one IS wired to run automatically at build time: it's the
# first step of .github/workflows/build-libnode.yml's build-libnode
# job, since that job is the only consumer of this recipe and always
# needs it at whatever version this script is pinned to -- there's no
# separate "review what got fetched, git add it" step for a human to
# do in between, so automating the fetch instead of leaving it manual
# loses nothing.
#
# Output lands at android-project/build/termux-recipe/nodejs-lts --
# under build/, which is already gitignored (standard Gradle output
# dir), so nothing here needs its own .gitignore entry.

set -euo pipefail

# Pinned deliberately -- bump this (and TERMUX_PACKAGES_TARBALL_SHA256
# below) as a reviewed, explicit change, never silently track
# termux-packages' master branch. Same commit build-libnode.yml's own
# TERMUX_PACKAGES_COMMIT already pinned to, kept in sync with that
# workflow's clone of the rest of termux-packages so the recipe here
# and the build system running it never drift apart.
TERMUX_PACKAGES_COMMIT="9010030e71dbb22d8d5fffad8d6e6bc84155a23b"
TERMUX_PACKAGES_URL="https://codeload.github.com/termux/termux-packages/tar.gz/${TERMUX_PACKAGES_COMMIT}"
# Verified against the actual downloaded tarball as of this script's
# authorship -- see the commit that introduced this file.
TERMUX_PACKAGES_TARBALL_SHA256="4d8f23a8956a82a9b805f48b236ee19c9865d69cc8b6eeb2909c863fa3a0469e"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${PROJECT_ROOT}/build/termux-recipe"
CACHE_DIR="${PROJECT_ROOT}/.vendor-cache"
TARBALL="${CACHE_DIR}/termux-packages-${TERMUX_PACKAGES_COMMIT}.tar.gz"
EXTRACT_ROOT_NAME="termux-packages-${TERMUX_PACKAGES_COMMIT}"

mkdir -p "${CACHE_DIR}"

if [[ -f "${TARBALL}" ]]; then
    echo "vendor-termux-recipe: using cached ${TARBALL}"
else
    echo "vendor-termux-recipe: fetching ${TERMUX_PACKAGES_URL}"
    curl -sL -o "${TARBALL}" "${TERMUX_PACKAGES_URL}"
fi

echo "vendor-termux-recipe: verifying checksum"
echo "${TERMUX_PACKAGES_TARBALL_SHA256}  ${TARBALL}" | sha256sum -c -

rm -rf "${DEST}"
mkdir -p "${DEST}"

echo "vendor-termux-recipe: extracting nodejs-lts recipe"
tar -xzf "${TARBALL}" -C "${CACHE_DIR}" \
    "${EXTRACT_ROOT_NAME}/packages/nodejs-lts" \
    "${EXTRACT_ROOT_NAME}/LICENSE.md"

mv "${CACHE_DIR}/${EXTRACT_ROOT_NAME}/packages/nodejs-lts" "${DEST}/nodejs-lts"
# LICENSE.md's own text is what explains the patches/build.sh's
# licensing (see the old vendor/termux-packages/README.md's
# "Licensing" section, ported into this recipe's own README below) --
# keep it alongside the recipe rather than dropping it, same as
# before.
mv "${CACHE_DIR}/${EXTRACT_ROOT_NAME}/LICENSE.md" "${DEST}/LICENSE.md"

cat > "${DEST}/README.md" <<'EOF'
# Fetched: termux-packages' `nodejs-lts` build recipe

This directory is produced by `scripts/vendor-termux-recipe.sh` at
build time -- it is not committed to the repository (see `build/` in
`.gitignore`). Re-run that script to regenerate it.

`nodejs-lts/` is `packages/nodejs-lts/` from
[termux/termux-packages](https://github.com/termux/termux-packages),
fetched at the commit pinned in `vendor-termux-recipe.sh`
(`TERMUX_PACKAGES_COMMIT`). It's the build recipe -- `build.sh` plus
every patch it applies -- that produces Node.js `24.18.0`,
cross-compiled with `--dest-os=android`, i.e. a bionic-linked Node
binary, as opposed to the glibc-linked binary a stock nodejs.org
release tarball gives you. See
`docs/Foundational/VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 5 for
why this approach (option (b)) was picked.

Licensing: per `LICENSE.md` (fetched alongside), termux-packages' own
build infrastructure is Apache-2.0, but "the scripts and patches to
build each package is licensed under the same license as the actual
package" -- so `nodejs-lts/build.sh` and its patches are under
Node.js's own license (MIT), one-way-compatible as an inbound license
into ARKware's GPLv3-or-later (`../../../LICENSE`).

Not a compiled binary, and not runnable standalone -- same caveats as
before: `build.sh` calls shared functions from termux-packages' own
`build-package.sh`/`scripts/build/`, none of which are fetched here.
`.github/workflows/build-libnode.yml` is what actually runs it, inside
termux-packages' own Docker build environment.
EOF

rm -rf "${CACHE_DIR:?}/${EXTRACT_ROOT_NAME}"

echo "vendor-termux-recipe: done -> ${DEST}"
du -sh "${DEST}"
