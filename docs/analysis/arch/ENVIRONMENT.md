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
