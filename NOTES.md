# Known issues / deferred work

## POH capture: guest houses are indistinguishable from your own

The house facility scan commits whenever the player stands in a POH region
(`HouseStore` commit gate in `CopilotPlugin.onGameTick`), but a friend's
house occupies the same regions. Visiting a host's house — common for
gilded altar training — overwrites the stored scan with *their*
facilities until the next visit home.

Impact is self-healing (one home visit rewrites the store) but the stale
window can mislead transport/prep answers.

Fix direction: gate the commit on an "in your own house" signal. Candidates
to investigate in-game with `::probe` / `::house` from a friend's house:

- a varbit/varp distinguishing owner from guest (building mode is
  owner-only but not always on, so it can confirm but not gate alone)
- the entry chat message ("You are now in your house." vs. the guest
  variant naming the owner)
