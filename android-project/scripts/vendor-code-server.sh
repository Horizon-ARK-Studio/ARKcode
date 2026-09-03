#!/usr/bin/env bash
#
# Fetches the pinned code-server release, verifies its checksum, and
# extracts *only* the platform-independent VS Code workbench frontend
# (out/vs/, out/media/, nls*.json, product.json, LICENSE,
# ThirdPartyNotices.txt) into app/src/main/assets/code-server/workbench/.
#
# Deliberately NOT run automatically as part of a Gradle build, and
# the extracted output is deliberately NOT committed to this repo:
#
#   1. The full release tarball is ~235MB compressed / ~760MB
#      uncompressed. Committing that (or its extracted subset, which
#      is still ~56MB even after dropping the arch-specific Node
#      binary and every server-side node_modules tree) into git
#      history is exactly the kind of bloat a patch/diff-reviewable
#      repo should not carry -- every future clone pays for it
#      forever, unlike a build-time fetch.
#   2. code-server's bundled `lib/vscode/node_modules/node-pty/build/
#      Release/pty.node` (the integrated terminal's native addon) in
#      this release is linux-x64-linked -- useless on Android as-is.
#      Vendoring the workbench separately from the server/Node side
#      keeps that already-known Phase 4 risk (see
#      VSCODE-IDE-IMPLEMENTATION-PLAN.md section 4.1's node-pty note)
#      isolated to its own future step instead of silently bundled in
#      here.
#
# What THIS script extracts is genuinely platform-independent: the
# workbench's HTML/CSS/JS bundle runs the same regardless of which
# Node/libc built the server serving it, so it's safe to source from
# any release asset (linux-amd64 here) even though the eventual
# on-device Node binary (section 5) will be a completely different
# build (bionic-linked, arm64/armv7).
#
# Usage:
#   ./scripts/vendor-code-server.sh
#
# Run from CI (Phase 6, section 7) before `gradle assembleDebug`/
# `assembleRelease` so a stale or missing vendored workbench fails
# the build loudly instead of shipping silently broken, per section 7's
# Phase 6 description.

set -euo pipefail

# Pinned deliberately -- bump this (and CODE_SERVER_SHA256 below)
# as a reviewed, explicit change, never silently track "latest".
CODE_SERVER_VERSION="4.135.0"
CODE_SERVER_ASSET="code-server-${CODE_SERVER_VERSION}-linux-amd64.tar.gz"
CODE_SERVER_URL="https://github.com/coder/code-server/releases/download/v${CODE_SERVER_VERSION}/${CODE_SERVER_ASSET}"
# Verified against the actual downloaded asset as of this script's
# authorship -- see the commit that introduced this file.
CODE_SERVER_SHA256="300ef4e37e469e6368a4673c6a623e1c9ba8a34f42b394fb49c431a8900bc7d1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${PROJECT_ROOT}/app/src/main/assets/code-server/workbench"
CACHE_DIR="${PROJECT_ROOT}/.vendor-cache"
TARBALL="${CACHE_DIR}/${CODE_SERVER_ASSET}"
EXTRACT_ROOT_NAME="code-server-${CODE_SERVER_VERSION}-linux-amd64"

mkdir -p "${CACHE_DIR}"

if [[ -f "${TARBALL}" ]]; then
    echo "vendor-code-server: using cached ${TARBALL}"
else
    echo "vendor-code-server: fetching ${CODE_SERVER_URL}"
    curl -sL -o "${TARBALL}" "${CODE_SERVER_URL}"
fi

echo "vendor-code-server: verifying checksum"
echo "${CODE_SERVER_SHA256}  ${TARBALL}" | sha256sum -c -

rm -rf "${DEST}"
mkdir -p "${DEST}"

echo "vendor-code-server: extracting workbench assets"
tar -xzf "${TARBALL}" -C "${CACHE_DIR}" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/vs" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/media" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/nls.messages.json" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/nls.metadata.json" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/product.json" \
    "${EXTRACT_ROOT_NAME}/LICENSE" \
    "${EXTRACT_ROOT_NAME}/ThirdPartyNotices.txt"

EXTRACTED="${CACHE_DIR}/${EXTRACT_ROOT_NAME}/lib/vscode"
mv "${EXTRACTED}/out/vs" "${DEST}/vs"
mv "${EXTRACTED}/out/media" "${DEST}/media"
mv "${EXTRACTED}/out/nls.messages.json" "${DEST}/nls.messages.json"
mv "${EXTRACTED}/out/nls.metadata.json" "${DEST}/nls.metadata.json"
mv "${EXTRACTED}/product.json" "${DEST}/product.json"
mv "${CACHE_DIR}/${EXTRACT_ROOT_NAME}/LICENSE" "${DEST}/LICENSE.code-server"
mv "${CACHE_DIR}/${EXTRACT_ROOT_NAME}/ThirdPartyNotices.txt" "${DEST}/ThirdPartyNotices.txt"

rm -rf "${CACHE_DIR:?}/${EXTRACT_ROOT_NAME}"

echo "vendor-code-server: done -> ${DEST}"
du -sh "${DEST}"
