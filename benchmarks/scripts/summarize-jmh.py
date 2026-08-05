#!/usr/bin/env python3
"""Summarize JMH JSON results into a markdown table (AverageTime / ns/op)."""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <jmh-result.json>", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    rows = json.loads(path.read_text())
    print("| Benchmark | Mode | Threads | Score | Error | Unit |")
    print("|---|---|---:|---:|---:|---|")
    for row in sorted(rows, key=lambda r: r["benchmark"]):
        primary = row["primaryMetric"]
        score = primary["score"]
        error = primary.get("scoreError", float("nan"))
        unit = primary["scoreUnit"]
        mode = row.get("mode", "?")
        threads = row.get("threads", 1)
        short = row["benchmark"].rsplit(".", 1)[-1]
        print(
            f"| `{short}` | {mode} | {threads} | {score:,.2f} | {error:,.2f} | {unit} |"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
