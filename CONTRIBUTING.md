# Contributing

Thanks for helping improve outcome-metrics.

## Development

- JDK 21+
- Maven 3.9+ (wrapper provided)

```bash
./mvnw -B verify                                  # library reactor (all modules)
./mvnw -f outcome-metrics-bom/pom.xml -B validate # BOM
./mvnw -f samples/pom.xml -B verify               # samples (install reactor + BOM first)
```

Module map: core (`outcome-metrics`), framework adapters (`-spring-boot-starter`,
`-quarkus`), reactive bindings (`-reactor`, `-mutiny`), build/test tooling (`-processor`,
`-test`), and the `-bom`. Start with the [developer handbook](docs/handbook/README.md).

## Pull requests

1. Open an issue first for larger API/behavior changes — the feature template asks the design
   questions this library holds every change to (closed vocabularies, failure-path label sets,
   which layer, never-throw telemetry).
2. Keep PRs focused; include tests for new behavior — including the failure path.
3. Do not add deprecated compatibility shims for removed APIs — bump the major instead.
4. Use clear commit messages (Conventional Commits welcome: `feat:`, `fix:`, `docs:`).
5. Update the handbook page and `llms.txt` for user-visible API changes, and CHANGELOG.md.
6. Sign off commits with a DCO trailer (**enforced in CI** — unsigned commits fail the DCO check):

```
Signed-off-by: Your Name <you@example.com>
```

(`git commit -s`)

## Code review

Human review is required before merging to `main` (OpenSSF Scorecard
[Code-Review](https://github.com/ossf/scorecard/blob/main/docs/checks.md#code-review)
and Branch-Protection).

- Every PR needs **at least one approving review** from someone other than the
  last pusher.
- Changes matching `.github/CODEOWNERS` need a **Code Owner** approval
  (`@rabitem` today).
- Bot reviews (Dependabot, AI bots, etc.) do **not** count as human review for
  Scorecard.
- Prefer merging via GitHub’s merge / squash / rebase UI after approval so the
  review is recorded on the changeset.
- Maintainers: do not routinely bypass review with admin privileges except for
  emergencies; bypass weakens the Code-Review signal.

## Code style

- Match existing formatting and Javadoc level.
- Nullness: prefer explicit checks / JSpecify where already used.
- No RH/product-specific package names or config keys.

## Reporting security issues

See [SECURITY.md](SECURITY.md).
