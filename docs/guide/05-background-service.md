# Background execution (`RunService`)

Verified against HEAD `4ff37c0`. `app/src/main/kotlin/dev/lumen/app/platform/RunService.kt`.
Last changes: `fed3b0e` (lifecycle), plus the wake-lock renewal in Run C.

A run must keep going when the user switches apps, and must **not** keep going
when the user stops it. This service is the whole lifecycle.

## Return value and restart policy

`onStartCommand` returns **`START_NOT_STICKY`**. The service never restarts an
interrupted run on its own: a half-finished agent run silently resumed in the
background has no user watching it and can hammer tools and network unattended.
Recovery is `RunRecovery` at the next explicit start, not a platform restart.

## The start contract

- `startForeground` must succeed within the platform's 5 s window.
- A failure is **deliberately not swallowed** (`RunService.kt:120`): if
  `startForeground` throws, the run is torn down immediately, because a
  background service that failed to go foreground will be killed anyway and
  leaving the run alive produces orphaned work and a stuck notification.
- The Stop action arrives via `startForegroundService`, so `onStartCommand` must
  handle a start that only means "stop".

## Wake lock

- `PARTIAL_WAKE_LOCK` with `setReferenceCounted(false)`, so the lock is owned
  outright by the service: exactly one acquire and one release, regardless of
  how many times progress renews it. `wakeLockReleases` tracks whether an
  acquire was paired with a release, so teardown never double-releases.
- `acquireWakeLock()` is guarded by `if (wakeLock?.isHeld == true) return`, so a
  progress-driven renewal cannot acquire twice.
- **A failed stop-handler must not drop the wake lock.** If teardown throws, the
  lock is kept so the process is not suspended mid-teardown (fixed in `fed3b0e`;
  before that a throwing stop handler left work running with no lock).
- The lock is renewed on progress, and released on both the normal and the
  error path. Release checks `isHeld` first in one place and clears the field in
  the other — use the existing helpers rather than calling `release()` directly.

## Notifications

One notification id (`NOTIFICATION_ID`) for the run, carrying the Stop action and
progress. Updating it on progress is what keeps the service legitimately
foreground during a long run.

## Stop semantics

User Stop is a **cancellation**, not a kill. The stop path:

1. Cancels the run's coroutine scope.
2. Waits for the agent loop to unwind (its `ensureActive` re-checks turn a
   cancellation into a clean unwind rather than a mislabelled timeout).
3. Flips any `PENDING` tool parts to `ERROR` so the UI has no spinner.
4. Releases the wake lock and removes the notification.

## When you touch this file

The dangerous deletions are the ones that look like cleanup:

- Removing the `startForeground` re-throw → orphaned runs.
- Removing the "stop-handler failure keeps the lock" branch → suspended teardown.
- Changing `START_NOT_STICKY` → unattended resumed runs.
- Removing the `isHeld` guard → wake-lock reference leak (battery drain that
  outlives the app).

`RunServiceTest` is Robolectric, so it only runs on CI.
