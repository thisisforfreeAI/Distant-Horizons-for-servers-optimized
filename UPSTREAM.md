# Upstream baseline

This repository is a fork of Distant Horizons (LGPL-3.0) for the
Distant Horizons Server Optimized (DHSO) project.

- Upstream: https://gitlab.com/distant-horizons-team/distant-horizons
- Tag: `3.3.3` (commit `a54dd3eb1df0078e63a71266d042c10d9cda4493`)
- Core submodule `coreSubProjects`: https://gitlab.com/jeseibel/distant-horizons-core
  at commit `b02c66d778beea11a931291815d652cd8abaa7ad`, vendored in place
  (no longer a git submodule).

This first commit is the unmodified upstream source. All DHSO changes come
in later commits so they can be diffed against it.

Build (Java 25): `./gradlew neoforge:assemble -PmcVer=26.2.0`
