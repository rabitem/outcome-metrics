# OpenSSF Best Practices Badge

This project participates in the
[OpenSSF Best Practices Badge](https://www.bestpractices.dev/) program
(Scorecard check
[CII-Best-Practices](https://github.com/ossf/scorecard/blob/main/docs/checks.md#cii-best-practices)).

## Status

- **BadgeApp project:** [13963](https://www.bestpractices.dev/projects/13963) — **Passing (100%)**
- **Repo proposals file:** [`.bestpractices.json`](../.bestpractices.json)
  (read by BadgeApp automation on edit / “Save and continue 🤖”)

README badge:

```markdown
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/13963/badge)](https://www.bestpractices.dev/projects/13963)
```

Even an in-progress entry raises the Scorecard CII check from `0` to `2`.
A passing badge scores `5`.

## Automation proposals (URL)

External tools can propose criterion values via a project edit URL
([docs](https://github.com/ossf/best-practices-badge/blob/main/docs/automation-proposals.md)).
Example (baseline access-control criteria):

https://www.bestpractices.dev/en/projects/13963/choose/edit?osps_ac_01_01_status=Met&osps_ac_01_01_justification=GitHub+enforced+MFA&osps_ac_03_01_status=Met&osps_ac_03_01_justification=Ruleset+requires+PRs+on+main&overrides=osps_ac_*

Or look up by repository URL:

https://www.bestpractices.dev/en/projects?as=edit&url=https%3A%2F%2Fgithub.com%2Frabitem%2Foutcome-metrics&section=baseline-1

After opening an edit URL while logged in as a project owner, review the
yellow/orange highlighted proposals and save.

## Passing checklist (initial)

| Criterion area | Evidence |
|---|---|
| Public VCS / HTTPS | GitHub repo |
| License | [`LICENSE`](../LICENSE) (MIT) |
| Documentation | [`README.md`](../README.md), [`docs/handbook/`](handbook/) |
| Contribution process | [`CONTRIBUTING.md`](../CONTRIBUTING.md), DCO workflow |
| Security reporting | [`SECURITY.md`](../SECURITY.md), private vulnerability reporting |
| Build / tests | `./mvnw -B verify`, [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) |
| Dependency updates | [`.github/dependabot.yml`](../.github/dependabot.yml) |
| Static analysis | [`.github/workflows/codeql.yml`](../.github/workflows/codeql.yml) |
| Supply-chain scorecard | [`.github/workflows/scorecard.yml`](../.github/workflows/scorecard.yml) |
| Fuzzing | [`fuzz/`](../fuzz/), [`.clusterfuzzlite/`](../.clusterfuzzlite/) |
