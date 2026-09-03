# BUGS-CAUGHT

> Active bug tracker.
> Bugs remain here until they are fixed, tested, and confirmed working.
> Once confirmed, they are removed from this file.
> This is just the template, create a new bug-name.md file and track the specific issue there.

---

## Active Bugs

<!--
Add unfixed bugs here.

Template:

### BUG-XXXX: Short description
- **Status:** `UNFIXED`
- **Found:** YYYY-MM-DD
- **Stage:** `v1 (Android) | v2 (desktop window mode) | v3 (desktop chrome mode)`
- **Location:** `path/to/file:line`
- **Severity:** `Critical | High | Medium | Low`
- **Description:**
  What is going wrong.

- **Expected:**
  What should happen.

- **Actual:**
  What happens instead.

- **Reproduction:**
  1. Step one
  2. Step two
  3. Step three

- **Likely cause:**
  Suspected cause, if known.

- **Fix:**
  What needs to be changed.

- **Test:**
  How the fix must be verified.

- **Notes:**
  Additional information.
-->

_(No code yet -- Stage 0. First entries land once v1 (Android)
implementation starts.)_

---

## Rules

1. Every discovered bug gets an entry under **Active Bugs**.
2. Do not remove a bug merely because a fix was written.
3. A bug is removable only when:
   - the fix is implemented,
   - the relevant test passes,
   - the original reproduction no longer fails,
   - and the fix does not introduce a regression.
4. Once all verification succeeds, remove the bug from this file.
5. Do not keep a separate "Fixed Bugs" section here. Git history, commits, PRs, or changelogs should provide the historical record.
6. If a supposedly fixed bug reappears, create a new entry with a new bug ID and reference the previous fix in `Notes`.
7. Keep entries focused on observable failures rather than vague concerns or speculative cleanup.
8. Tag every entry with **Stage** (`v1`/`v2`/`v3`) -- ARKware spans three runtimes, and a bug's runtime is part of diagnosing it, not incidental.

---

## Verification Standard

A bug may be removed only after:

```text
Bug reproduced
    ↓
Root cause identified
    ↓
Fix implemented
    ↓
Build/compile succeeds
    ↓
Regression test passes
    ↓
Original reproduction passes
    ↓
Fix confirmed working
    ↓
BUG REMOVED
````

---

## Bug Entry Template

```md
### BUG-XXXX: Short description
- **Status:** `UNFIXED`
- **Found:** YYYY-MM-DD
- **Stage:** `v1 | v2 | v3`
- **Location:** `path/to/file:line`
- **Severity:** `Critical | High | Medium | Low`

- **Description:**
  ...

- **Expected:**
  ...

- **Actual:**
  ...

- **Reproduction:**
  1. ...
  2. ...
  3. ...

- **Likely cause:**
  ...

- **Fix:**
  ...

- **Test:**
  ...

- **Notes:**
  ...
```
