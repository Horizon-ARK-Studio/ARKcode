# Vendored: termux-packages' `nodejs-lts` build recipe

**Status: reference material only. Nothing under `vendor/` is invoked by
any Gradle task, script, or CI job in this project yet.** This is
sourcing for `VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 5 option
(b), not a working build step -- see "What integration will actually
require" below for exactly what's missing before it could become one.

## What this is

`nodejs-lts/` is `packages/nodejs-lts/` copied verbatim from
[termux/termux-packages](https://github.com/termux/termux-packages),
commit `9010030e71dbb22d8d5fffad8d6e6bc84155a23b` (shallow clone, so
this is that repo's tip at the time of vendoring, not necessarily the
commit that last touched this specific recipe). It's the build
recipe -- `build.sh` plus every patch it applies -- that produces
Node.js `24.18.0`, cross-compiled with `--dest-os=android`, i.e. a
**bionic-linked Node binary**, as opposed to the glibc-linked binary
a stock `nodejs.org` release tarball gives you.

This is exactly the "build against Termux's bionic-targeted
toolchain" approach `VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 5
describes as option (b), and the reason that document recommends it
over option (a): it sidesteps the glibc-vs-bionic linking question by
construction, since `build.sh`'s own `./configure --dest-os=android`
line is the proof this recipe already targets the right libc, whereas
a stock release tarball's linkage would need to be verified (and very
likely reworked) from scratch.

## What this is *not*

- **Not a compiled binary.** Nothing here is a `.so`/executable --
  see `docs/Foundational/VSCODE-IDE-IMPLEMENTATION-PLAN.md` section
  0.1: vendoring Termux's own *compiled, installed* userland doesn't
  work on Android at all (hardcoded `PT_INTERP` pointing at
  `/data/data/com.termux/...`, plus the `noexec` mount issue). This
  is the *recipe*, run once, offline, against this project's own
  eventual build infrastructure -- never Termux's installed binaries
  themselves.
- **Not runnable as-is.** `build.sh` is one package recipe within
  termux-packages' larger build system -- it calls shared functions
  (`termux_setup_ninja`, `termux_download`, `termux_step_*` hooks)
  that live in that repo's `build-package.sh`/`scripts/build/`, none
  of which are vendored here. Running this recipe for real means
  either running it inside termux-packages' own build environment
  (their Docker container, via their `./scripts/run-docker.sh` +
  `./build-package.sh nodejs-lts`) and taking the resulting binary
  out, or reimplementing just enough of the calling convention to run
  `build.sh` standalone -- an actual decision either way, not made by
  this commit.
- **Not the same as this project's own prefix.** `build.sh` configures
  with `--prefix=$TERMUX_PREFIX` (Termux's own
  `/data/data/com.termux/files/usr`). Even a successful build from
  this exact recipe still bakes in Termux's prefix unless that's
  overridden -- which is a real, separate piece of work (see "What
  integration will actually require" below), not something fixed by
  vendoring the recipe alone.

## Licensing

Per `LICENSE.md` (vendored alongside, also verbatim): termux-packages'
build infrastructure itself is Apache-2.0, but "the scripts and
patches to build each package is licensed under the same license as
the actual package" -- so `nodejs-lts/build.sh` and its patches are
under Node.js's own license (MIT), consistent with ARKware's
GPLv3-or-later (`../../LICENSE`): both MIT and Apache-2.0 are
one-way-compatible as inbound licenses into a GPLv3 project.

## What integration will actually require (not started here)

In the order `VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 7's phase
table implies (Phase 2, "Real Node binary sourced per section 5's
eventual decision"):

1. A real build environment capable of running this recipe -- either
   termux-packages' own Docker-based one, or a from-scratch
   reimplementation of the handful of `termux_step_*` hooks
   `build.sh` actually calls (host ICU build, host LLVM toolchain
   fetch, `ninja` invocation, `tools/install.py`).
2. Re-pointing `--prefix` away from `$TERMUX_PREFIX` at this project's
   own install location, and re-checking every patch in this
   directory for prefix-path assumptions beyond the `configure` line
   itself (several of the `.patch` files touch build-time include/
   library search paths).
3. Confirming the resulting binary actually runs standalone outside
   any Termux-provided shared library search path -- `build.sh`'s
   `TERMUX_PKG_DEPENDS` line (`libc++, openssl, c-ares, libicu,
   libsqlite, zlib`) lists real shared-library dependencies this
   recipe assumes are present at runtime, which on Android means
   bundling each of those under `jniLibs/` too (same `noexec`
   reasoning as the Node binary itself -- see
   `VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 0.1/4.1), not just
   `libnode.so` alone.
4. Only once all of the above is proven does packaging per
   `VSCODE-IDE-IMPLEMENTATION-PLAN.md` section 4.1 (renaming the
   resulting binary `libnode.so`, placing per-ABI copies under
   `app/src/main/jniLibs/`) become the relevant next step.

None of this is started by vendoring the recipe -- this commit is
sourcing only, per the instruction that produced it.
