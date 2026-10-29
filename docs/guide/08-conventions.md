# Conventions and hard-won rules

Each rule below exists because breaking it caused a real bug in this repo.

## Never leave a tool part `PENDING`

Every abort path in `AgentLoop` must rewrite the turn's tool parts to
`ToolState.ERROR` before it exits. The UI renders `PENDING` as a spinner forever.
If a spinner sticks, the bug is the loop's exit path.

## Cancellation is not a timeout

Catch `CancellationException` and rethrow it. For
`TimeoutCancellationException`, re-check `currentCoroutineContext().ensureActive()`
before reporting a timeout, or a user Stop is mislabelled as a slow tool.

## Don't block the main thread on admission control

`ResourcePolicy.acquire` must only `tryAcquire` on the main looper. A 60 s block
is an ANR. Any new gate must follow the same shape: detect, don't block.

## Limits go on the guest script, not on proot

`ulimit` is a shell builtin. Applying it to `proot` limits the shim, not the
guest tree. Join guards with `;`, not `&&`, and tolerate each one's failure.

## Don't set a CPU-time ulimit

A real build can exceed any fixed number. Bound wall clock with the per-tool
deadline, which is precise.

## Migrations must tolerate an older schema

Never `throw` inside a migration loop — it aborts mid-migration and leaves the
database between versions. Filter with `mapNotNull`, never `DROP` to migrate, and
index a new `session_id` column in the same migration that adds it.

## Path arguments always go through `resolveInsideCwd`

Four throw sites exist in `Support.kt`. Another code path that builds a `Path`
from a tool argument without it is a sandbox escape.

## Identity is the id, never the normalised name

`ProjectStore` collided two projects that normalised to the same key.
Normalisation is for lookup only.

## Scrub the host environment before any guest command

Both the Debian and Alpine paths call `scrubHostEnv`. A new spawn point that
forgets it leaks the app's ambient credentials into the guest.

## `startForeground` failure is fatal to the run

Do not swallow it. Background work that failed to go foreground will be killed
anyway; leaving it alive produces orphaned work.

## Keep `START_NOT_STICKY`

A platform-restarted agent run has no user watching it. Recovery is explicit
(`RunRecovery`), never automatic.

## Verify with CI, not a local `:app` run

Robolectric cannot run in the dev sandbox. Locally you can only say "compiles".
Read both CI jobs before declaring success. See `07-testing.md`.

## Comment the *why*, and name the failure it prevents

The comments in this codebase that matter all say what breaks without them
("prepending nice before setsid would lower only setsid"). A comment that
restates the code is noise; a comment that records a bug is load-bearing.
