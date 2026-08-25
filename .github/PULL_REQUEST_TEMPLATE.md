## TL;DR

<!-- One or two sentences: what changed and why. Lead with the outcome, not the mechanics. -->

## Changes

<!-- What was added/changed/removed. Call out deliberate design departures explicitly. -->

## Checklist

- [ ] `./mvnw -B verify` passes locally (and `./mvnw -f samples/pom.xml -B verify` if samples are affected)
- [ ] Tests cover new/changed behavior — including the failure path and label-set consistency for new tags
- [ ] New tag values come from **closed vocabularies**; nothing unbounded reaches a tag
- [ ] CHANGELOG.md updated (for user-visible changes)
- [ ] Docs updated (handbook page + `llms.txt` for API changes)
- [ ] No deprecated compatibility shims added
- [ ] Commits are signed off (`git commit -s` — DCO is enforced in CI)
