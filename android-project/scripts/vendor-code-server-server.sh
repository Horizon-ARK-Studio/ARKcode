#!/usr/bin/env bash
#
# Companion to scripts/vendor-code-server.sh: that script vendors only
# the platform-independent VS Code *workbench* frontend
# (lib/vscode/out/vs, out/media, ...). THIS script vendors the
# server-side half needed to actually run code-server as a process --
# code-server's own out/node/ (entry.js and friends) plus BOTH
# node_modules trees it and the bundled VS Code server need
# (code-server's own top-level node_modules/, and
# lib/vscode/node_modules/ for the extension host) -- into
# app/src/main/assets/code-server/{out/node,node_modules,lib/vscode/{out,node_modules}}/.
#
# Run this AFTER (or before, order doesn't matter, but both are
# required) scripts/vendor-code-server.sh -- IdeBackendService copies
# the whole assets/code-server/ tree as one unit
# (extractAssetsOnce/copyAssetTree), so the two scripts' outputs are
# meant to merge into a single tree, not stand alone.
#
# Why this didn't exist alongside vendor-code-server.sh from the
# start: code-server's release ships a linux-x64-linked `node-pty`
# native addon for the integrated terminal (vendor-code-server.sh's
# own header covers that, and it's still true -- see the manifest this
# script prints at the end). What THIS script's investigation added on
# top of that already-known risk:
#
#   1. It is not just node-pty. lib/vscode/node_modules/ ships SEVEN
#      linux-x64 native `.node` addons total in this pinned release:
#      node-pty (integrated terminal), @vscode/sqlite3 (workspace
#      state DB), @vscode/spdlog (native logging sink),
#      @vscode/native-watchdog (parent-process watchdog),
#      @vscode/deviceid (Windows-only -- dead weight on any POSIX
#      target, not just Android), kerberos (SSO auth), and
#      @parcel/watcher (native file-change watching). This script
#      strips all seven .node binaries (they're wrong-arch and would
#      either crash a require() or silently no-op depending on how
#      lazily each package's JS wrapper loads them) and prints exactly
#      what it stripped -- see the manifest below. This script only
#      trace-verified require-timing for none of these seven; each is
#      an open Phase 4 risk per the plan doc, same as node-pty always
#      was, not a solved problem.
#   2. code-server's OWN top-level node_modules/ (not
#      lib/vscode/node_modules/ -- these are code-server's own server
#      deps, resolved directly by out/node/*.js) ships an eighth
#      native addon: `argon2`, used for --auth password's hash/verify.
#      This one WAS traced: out/node/util.js does
#      `const argon2 = __importStar(require("argon2"))` at module
#      top level, which is eager -- stripping argon2's .node binary
#      without patching that require would crash the ENTIRE server at
#      startup, even with --auth none (IdeBackendService's only mode),
#      because the crash happens at module-load time, before any
#      command-line flag is even parsed. See patch_argon2_lazy_require
#      below for the fix: make the require lazy (move it inside the
#      two functions that actually call into argon2), so a missing/
#      wrong-arch argon2 native binary only matters if password auth
#      is ever actually used -- which, per plan section 4.3, it isn't.
#
# Usage:
#   ./scripts/vendor-code-server-server.sh
#
# Like vendor-code-server.sh, deliberately not run automatically as
# part of a Gradle build, and the extracted output is deliberately NOT
# committed to this repo -- same size-in-git-history reasoning as that
# script's own header gives, this one is course another ~65MB on top.

set -euo pipefail

# Pinned identically to vendor-code-server.sh -- these two scripts
# extract from the SAME release tarball and MUST stay in lockstep, or
# the workbench and server halves of a single code-server version
# would silently mismatch. Bump both scripts together.
CODE_SERVER_VERSION="4.135.0"
CODE_SERVER_ASSET="code-server-${CODE_SERVER_VERSION}-linux-amd64.tar.gz"
CODE_SERVER_URL="https://github.com/coder/code-server/releases/download/v${CODE_SERVER_VERSION}/${CODE_SERVER_ASSET}"
CODE_SERVER_SHA256="300ef4e37e469e6368a4673c6a623e1c9ba8a34f42b394fb49c431a8900bc7d1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${PROJECT_ROOT}/app/src/main/assets/code-server"
CACHE_DIR="${PROJECT_ROOT}/.vendor-cache"
TARBALL="${CACHE_DIR}/${CODE_SERVER_ASSET}"
EXTRACT_ROOT_NAME="code-server-${CODE_SERVER_VERSION}-linux-amd64"
EXTRACTED="${CACHE_DIR}/${EXTRACT_ROOT_NAME}"

mkdir -p "${CACHE_DIR}"

if [[ -f "${TARBALL}" ]]; then
    echo "vendor-code-server-server: using cached ${TARBALL}"
else
    echo "vendor-code-server-server: fetching ${CODE_SERVER_URL}"
    curl -sL -o "${TARBALL}" "${CODE_SERVER_URL}"
fi

echo "vendor-code-server-server: verifying checksum"
echo "${CODE_SERVER_SHA256}  ${TARBALL}" | sha256sum -c -

echo "vendor-code-server-server: extracting server-side bundle"
# lib/vscode/out/vs, out/media, product.json, package.json are
# vendor-code-server.sh's job, not this script's -- deliberately not
# re-extracted here so the two scripts' responsibilities don't
# overlap (and so re-running one doesn't clobber the other's output).
tar -xzf "${TARBALL}" -C "${CACHE_DIR}" \
    "${EXTRACT_ROOT_NAME}/out/node" \
    "${EXTRACT_ROOT_NAME}/node_modules" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/server-main.js" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/server-cli.js" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/out/bootstrap-fork.js" \
    "${EXTRACT_ROOT_NAME}/lib/vscode/node_modules"

rm -rf "${DEST}/out/node" "${DEST}/node_modules" "${DEST}/lib/vscode/node_modules"
# Only pre-create the PARENT dirs, never the mv targets themselves --
# `mv src existing-dir` moves src INSIDE existing-dir (nesting it one
# level deeper) rather than renaming it, if the target already exists
# as a directory. Bit this script's own first run: pre-creating
# lib/vscode/node_modules here produced
# lib/vscode/node_modules/node_modules/. Caught by actually running
# this script end to end against the pinned release, not just reading
# it back.
mkdir -p "${DEST}/out" "${DEST}/lib/vscode/out"

# out/node/: code-server's own server (routes, cli parsing, the
# process this app actually execs). Drop .map files -- debug source
# maps, dead weight on a device build.
mv "${EXTRACTED}/out/node" "${DEST}/out/node"
find "${DEST}/out/node" -name '*.js.map' -delete

# node_modules/: code-server's own top-level deps.
mv "${EXTRACTED}/node_modules" "${DEST}/node_modules"

# lib/vscode/out/{server-main,server-cli,bootstrap-fork}.js: the
# compiled VS Code server + extension-host-fork entry points that
# out/node/main.js loads via vsRootPath (see vendor-code-server.sh's
# header for that path arithmetic). Merges alongside that script's
# out/vs, out/media, etc. under the same lib/vscode/out/ directory.
mv "${EXTRACTED}/lib/vscode/out/server-main.js" "${DEST}/lib/vscode/out/server-main.js"
mv "${EXTRACTED}/lib/vscode/out/server-cli.js" "${DEST}/lib/vscode/out/server-cli.js"
mv "${EXTRACTED}/lib/vscode/out/bootstrap-fork.js" "${DEST}/lib/vscode/out/bootstrap-fork.js"

# lib/vscode/node_modules/: the extension host's own deps. Drop
# @github/ (the bundled Copilot extension's SDK, ~193MB in this
# release, entirely unrelated to core editing and not something this
# app enables) to keep this vendoring step's output size sane.
rm -rf "${EXTRACTED}/lib/vscode/node_modules/@github"
mv "${EXTRACTED}/lib/vscode/node_modules" "${DEST}/lib/vscode/node_modules"

rm -rf "${EXTRACTED}"

echo "vendor-code-server-server: patching out/node/util.js (argon2 lazy require)"
patch_argon2_lazy_require() {
    local util_js="${DEST}/out/node/util.js"
    local eager_require='const argon2 = __importStar(require("argon2"));'
    if ! grep -qF "${eager_require}" "${util_js}"; then
        echo "vendor-code-server-server: FATAL: expected eager 'require(\"argon2\")' line" >&2
        echo "not found in ${util_js} -- code-server's source shape changed since" >&2
        echo "this script was written against v${CODE_SERVER_VERSION}. Re-verify the" >&2
        echo "hash()/isHashMatch() call sites by hand before trusting this patch again." >&2
        exit 1
    fi
    # Two independent substitutions, each asserted to hit exactly
    # once, so a partial/stale match fails loudly instead of silently
    # patching only half the call sites:
    #   1. Drop the eager top-level require.
    #   2. Make hash()/isHashMatch() lazily require() argon2 on first
    #      actual use instead.
    python3 - "${util_js}" <<'PYEOF'
import sys, re

path = sys.argv[1]
src = open(path, encoding="utf-8").read()

eager = 'const argon2 = __importStar(require("argon2"));\n'
assert src.count(eager) == 1, f"expected exactly one eager argon2 require, found {src.count(eager)}"
src = src.replace(eager, "", 1)

hash_call = "return yield argon2.hash(password);"
assert src.count(hash_call) == 1, f"expected exactly one argon2.hash() call, found {src.count(hash_call)}"
src = src.replace(
    hash_call,
    'return yield (__importStar(require("argon2"))).hash(password);',
    1,
)

verify_call = "return yield argon2.verify(hash, password);"
assert src.count(verify_call) == 1, f"expected exactly one argon2.verify() call, found {src.count(verify_call)}"
src = src.replace(
    verify_call,
    'return yield (__importStar(require("argon2"))).verify(hash, password);',
    1,
)

open(path, "w", encoding="utf-8").write(src)
print("vendor-code-server-server: argon2 require is now lazy (module load no longer touches it)")
PYEOF
}
patch_argon2_lazy_require

echo "vendor-code-server-server: stripping wrong-arch native addons"
STRIPPED_MANIFEST="${DEST}/.stripped-native-addons.txt"
: > "${STRIPPED_MANIFEST}"
while IFS= read -r -d '' node_file; do
    echo "${node_file#"${DEST}/"}" >> "${STRIPPED_MANIFEST}"
    rm -f "${node_file}"
done < <(find "${DEST}/node_modules" "${DEST}/lib/vscode/node_modules" -name '*.node' -print0)
sort -o "${STRIPPED_MANIFEST}" "${STRIPPED_MANIFEST}"

echo "vendor-code-server-server: done -> ${DEST}"
echo "vendor-code-server-server: stripped $(wc -l < "${STRIPPED_MANIFEST}") linux-x64 native addon(s), see ${STRIPPED_MANIFEST}:"
sed 's/^/  - /' "${STRIPPED_MANIFEST}"
echo "vendor-code-server-server: argon2 is patched lazy (safe under --auth none)."
echo "vendor-code-server-server: all seven remaining stripped addons are UNRESOLVED Phase 4 risk:"
echo "  no integrated terminal (node-pty), no sqlite3 workspace-state DB, no native"
echo "  file watcher (@parcel/watcher), no native log sink (spdlog), no native-watchdog,"
echo "  no kerberos SSO, no deviceid (POSIX-irrelevant regardless). Each needs its own"
echo "  bionic-targeted rebuild (same NDK/toolchain dependency as libnode.so itself,"
echo "  plan section 5b) or a confirmed-safe JS fallback before it's actually fine to ship."
du -sh "${DEST}/out/node" "${DEST}/node_modules" "${DEST}/lib/vscode/node_modules"
