# OpenSSF Best Practices Badge

This project participates in the
[OpenSSF Best Practices Badge](https://www.bestpractices.dev/) program
(Scorecard check
[CII-Best-Practices](https://github.com/ossf/scorecard/blob/main/docs/checks.md#cii-best-practices)).

## Status

Register (or update) the badge entry here:

https://www.bestpractices.dev/en/projects/new

Use repository URL:

`https://github.com/rabitem/outcome-metrics`

After the project exists on BadgeApp, add the badge to `README.md`:

```markdown
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/<ID>/badge)](https://www.bestpractices.dev/projects/<ID>)
```

Even an in-progress entry raises the Scorecard CII check from `0` to `2`.
A passing badge scores `5`.

## Passing checklist (initial)

Map evidence already in-repo when filling the form:

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
