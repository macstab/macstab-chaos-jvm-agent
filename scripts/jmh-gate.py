#!/usr/bin/env python3
"""Release-over-release benchmark gate.

Compares two JMH JSON result files (``-rf json``) produced on the same machine, one from the base
revision and one from the candidate. A benchmark regresses only when all three hold:

* the candidate's median is more than 15 % above the base median,
* and more than 25 ns above it (sub-noise differences on nanosecond benchmarks do not count),
* and a one-sided Mann-Whitney U test over the raw iteration scores gives p < 0.01.

Benchmarks present in only one file are reported and skipped. Exit status 1 when any benchmark
regresses, 0 otherwise (also when the base file does not exist yet: nothing to compare against).

Usage: jmh-gate.py BASE.json CANDIDATE.json [--max-ratio 1.15] [--min-ns 25] [--alpha 0.01]
"""

import argparse
import json
import math
import os
import statistics
import sys


def load(path):
    with open(path, encoding="utf-8") as handle:
        results = json.load(handle)
    samples = {}
    for result in results:
        params = result.get("params") or {}
        key = result["benchmark"] + "".join(f" [{k}={v}]" for k, v in sorted(params.items()))
        metric = result["primaryMetric"]
        unit = metric["scoreUnit"]
        if unit not in ("ns/op", "us/op", "ms/op"):
            raise SystemExit(f"{key}: unsupported unit {unit} (use AverageTime or SampleTime)")
        scale = {"ns/op": 1.0, "us/op": 1e3, "ms/op": 1e6}[unit]
        raw = [value * scale for fork in metric["rawData"] for value in fork]
        samples[key] = raw
    return samples


def mann_whitney_greater(candidate, base):
    """One-sided p-value that candidate scores are stochastically greater than base scores."""
    n1, n2 = len(candidate), len(base)
    combined = sorted([(v, 0) for v in candidate] + [(v, 1) for v in base])
    ranks = [0.0] * len(combined)
    ties = 0.0
    i = 0
    while i < len(combined):
        j = i
        while j + 1 < len(combined) and combined[j + 1][0] == combined[i][0]:
            j += 1
        rank = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[k] = rank
        size = j - i + 1
        ties += size**3 - size
        i = j + 1
    rank_sum = sum(rank for rank, (_, group) in zip(ranks, combined) if group == 0)
    u = rank_sum - n1 * (n1 + 1) / 2.0
    mean = n1 * n2 / 2.0
    n = n1 + n2
    variance = n1 * n2 / 12.0 * ((n + 1) - ties / (n * (n - 1)))
    if variance <= 0:
        return 1.0
    z = (u - mean - 0.5) / math.sqrt(variance)  # continuity correction
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base")
    parser.add_argument("candidate")
    parser.add_argument("--max-ratio", type=float, default=1.15)
    parser.add_argument("--min-ns", type=float, default=25.0)
    parser.add_argument("--alpha", type=float, default=0.01)
    args = parser.parse_args()

    if not os.path.exists(args.base):
        print(f"no base results ({args.base}); nothing to compare against, gate passes")
        return 0
    base = load(args.base)
    candidate = load(args.candidate)

    regressions = 0
    print(f"{'benchmark':<70} {'base ns':>10} {'cand ns':>10} {'ratio':>7} {'p':>9}  verdict")
    for key in sorted(set(base) | set(candidate)):
        if key not in base or key not in candidate:
            where = "candidate" if key in candidate else "base"
            print(f"{key:<70} only in {where}, skipped")
            continue
        b, c = base[key], candidate[key]
        b_med, c_med = statistics.median(b), statistics.median(c)
        ratio = c_med / b_med if b_med > 0 else math.inf
        p = mann_whitney_greater(c, b)
        regressed = ratio > args.max_ratio and c_med - b_med > args.min_ns and p < args.alpha
        regressions += regressed
        verdict = "REGRESSED" if regressed else "ok"
        print(f"{key:<70} {b_med:>10.2f} {c_med:>10.2f} {ratio:>7.3f} {p:>9.2g}  {verdict}")
    if regressions:
        print(
            f"\n{regressions} benchmark(s) regressed: more than {(args.max_ratio - 1) * 100:.0f} %"
            f" and {args.min_ns:g} ns slower with p < {args.alpha:g}"
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
