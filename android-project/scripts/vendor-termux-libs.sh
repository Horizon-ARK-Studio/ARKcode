#!/usr/bin/env bash
#
# Fetches libnode.so's missing NEEDED shared libraries (see BUG-0002 in
# docs/bugs-caught/README.md and IdeBackendService.REQUIRED_NATIVE_LIBS)
# straight from Termux's real apt repository and drops them into
# jniLibs/arm64-v8a/ alongside libnode.so itself, so a real device
# build doesn't depend on a user having imported them at runtime via
# TermuxLibImporter's SAF picker.
#
# WHY NOT A HARDCODED VERSION+SHA256 LIKE vendor-code-server.sh:
# that script pins an exact upstream GitHub release, which GitHub
# keeps forever. Termux's apt repo is rolling -- once a package is
# rebuilt, the old .deb is pruned from the pool, so a hardcoded old
# version+URL here would eventually 404 with no way to recover short
# of re-pinning. Instead this script resolves the CURRENT version at
# request time from the repo's own signed Packages index and verifies
# the download against the SHA256 *that index states* -- the same
# trust chain apt itself uses (Release -> Release.gpg -> Packages ->
# per-entry SHA256), short of actually importing the termux-pacman
# GPG key and checking Release.gpg here too. Anyone wanting that extra
# step can layer it on; the per-file SHA256 check below already means
# a corrupted or tampered-in-transit .deb is caught, just not a
# tampered *index*.
#
# Real repo layout, confirmed against
# https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages
# (NOT the flat /<letter>/<pkg>/<pkg>_<ver>_<arch>.deb shape some
# writeups assume -- the actual pool path comes from each entry's own
# Filename: field, which this script reads rather than constructs by
# hand):
#   dists/stable/Release                       (signed index of indices)
#   dists/stable/main/binary-aarch64/Packages   (deb822 stanzas, plain text)
#   pool/main/<letter>/<pkgname>/<exact-filename-from-index>
#
# Real Termux package names for each REQUIRED_NATIVE_LIBS entry,
# confirmed against each package's own build.sh in termux/termux-packages
# (i.e. not guessed from the library's own name -- "sqlite" not
# "libsqlite3", "c-ares" not "libcares", "libc++" ships
# libc++_shared.so specifically because every C++-linked Termux
# package builds against it by default, per that package's own
# build.sh comment):
#   libz.so.1                          <- zlib
#   libcares.so                        <- c-ares
#   libsqlite3.so                      <- sqlite
#   libcrypto.so.3, libssl.so.3        <- openssl
#   libicui18n.so.<ver>, libicuuc.so.<ver> <- icu (version floats with
#                                          the package -- this script
#                                          copies whatever the package
#                                          actually ships, not a
#                                          hardcoded "78")
#   libc++_shared.so                   <- libc++
#
# Usage:
#   ./scripts/vendor-termux-libs.sh
#
# Like vendor-code-server.sh, deliberately NOT run automatically as
# part of a Gradle build -- run it by hand, review what it fetched
# (this script prints a manifest at the end), then `git add` the
# resulting jniLibs/arm64-v8a/*.so files yourself. Unlike
# assets/code-server/, jniLibs/arm64-v8a/libnode.so IS already
# committed to this repo (see .gitignore's comment on that directory
# vs assets/code-server/), so these files following the same
# committed convention is deliberate, not an oversight.

set -euo pipefail

TERMUX_REPO_BASE="https://packages.termux.dev/apt/termux-main"
TERMUX_REPO_FALLBACK="https://packages-cf.termux.dev/apt/termux-main"
TERMUX_ARCH="aarch64"          # matches this repo's only built ABI, arm64-v8a
ANDROID_ABI="arm64-v8a"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${PROJECT_ROOT}/app/src/main/jniLibs/${ANDROID_ABI}"
CACHE_DIR="${PROJECT_ROOT}/.vendor-cache/termux-libs"
INDEX_FILE="${CACHE_DIR}/Packages"

# name-of-.so-we-need -> Termux package that ships it. One package can
# (and for openssl/icu, does) satisfy more than one entry in
# REQUIRED_NATIVE_LIBS -- listed once per .so anyway so the manifest
# at the end reads one line per file, not one per package.
declare -A LIB_TO_PACKAGE=(
    ["libz.so.1"]="zlib"
    ["libcares.so"]="c-ares"
    ["libsqlite3.so"]="sqlite"
    ["libcrypto.so.3"]="openssl"
    ["libssl.so.3"]="openssl"
    ["libc++_shared.so"]="libc++"
)
# icu's two libraries carry a version suffix that moves with the
# package (see header) -- handled separately below via a glob match
# instead of an exact filename, unlike the fixed names above.
ICU_PACKAGE="icu"

mkdir -p "${CACHE_DIR}" "${DEST}"

echo "vendor-termux-libs: fetching Packages index for ${TERMUX_ARCH}"
INDEX_URL="${TERMUX_REPO_BASE}/dists/stable/main/binary-${TERMUX_ARCH}/Packages"
if ! curl -fsSL -o "${INDEX_FILE}" "${INDEX_URL}"; then
    echo "vendor-termux-libs: primary mirror failed, trying fallback CDN" >&2
    INDEX_URL="${TERMUX_REPO_FALLBACK}/dists/stable/main/binary-${TERMUX_ARCH}/Packages"
    curl -fsSL -o "${INDEX_FILE}" "${INDEX_URL}"
fi

# Pulls one field's value out of the (single, first) stanza in the
# deb822 index whose Package: line matches $1. awk over a full
# apt-style parser: the index format's contract is "blank line
# separates stanzas, first match wins" for a plain repo (no
# pinning/priority needed here since there's exactly one
# binary-aarch64 Packages file, no multiple-suite merge to resolve).
package_field() {
    local pkg="$1" field="$2"
    awk -v pkg="${pkg}" -v field="${field}:" '
        BEGIN { in_stanza = 0; matched = 0 }
        /^Package: / {
            in_stanza = ($2 == pkg)
            if (in_stanza) matched = 1
        }
        in_stanza && index($0, field) == 1 {
            sub("^" field " ", "")
            print
            exit
        }
    ' "${INDEX_FILE}"
}

# Downloads+verifies one Termux package's .deb into CACHE_DIR, then
# ar/tar-extracts it into its own subdir under CACHE_DIR so multiple
# packages' contents never collide. Returns (via a global, bash has no
# real return-by-value) the extracted data root in EXTRACTED_ROOT.
fetch_and_extract_package() {
    local pkg="$1"
    local version filename sha256
    version="$(package_field "${pkg}" "Version")"
    filename="$(package_field "${pkg}" "Filename")"
    sha256="$(package_field "${pkg}" "SHA256")"

    if [[ -z "${version}" || -z "${filename}" || -z "${sha256}" ]]; then
        echo "vendor-termux-libs: FATAL: package '${pkg}' not found in" \
            "${INDEX_URL} (or missing Version/Filename/SHA256 field) --" \
            "check the package name against termux/termux-packages" >&2
        exit 1
    fi
    echo "vendor-termux-libs: ${pkg} -> version ${version}"

    local deb_path="${CACHE_DIR}/$(basename "${filename}")"
    if [[ -f "${deb_path}" ]]; then
        echo "vendor-termux-libs: using cached ${deb_path}"
    else
        echo "vendor-termux-libs: fetching ${TERMUX_REPO_BASE}/${filename}"
        curl -fsSL -o "${deb_path}" "${TERMUX_REPO_BASE}/${filename}"
    fi

    echo "vendor-termux-libs: verifying ${pkg} checksum"
    echo "${sha256}  ${deb_path}" | sha256sum -c -

    local extract_dir="${CACHE_DIR}/${pkg}-extracted"
    rm -rf "${extract_dir}"
    mkdir -p "${extract_dir}"
    # .deb is a plain ar archive of {debian-binary, control.tar.*,
    # data.tar.*} -- only data.tar.* (the actual installed files)
    # matters here. Termux's build tooling has shipped data.tar.xz
    # historically and data.tar.zst more recently; handle either
    # rather than assume one.
    ( cd "${extract_dir}" && ar -x "${deb_path}" )
    local data_tar
    data_tar="$(find "${extract_dir}" -maxdepth 1 -name 'data.tar.*' | head -n1)"
    if [[ -z "${data_tar}" ]]; then
        echo "vendor-termux-libs: FATAL: ${deb_path} has no data.tar.* member" >&2
        exit 1
    fi
    tar -xf "${data_tar}" -C "${extract_dir}"

    EXTRACTED_ROOT="${extract_dir}/data/data/com.termux/files/usr"
    if [[ ! -d "${EXTRACTED_ROOT}" ]]; then
        echo "vendor-termux-libs: FATAL: expected ${EXTRACTED_ROOT} not found" \
            "in ${pkg}'s data.tar -- Termux's install-prefix layout may have" \
            "changed since this script was written" >&2
        exit 1
    fi
}

MANIFEST=()

# ---- fixed-name libraries: one package can cover several exact names ----
# Group by package first so a package shipping two of our libraries
# (openssl -> libssl.so.3 + libcrypto.so.3) is only downloaded once.
declare -A PACKAGES_SEEN=()
for lib_name in "${!LIB_TO_PACKAGE[@]}"; do
    PACKAGES_SEEN["${LIB_TO_PACKAGE[$lib_name]}"]=1
done
for pkg in "${!PACKAGES_SEEN[@]}"; do
    fetch_and_extract_package "${pkg}"
    for lib_name in "${!LIB_TO_PACKAGE[@]}"; do
        [[ "${LIB_TO_PACKAGE[$lib_name]}" == "${pkg}" ]] || continue
        src="${EXTRACTED_ROOT}/lib/${lib_name}"
        if [[ ! -f "${src}" ]]; then
            echo "vendor-termux-libs: FATAL: ${pkg}'s package doesn't contain" \
                "usr/lib/${lib_name} -- expected filename may have changed" \
                "upstream, check this script's LIB_TO_PACKAGE mapping" >&2
            exit 1
        fi
        cp "${src}" "${DEST}/${lib_name}"
        MANIFEST+=("${lib_name}  (${pkg})")
    done
done

# ---- icu: versioned filenames, resolved by glob rather than hardcoded ----
fetch_and_extract_package "${ICU_PACKAGE}"
icu_found=0
for icu_lib in libicuuc libicui18n; do
    src="$(find "${EXTRACTED_ROOT}/lib" -maxdepth 1 -name "${icu_lib}.so.*" | sort -V | tail -n1)"
    if [[ -z "${src}" ]]; then
        echo "vendor-termux-libs: FATAL: icu package doesn't contain any" \
            "usr/lib/${icu_lib}.so.* -- expected filename pattern may have" \
            "changed upstream" >&2
        exit 1
    fi
    cp "${src}" "${DEST}/$(basename "${src}")"
    MANIFEST+=("$(basename "${src}")  (icu)")
    icu_found=$((icu_found + 1))
done
[[ "${icu_found}" -eq 2 ]] || { echo "vendor-termux-libs: FATAL: expected 2 icu libs, found ${icu_found}" >&2; exit 1; }

echo ""
echo "vendor-termux-libs: done -> ${DEST}"
echo "vendor-termux-libs: fetched ${#MANIFEST[@]} librar$([[ ${#MANIFEST[@]} -eq 1 ]] && echo y || echo ies):"
printf '  - %s\n' "${MANIFEST[@]}"
echo ""
echo "vendor-termux-libs: reminder -- these came from Termux's rolling repo," \
    "not a pinned release. Re-run this script and diff against what's" \
    "already committed under jniLibs/${ANDROID_ABI}/ if IdeBackendService" \
    "ever starts failing REQUIRED_NATIVE_LIBS again after a libnode.so rebuild."
