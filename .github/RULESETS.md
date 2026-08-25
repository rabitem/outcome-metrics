# Repository rulesets

This documents the intended GitHub ruleset configuration for OpenSSF Scorecard
[Branch-Protection](https://github.com/ossf/scorecard/blob/main/docs/checks.md#branch-protection).

Settings live in GitHub (Rules → Rulesets), not in this file. Keep this doc in
sync when changing rules.

## Protect main (`~DEFAULT_BRANCH`)

| Setting | Value | Rationale |
|---|---|---|
| Restrict deletions | on | Prevent accidental branch deletion |
| Block force pushes | on | Preserve history |
| Require a pull request | on | No direct pushes to `main` |
| Required approvals | ≥ 1 | Human review before merge |
| Dismiss stale reviews | on | Re-review after new commits |
| Require review from Code Owners | on | Route review via `.github/CODEOWNERS` |
| Require approval of the most recent push | on | Last pusher cannot self-approve |
| Require conversation resolution | on | Close review threads before merge |
| Require status checks | `Verify (JDK 21)`, `Verify (JDK 25)`, `DCO`, `Analyze (Java)` | CI + DCO + CodeQL |
| Require branches to be up to date | on | Merge with latest `main` |
| Require Code Scanning results | CodeQL errors / high+ security | Block known scanner findings |

### Admin bypass

A repository-admin bypass remains enabled so a solo maintainer can still land
emergency fixes. Scorecard treats any bypass actor as “not enforced for
administrators”; that trade-off is intentional until a second human reviewer
is available.

## Protect version tags (`refs/tags/v*`)

| Setting | Value |
|---|---|
| Restrict deletions | on |
| Block force pushes | on |
| Restrict creations / updates | off for solo maintainers (admins create `v*` tags to cut releases) |

Immutable GitHub Releases still prevent mutating a published release’s assets.
Do not reuse a tag name after a published immutable release — cut the next
semver instead (`v0.1.0-beta.1` is burned; use `v0.1.0-beta.4`+).
