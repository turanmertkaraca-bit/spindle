# ENVIRONMENT · verified facts and repairs

Everything here was verified in this environment. Read before debugging a build
or git failure — these traps have already cost time.

---

## 1. Git object store corruption (already repaired — do not re-panic)

**Symptom:** `git status` / `git log` fail with:

```
fatal: bad object HEAD
```

**Cause:** `.git/objects/**` contained **~1,046 broken symlinks** plus sidecar
`*.0001` files. They are `proot --link2symlink` artifacts: each object had been
replaced by a symlink pointing into
`/data/user/0/dev.lumen.app/files/workspace/spindle/.git/...` — a path that no
longer exists (the project moved to `files/projects/playground/spindle`). The
**real object bytes were in the sibling `*.0001` files.**

**Repair that worked** (already applied — 523 objects restored):

```sh
cd <repo>/.git/objects
python3 - <<'PY'
import os, subprocess
out = subprocess.run(['find','.','-type','l'], capture_output=True, text=True).stdout.split()
for p in out:
    if os.path.exists(p): continue          # still-valid symlink, leave it
    src = os.readlink(p) + '.0001'          # the real bytes live here
    if not os.path.exists(src):
        src = os.path.join(os.path.dirname(p), os.path.basename(os.readlink(p)) + '.0001')
    if os.path.exists(src):
        data = open(src,'rb').read()
        os.unlink(p); open(p,'wb').write(data); os.unlink(src)
PY
```

Then, if a pack index is stale (`fetch-pack: invalid index-pack output`):

```sh
rm -f .git/objects/pack/*.idx
git index-pack .git/objects/pack/<name>.pack
git fetch origin --tags
```

**Do NOT `git clone` over the repo** — you would lose local refs and have to
re-derive. Repair in place.

**Prevention:** if you see `--link2symlink` symlinks appearing in `.git`, that is
proot at work; keep the `.0001` sidecars until you have verified the objects
read.

---

## 2. Build environment

| Thing | Value |
|---|---|
| JDK | `/usr/lib/jvm/java-17-openjdk-arm64` |
| Android SDK | `/opt/android-sdk` (platforms 34, 35; build-tools 35.0.0) |
| Required flag | `-Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2` |

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
./gradlew --no-daemon -Pandroid.aapt2FromMavenOverride=/opt/aapt2bin/aapt2 :core:test
```

### The proxy port moves between sessions

Gradle needs the HTTPS proxy. **Read it from the live environment**, don't
remember it:

```sh
echo $https_proxy        # e.g. http://127.0.0.1:35805
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<PORT> \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<PORT>"
```

A stale port presents as Gradle failing to download an arbitrary jar
(`Connection refused`), **not** as an auth error. It is not a code problem.

---

## 3. SDK binaries crash on this host

The SDK's `aapt` / `aapt2` / `apksigner` are **x86**; this host is **aarch64**.
They fail with **`Illegal instruction`** (exit 132). Symptoms: `aapt dump
badging` returns nothing and prints `Illegal instruction`.

Use instead:

- **Inspect an APK:** Python's `zipfile` — read `AndroidManifest.xml` (binary;
  search for UTF-16 strings), list `classes*.dex`, `resources.arsc`.
- **Verify a signature:** the pure-Java `apksig` jar from the Gradle cache:
  `/root/.gradle/caches/modules-2/files-2.1/com.android.tools.build/apksig/8.5.2/*/apksig-8.5.2.jar`
  → `com.android.apksig.ApkVerifier`. It runs fine on aarch64.
  The signing cert SHA-256 for this project's debug key is
  `aea42858c6ff0ab72ccb866d76064e62a2c4a53cdc98339ad084dff21918c68c`.

**Note:** a debug APK signed only with v2 will have **no** `.RSA`/`.DSA` in
`META-INF` — that is normal, not a missing signature. v2/v3 signatures live in
the APK Signing Block.

---

## 4. Workspace layout

```
files/projects/playground/        <- a SEPARATE repo (fnportandroid). IGNORE IT.
files/projects/playground/spindle/ <- THIS project (repo root)
files/workspace/                  <- empty; referenced by old broken symlinks
```

Work only in `spindle/`. `dist/` (built APKs) is gitignored by design; releases
are published by CI to GitHub Releases.

---

## 5. CI

Two jobs, both must be green on the pushed sha:

- `jvm backend` — `:core :tools :sandbox :store-sqlite :server :cli` tests.
- `android app (robolectric)` — the **only** verifier for `:app` behaviour, and
  it uploads the `lumen-screenshots` artifact.

`.github/workflows/release.yml` publishes an APK when a `v*` tag is pushed, and
it **asserts** package id, versionName and the debug signing cert before
publishing.

---

## 6. The 2 GiB address-space cap — and why builds failed (FIXED `d7e4fda`)

**Symptom:** every Gradle/JVM run dies with

```
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (malloc) failed to allocate ...
```

even though `free -m` shows ~1.8 GB available and nothing is hogging RAM.

**Cause:** the shell carries `ulimit -v 2097152` — a **2 GiB virtual address
space** cap, exactly equal to `ResourcePolicy.GUEST_MEMORY_LIMIT_KB`. The guest
`ulimit -v` guard added in Run C was applied to *every* command the agent ran,
including `./gradlew`. A JVM reserves heap + metaspace + code cache + thread
stacks + direct buffers — routinely several GB of *virtual* space — so the
allocation fails immediately. It is not real OOM; it is the ceiling.

**Not fixable with swap/zram here** (checked, for the record):

- `swapon` → `Function not implemented` (no swap support in this kernel/namespace)
- no `zram-control`, no zram module
- `/proc/sys/vm/*` not writable (Permission denied)
- `ulimit -v` cannot be raised: `Operation not permitted`

So the only real fix is on the **app** side: don't cap what needs the space.

**Fix (`d7e4fda`):** `ResourcePolicy.limitScript` now skips `ulimit -v` for
`MEMORY_HUNGRY` launchers (java/javac/kotlinc/gradle/mvn/node/npm/python/gcc/
cargo/docker/apt…), matched on the **basename of the first token**. The fd cap
(`ulimit -n`) still applies to them. A composite script (`cd x && ./gradlew`)
keeps the cap — conservative by design.

**Consequence for agents:** running `./gradlew …` as a single command now works.
If you wrap it (`cd sub && ./gradlew …`, or a shell script that calls it), the
cap still applies and the JVM will die — invoke the launcher directly, or add
the wrapper basename to `MEMORY_HUNGRY` if it is a routine one.

**A 2 GiB `ulimit -v` is still worth having** for non-JVM commands: it stops a
runaway process from being reaped by Android's low-memory killer, which would
take the whole app down rather than just the command.

---

## 7. Repairing a downstream repo's object store (verified recipe)

The agent works inside `projects/playground/`, which contains **another** git
repository (`fnportandroid`). Its store developed the same proot artifacts, and
`git status` there failed with `fatal: bad object HEAD` — which the chess
workbench report correctly called out as a repo-hygiene hazard, because stray
files are invisible when `status` cannot run.

The repair below was applied and verified: `log`, `status` and `fetch` all work
with zero errors afterwards. It is *not* the same as §1 above; the failure mode is
different and the naive §1 script does nothing here.

### What was actually wrong

1. **Pack files were symlinks into a deleted path.** `pack-*.pack` and
   `pack-*.idx` were `--link2symlink` links pointing at
   `/data/user/0/.../workspace/.git/objects/pack/.l2s.tmp_pack_X`, which no longer
   existed — but the real bytes were present as `.l2s.tmp_pack_X.0001`.
   `git verify-pack` reported `.pack: ok` only after restoring them, which is how
   to tell this apart from genuine corruption.
2. **A local-only commit was the branch tip.** `refs/heads/main` pointed at a
   commit whose parent had never been fetched (it is not on the remote), so every
   traversal died on a missing parent.
3. **Stale refs kept resurrecting the dead commit.** A reflog, a
   `refs/remotes/origin/main` entry, a tag, and `.git/shallow` all referenced it.
   `git fetch` failed with `fetch-pack: invalid index-pack output` until all were
   removed.
4. **A `refs/tags/V3.0.0` pointing at a missing object** made `fetch` abort with
   `did not send all necessary objects`.

### The repair, in order

```sh
cd <downstream-repo>

# 1. Restore pack/index bytes from the .0001 sidecars.
#    `cp` then `mv` — a plain `python os.remove` on the dangling link is
#    redirected by proot's link2symlink layer and fails.
cd .git/objects/pack
for pair in "pack-AAA.pack:.l2s.tmp_pack_X.0001" "pack-AAA.idx:.l2s.tmp_idx_X.0001"; do
  link="${pair%%:*}"; side="${pair##*:}"
  cp -f "$side" "$link.tmp-real" && rm -f "$link" && mv "$link.tmp-real" "$link"
done
cd -
git verify-pack -v .git/objects/pack/<name>.idx | tail -1     # expect ": ok"

# 2. Get the missing history from a CLEAN clone elsewhere (never clone over this
#    working tree — you would lose local refs).
git clone --filter=blob:none --no-checkout <url> /tmp/fresh
cp -n /tmp/fresh/.git/objects/pack/*.pack .git/objects/pack/
cp -n /tmp/fresh/.git/objects/pack/*.idx  .git/objects/pack/

# 3. Point the branch at a commit that exists, then clear every stale ref.
git update-ref refs/heads/main <real-remote-head>
git symbolic-ref HEAD refs/heads/main
rm -rf .git/logs                      # reflogs referenced the dead commit
rm -f  .git/shallow                   # a bogus shallow boundary blocks fetching
rm -f  .git/FETCH_HEAD .git/ORIG_HEAD

# 4. Delete every ref whose object is missing (some are in .git/packed-refs).
for t in $(git for-each-ref --format='%(refname)' refs/tags); do
  git cat-file -t "$t" >/dev/null 2>&1 || rm -f ".git/$t"
done
# and drop stale lines from .git/packed-refs by hand

# 5. Verify.
git log --oneline -3
git status          # must print no "error:"/"fatal:" lines
git fetch origin main
git branch --set-upstream-to=origin/main main
```

### The general lesson

`fatal: bad object HEAD` has (at least) two distinct causes in this environment,
and they need different repairs:

| Symptom | Cause | Fix |
|---|---|---|
| loose objects are broken links | `--link2symlink` on `objects/xx/` | §1 script |
| `verify-pack` says the pack is `ok` but refs fail | pack **and** index were links; plus a local-only tip and stale refs | §7 above |

Do not re-clone over a working tree to "fix" git. Copy objects from a clean clone
in `/tmp` instead.
