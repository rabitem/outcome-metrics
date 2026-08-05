# Cardinality

Unique time series drive Prometheus cost. Bound tag values before they hit production.

## Do / don't

| Do | Don't |
|---|---|
| Enums, channels, command names | User id, email, order id, UUID |
| `OutcomeReasonSource` codes | Exception class names as SLO reasons |
| `tag-limits` on hot dimensions | Hope scrape size stays flat |

## Config

```yaml
outcome:
  metrics:
    max-meters: 50000
    tag-limits:
      - meter-name-prefix: order.   # startsWith — keep the trailing dot
        tag-key: channel
        maximum-values: 32
```

When the limit is hit, further values become `other`. The meter still exists (unlike Micrometer `DENY`).

| Knob | Behavior |
|---|---|
| `tag-limits[]` | Remap excess values → `other` |
| `outcome.metrics.tag_value_overflows` | Gauge of remap events |
| `max-meters` | Hard registry size ceiling |
| `cache.normalize-tags` | Strip noisy cache tags |

Prefix tip: `order.` is safe; `ord` also matches `orderly.send`.

## Reading the overflow gauge

It counts **remap events**, not distinct rejected values. One overflowing observation can increment more than once if Micrometer registers multiple meters for it (timer + long task). Use it as a pressure signal.

If `other` dominates traffic, fix the producers or raise the limit deliberately — don't ignore it.
