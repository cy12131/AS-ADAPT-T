#!/usr/bin/env python3
"""Formal statistical analysis for the audited CCPE 54-run dataset."""

from __future__ import annotations

import csv
import math
import os
import re
import statistics
import tempfile
from collections import defaultdict
from datetime import datetime
from pathlib import Path

os.environ.setdefault(
    "MPLCONFIGDIR", str(Path(tempfile.gettempdir()) / "ccpe-54-mpl")
)
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"
AUDIT = ROOT / "analysis" / "output" / "ccpe-54-audit"
OUT = ROOT / "analysis" / "output" / "ccpe-54-statistical-analysis"
FIG = OUT / "figures"
T_CRITICAL = {2: 4.3026527299, 4: 2.7764451052}
ALGORITHMS = ["QUEUE_HYSTERESIS", "ADAPT_T", "AS_ADAPT_T"]
LABELS = {"QUEUE_HYSTERESIS": "Queue", "ADAPT_T": "ADAPT-T", "AS_ADAPT_T": "AS-ADAPT-T"}
COLORS = {"QUEUE_HYSTERESIS": "#D55E00", "ADAPT_T": "#0072B2", "AS_ADAPT_T": "#009E73"}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def meta(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def num(value) -> float:
    return float(value)


def integer(value) -> int:
    return int(float(value))


def iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def sample_sd(values: list[float]) -> float:
    return statistics.stdev(values) if len(values) > 1 else float("nan")


def fmt(value: float, digits: int = 6) -> str:
    return f"{value:.{digits}f}"


def na_or(value, digits: int = 6):
    return "NA" if value is None else fmt(float(value), digits)


def paired_log_effect(numerator: list[float], denominator: list[float]) -> dict:
    if len(numerator) != len(denominator) or len(numerator) < 2:
        raise ValueError("paired effect requires equally sized paired samples")
    logs = [math.log(a / b) for a, b in zip(numerator, denominator)]
    mean_log = statistics.fmean(logs)
    se = statistics.stdev(logs) / math.sqrt(len(logs))
    df = len(logs) - 1
    critical = T_CRITICAL[df]
    low, high = mean_log - critical * se, mean_log + critical * se
    transform = lambda value: (math.exp(value) - 1.0) * 100.0
    return {
        "mean_log_ratio": mean_log,
        "relative_change_percent": transform(mean_log),
        "ci95_low": transform(low), "ci95_high": transform(high),
        "n": len(logs), "df": df,
        "numerator_lower_count": sum(a < b for a, b in zip(numerator, denominator)),
        "numerator_higher_count": sum(a > b for a, b in zip(numerator, denominator)),
    }


def round_no(run_id: str) -> int:
    return int(re.search(r"-r(\d+)-", run_id).group(1))


def java_short(value: str) -> str:
    match = re.search(r'"([0-9.]+)"', value)
    return match.group(1) if match else value


def build_master() -> list[dict]:
    audit_runs = read_csv(AUDIT / "run_level_summary.csv")
    timings = {row["run_id"]: row for row in read_csv(AUDIT / "controller_timing_summary.csv")}
    result = []
    for source in audit_runs:
        run_id = source["run_id"]
        windows = read_csv(RESULTS / f"{run_id}-server.csv")
        metadata = meta(RESULTS / f"{run_id}-metadata.txt")
        successes = sum(integer(row["successful_requests"]) for row in windows)
        weighted_p99 = sum(integer(row["successful_requests"]) * num(row["p99_latency_ms"])
                           for row in windows) / successes
        timing = timings[run_id]
        algorithm = source["algorithm"]
        if algorithm == "QUEUE_HYSTERESIS":
            candidate = completed = accepted = rejected = unevaluated = rollback = None
            acceptance_rate = rejected_ratio = rollback_ratio = None
        else:
            candidate = integer(source["candidate_count"])
            accepted = integer(source["accepted_count"])
            rejected = integer(source["rejected_count"])
            completed = accepted + rejected
            unevaluated = candidate - completed
            rollback = integer(source["rollback_count"])
            acceptance_rate = accepted / completed if completed else None
            rejected_ratio = rejected / completed if completed else None
            rollback_ratio = rollback / completed if completed else None
        result.append({
            "algorithm": algorithm, "workload": "PRIME",
            "concurrency": source["concurrency"], "round": round_no(run_id),
            "run_id": run_id, "static_dynamic": source["dynamic_or_static"],
            "java_version": java_short(metadata["java_version"]),
            "duration_s": source["duration"], "request_count": source["request_count"],
            "success_count": source["success_count"], "failure_count": source["failure_count"],
            "throughput_req_s": source["throughput"],
            "mean_latency_ms": source["mean_latency"], "p50_ms": source["P50"],
            "p95_ms": source["P95"], "p99_ms": source["P99"],
            "p99_9_ms": source["P99.9"], "max_latency_ms": source["max_latency"],
            "weighted_window_p99_ms": fmt(weighted_p99), "A": source["A"], "V": source["V"],
            "candidate_count": "NA" if candidate is None else candidate,
            "completed_trial_count": "NA" if completed is None else completed,
            "accepted_count": "NA" if accepted is None else accepted,
            "rejected_count": "NA" if rejected is None else rejected,
            "unevaluated_candidate_count": "NA" if unevaluated is None else unevaluated,
            "rollback_count": "NA" if rollback is None else rollback,
            "acceptance_rate": na_or(acceptance_rate),
            "rejected_trial_ratio": na_or(rejected_ratio), "rollback_ratio": na_or(rollback_ratio),
            "stable_enter_count": source["stable_enter_count"],
            "recovery_count": source["recovery_count"],
            "time_to_first_stable_s": source["time_to_first_stable"],
            "stable_ratio": source["stable_ratio"],
            "controller_time_mean_ms": fmt(num(timing["mean_ns"]) / 1e6),
            "controller_time_median_ms": fmt(num(timing["median_ns"]) / 1e6),
            "controller_time_p95_ms": fmt(num(timing["p95_ns_recomputed_nearest_rank"]) / 1e6),
            "controller_time_p99_ms": fmt(num(timing["p99_ns_recomputed_nearest_rank"]) / 1e6),
            "controller_time_max_ms": fmt(num(timing["max_ns"]) / 1e6),
            "controller_time_total_ms": fmt(num(timing["total_ns"]) / 1e6),
            "controller_fraction_percent": fmt(num(timing["total_ns"]) /
                                                      (num(source["duration"]) * 1e9) * 100, 9),
        })
    return sorted(result, key=lambda row: (row["static_dynamic"], str(row["concurrency"]),
                                            row["round"], row["algorithm"]))


MASTER_FIELDS = [
    "algorithm", "workload", "concurrency", "round", "run_id", "static_dynamic",
    "java_version", "duration_s", "request_count", "success_count", "failure_count",
    "throughput_req_s", "mean_latency_ms", "p50_ms", "p95_ms", "p99_ms", "p99_9_ms",
    "max_latency_ms", "weighted_window_p99_ms", "A", "V", "candidate_count",
    "completed_trial_count", "accepted_count", "rejected_count", "unevaluated_candidate_count",
    "rollback_count", "acceptance_rate", "rejected_trial_ratio", "rollback_ratio",
    "stable_enter_count", "recovery_count", "time_to_first_stable_s", "stable_ratio",
    "controller_time_mean_ms", "controller_time_median_ms", "controller_time_p95_ms",
    "controller_time_p99_ms", "controller_time_max_ms", "controller_time_total_ms",
    "controller_fraction_percent",
]


def stable_descriptives(master: list[dict]) -> list[dict]:
    metrics = {
        "throughput": "throughput_req_s", "mean_latency": "mean_latency_ms",
        "p95": "p95_ms", "p99": "p99_ms", "p99_9": "p99_9_ms",
        "weighted_window_p99": "weighted_window_p99_ms", "A": "A", "V": "V",
    }
    rows = []
    for concurrency in (10, 100, 500):
        for algorithm in ALGORITHMS:
            group = [row for row in master if row["static_dynamic"] == "static"
                     and integer(row["concurrency"]) == concurrency and row["algorithm"] == algorithm]
            output = {"concurrency": concurrency, "algorithm": algorithm, "n": len(group)}
            for label, field in metrics.items():
                values = [num(row[field]) for row in group]
                output[f"{label}_mean"] = fmt(statistics.fmean(values))
                output[f"{label}_sd"] = fmt(sample_sd(values))
            for label, field in [("candidate", "candidate_count"), ("completed_trial", "completed_trial_count"),
                                 ("rejected", "rejected_count"), ("rollback", "rollback_count")]:
                if algorithm == "QUEUE_HYSTERESIS":
                    output[f"{label}_mean"] = "NA"
                    output[f"{label}_median"] = "NA"
                else:
                    values = [num(row[field]) for row in group]
                    output[f"{label}_mean"] = fmt(statistics.fmean(values))
                    output[f"{label}_median"] = fmt(statistics.median(values))
            rows.append(output)
    return rows


def stable_effects(master: list[dict]) -> list[dict]:
    comparisons = [
        ("AS_ADAPT_T_vs_ADAPT_T", "AS_ADAPT_T", "ADAPT_T"),
        ("AS_ADAPT_T_vs_QUEUE", "AS_ADAPT_T", "QUEUE_HYSTERESIS"),
        ("ADAPT_T_vs_QUEUE", "ADAPT_T", "QUEUE_HYSTERESIS"),
    ]
    metrics = {
        "throughput": "throughput_req_s", "mean_latency": "mean_latency_ms",
        "p95": "p95_ms", "p99": "p99_ms", "p99_9": "p99_9_ms",
        "weighted_window_p99": "weighted_window_p99_ms", "A": "A", "V": "V",
    }
    output = []
    for concurrency in (10, 100, 500):
        for comparison, numerator_name, denominator_name in comparisons:
            for metric, field in metrics.items():
                numerator = sorted([row for row in master if row["static_dynamic"] == "static"
                                    and integer(row["concurrency"]) == concurrency
                                    and row["algorithm"] == numerator_name], key=lambda row: row["round"])
                denominator = sorted([row for row in master if row["static_dynamic"] == "static"
                                      and integer(row["concurrency"]) == concurrency
                                      and row["algorithm"] == denominator_name], key=lambda row: row["round"])
                effect = paired_log_effect([num(row[field]) for row in numerator],
                                           [num(row[field]) for row in denominator])
                output.append({
                    "concurrency": concurrency, "comparison": comparison, "metric": metric,
                    "relative_change_percent": fmt(effect["relative_change_percent"]),
                    "ci95_low": fmt(effect["ci95_low"]), "ci95_high": fmt(effect["ci95_high"]),
                    "mean_log_ratio": fmt(effect["mean_log_ratio"], 9), "n": effect["n"],
                    "df": effect["df"], "numerator_lower_count": effect["numerator_lower_count"],
                    "numerator_higher_count": effect["numerator_higher_count"],
                    "analysis_method": "paired log-ratio; t CI on mean log-ratio",
                })
    return output


def overhead(master: list[dict]) -> list[dict]:
    rows = []
    for scope, concurrency in [("static", "10"), ("static", "100"), ("static", "500"),
                               ("dynamic", "100-10-200-0-50")]:
        for algorithm in ("ADAPT_T", "AS_ADAPT_T"):
            group = [row for row in master if row["static_dynamic"] == scope
                     and row["concurrency"] == concurrency and row["algorithm"] == algorithm]
            output = {"static_dynamic": scope, "concurrency": concurrency,
                      "algorithm": algorithm, "n": len(group)}
            for field in ("A", "V", "candidate_count", "completed_trial_count", "accepted_count",
                          "rejected_count", "unevaluated_candidate_count", "rollback_count"):
                values = [num(row[field]) for row in group]
                output[f"{field}_mean"] = fmt(statistics.fmean(values))
                output[f"{field}_median"] = fmt(statistics.median(values))
                output[f"{field}_sd"] = fmt(sample_sd(values))
            for field in ("acceptance_rate", "rejected_trial_ratio", "rollback_ratio"):
                values = [num(row[field]) for row in group]
                output[f"{field}_mean"] = fmt(statistics.fmean(values))
            rows.append(output)
    return rows


def overhead_effects(master: list[dict]) -> list[dict]:
    output = []
    for scope, concurrency in [("static", "10"), ("static", "100"), ("static", "500"),
                               ("dynamic", "100-10-200-0-50")]:
        as_rows = sorted([row for row in master if row["static_dynamic"] == scope
                          and row["concurrency"] == concurrency and row["algorithm"] == "AS_ADAPT_T"],
                         key=lambda row: row["round"])
        adapt_rows = sorted([row for row in master if row["static_dynamic"] == scope
                             and row["concurrency"] == concurrency and row["algorithm"] == "ADAPT_T"],
                            key=lambda row: row["round"])
        for field in ("A", "V", "candidate_count", "completed_trial_count", "rejected_count", "rollback_count"):
            av = [num(row[field]) for row in as_rows]; dv = [num(row[field]) for row in adapt_rows]
            effect = paired_log_effect(av, dv)
            output.append({
                "static_dynamic": scope, "concurrency": concurrency, "metric": field,
                "as_mean": fmt(statistics.fmean(av)), "as_median": fmt(statistics.median(av)),
                "adapt_mean": fmt(statistics.fmean(dv)), "adapt_median": fmt(statistics.median(dv)),
                "relative_reduction_percent": fmt(-effect["relative_change_percent"]),
                "ci95_low_reduction": fmt(-effect["ci95_high"]),
                "ci95_high_reduction": fmt(-effect["ci95_low"]),
                "n": effect["n"], "df": effect["df"],
                "analysis_method": "paired log-ratio; reduction = 1 - geometric mean(AS/ADAPT)",
            })
    return output


def dynamic_full_effects(master: list[dict]) -> list[dict]:
    comparisons = [("AS_ADAPT_T_vs_ADAPT_T", "AS_ADAPT_T", "ADAPT_T"),
                   ("AS_ADAPT_T_vs_QUEUE", "AS_ADAPT_T", "QUEUE_HYSTERESIS"),
                   ("ADAPT_T_vs_QUEUE", "ADAPT_T", "QUEUE_HYSTERESIS")]
    metrics = {"throughput": "throughput_req_s", "mean_latency": "mean_latency_ms",
               "p95": "p95_ms", "p99": "p99_ms", "p99_9": "p99_9_ms",
               "weighted_window_p99": "weighted_window_p99_ms", "A": "A", "V": "V"}
    rows = []
    for comparison, numerator_name, denominator_name in comparisons:
        numerator = sorted([row for row in master if row["static_dynamic"] == "dynamic"
                            and row["algorithm"] == numerator_name], key=lambda row: row["round"])
        denominator = sorted([row for row in master if row["static_dynamic"] == "dynamic"
                              and row["algorithm"] == denominator_name], key=lambda row: row["round"])
        for metric, field in metrics.items():
            effect = paired_log_effect([num(row[field]) for row in numerator],
                                       [num(row[field]) for row in denominator])
            rows.append({"comparison": comparison, "metric": metric,
                         "relative_change_percent": fmt(effect["relative_change_percent"]),
                         "ci95_low": fmt(effect["ci95_low"]), "ci95_high": fmt(effect["ci95_high"]),
                         "n": 3, "df": 2, "numerator_lower_count": effect["numerator_lower_count"],
                         "numerator_higher_count": effect["numerator_higher_count"],
                         "analysis_method": "paired log-ratio; t CI on mean log-ratio"})
    return rows


def dynamic_stages() -> tuple[list[dict], list[dict]]:
    source = read_csv(AUDIT / "dynamic_stage_summary.csv")
    run_rows = [row for row in source if integer(row["target_users"]) != 0]
    grouped = []
    for algorithm in ALGORITHMS:
        for phase, users in ((1, 100), (2, 10), (3, 200), (5, 50)):
            values = [row for row in run_rows if row["algorithm"] == algorithm
                      and integer(row["phase"]) == phase]
            output = {"algorithm": algorithm, "phase": phase, "target_users": users, "n": len(values),
                      "request_count_total": sum(integer(row["request_count"]) for row in values)}
            for output_name, field in [("throughput", "throughput"), ("mean_latency", "mean_latency"),
                                       ("p95", "P95"), ("p99", "P99"), ("p99_9", "P99.9")]:
                data = [num(row[field]) for row in values]
                output[f"{output_name}_mean"] = fmt(statistics.fmean(data))
                output[f"{output_name}_sd"] = fmt(sample_sd(data))
            grouped.append(output)
    return run_rows, grouped


def recovery_analysis() -> list[dict]:
    output = []
    transitions = [(31, "100_to_10"), (61, "10_to_200"), (91, "200_to_0"), (121, "0_to_50")]
    for run_id in [f"ccpe-tail-54-dynamic-r{r}-as-adaptt" for r in range(1, 4)]:
        rows = read_csv(RESULTS / f"{run_id}-server.csv")
        for boundary, label in transitions:
            start_idx = boundary - 1
            stop_idx = min(boundary + 29, 150)
            segment = rows[start_idx:stop_idx]
            change_time = iso(rows[start_idx]["window_start"])
            detected = next((row for row in segment if row["control_stage"] == "LOAD_CHANGE_DETECTED"), None)
            record = {"run_id": run_id, "round": round_no(run_id), "transition": label,
                      "workload_change_window": boundary, "workload_change_timestamp": rows[start_idx]["window_start"]}
            if detected is None:
                pending = sum(row["control_stage"] == "LOAD_CHANGE_PENDING" for row in segment)
                record.update({"change_detection_window": "NA", "detection_delay_s": "NA",
                               "recovery_baseline_start_window": "NA", "search_restart_window": "NA",
                               "first_accepted_window": "NA", "stable_reentry_window": "NA",
                               "baseline_rebuild_time_s": "NA", "search_duration_s": "NA",
                               "time_to_restable_s": "NA", "pending_windows_in_stage": pending,
                               "interpretation": "no trigger; 3 consecutive change windows not reached"})
            else:
                pos = rows.index(detected)
                after = rows[pos + 1:]
                baseline_start = after[0]
                baseline_end = next(row for row in after
                                    if row["control_stage"] == "LOAD_CHANGE_BASELINE_ESTABLISHED")
                baseline_pos = rows.index(baseline_end)
                stage_end_pos = min(start_idx + 30, len(rows))
                until_next_detection = rows[baseline_pos + 1:stage_end_pos]
                search = next((row for row in until_next_detection if row["control_stage"] == "TRIAL_APPLIED"), None)
                stable = next((row for row in until_next_detection
                               if row["control_stage"] == "STABLE_REFERENCE_ESTABLISHED"), None)
                accepted_scope = (rows[baseline_pos + 1:rows.index(stable) + 1]
                                  if stable else until_next_detection)
                accepted = next((row for row in accepted_scope if row["control_stage"] == "TRIAL_ACCEPTED"), None)
                record.update({
                    "change_detection_window": detected["window_index"],
                    "detection_delay_s": fmt((iso(detected["window_end"]) - change_time).total_seconds()),
                    "recovery_baseline_start_window": baseline_start["window_index"],
                    "search_restart_window": search["window_index"] if search else "NA",
                    "first_accepted_window": accepted["window_index"] if accepted else "NA",
                    "stable_reentry_window": stable["window_index"] if stable else "NA",
                    "baseline_rebuild_time_s": fmt((iso(baseline_end["window_end"]) -
                                                     iso(detected["window_end"])).total_seconds()),
                    "search_duration_s": (fmt((iso(stable["window_end"]) - iso(baseline_end["window_end"])).total_seconds())
                                          if stable else "NA"),
                    "time_to_restable_s": (fmt((iso(stable["window_end"]) - change_time).total_seconds())
                                           if stable else "NA"),
                    "pending_windows_in_stage": sum(row["control_stage"] == "LOAD_CHANGE_PENDING"
                                                     for row in rows[start_idx:pos + 1]),
                    "interpretation": "triggered and completed baseline/search/stable cycle",
                })
            output.append(record)
    return output


def queue_response() -> list[dict]:
    output = []
    transitions = [(31, "100_to_10", "DECREASE"), (61, "10_to_200", "INCREASE"),
                   (91, "200_to_0", "DECREASE"), (121, "0_to_50", "INCREASE")]
    for run_id in [f"ccpe-tail-54-dynamic-r{r}-queue" for r in range(1, 4)]:
        rows = read_csv(RESULTS / f"{run_id}-server.csv")
        adjustments = [row for row in rows if row["control_stage"] in
                       {"QUEUE_INCREASE_APPLIED", "QUEUE_DECREASE_APPLIED"}]
        directions = [row["control_action"] for row in adjustments]
        reversals = sum(a != b for a, b in zip(directions, directions[1:]))
        for boundary, label, desired in transitions:
            segment = rows[boundary - 1:min(boundary + 29, 150)]
            change_time = iso(rows[boundary - 1]["window_start"])
            first = next((row for row in segment if row["control_action"] == desired
                          and row["control_stage"].endswith("APPLIED")), None)
            output.append({
                "run_id": run_id, "round": round_no(run_id), "transition": label,
                "desired_direction": desired,
                "first_matching_adjustment_window": first["window_index"] if first else "NA",
                "response_delay_s": (fmt((iso(first["window_end"]) - change_time).total_seconds())
                                     if first else "NA"),
                "adjustments_in_stage": sum(row["control_stage"].endswith("APPLIED") for row in segment),
                "run_direction_reversals": reversals,
                "run_A": len(adjustments),
                "run_V": sum(abs(integer(row["pool_size_after_control"]) -
                                 integer(row["configured_pool_size"])) for row in adjustments),
            })
    return output


def java_distribution_and_sensitivity(master: list[dict]) -> tuple[list[dict], list[dict]]:
    distribution = [{k: row[k] for k in ("algorithm", "concurrency", "round", "run_id",
                                           "static_dynamic", "java_version")} for row in master]
    sensitivity = []
    for concurrency in ("10", "100", "500", "100-10-200-0-50"):
        for version in ("17.0.20", "17.0.20.1"):
            as_rows = sorted([row for row in master if row["concurrency"] == concurrency
                              and row["algorithm"] == "AS_ADAPT_T" and row["java_version"] == version],
                             key=lambda row: row["round"])
            adapt_rows = sorted([row for row in master if row["concurrency"] == concurrency
                                 and row["algorithm"] == "ADAPT_T" and row["java_version"] == version],
                                key=lambda row: row["round"])
            common = sorted(set(row["round"] for row in as_rows) & set(row["round"] for row in adapt_rows))
            for metric, field in [("throughput", "throughput_req_s"), ("p99", "p99_ms"),
                                  ("p99_9", "p99_9_ms"), ("A", "A"), ("V", "V")]:
                ratios = [math.log(next(num(row[field]) for row in as_rows if row["round"] == rnd) /
                                   next(num(row[field]) for row in adapt_rows if row["round"] == rnd))
                          for rnd in common]
                sensitivity.append({
                    "concurrency": concurrency, "java_version": version, "metric": metric,
                    "paired_rounds": ";".join(map(str, common)) or "none", "n": len(common),
                    "relative_change_percent": (fmt((math.exp(statistics.fmean(ratios)) - 1) * 100)
                                                if ratios else "NA"),
                    "ci95": "not estimated within patch subgroup (n too small)",
                })
    return distribution, sensitivity


def r5_batch(master: list[dict]) -> list[dict]:
    output = []
    for algorithm in ALGORITHMS:
        group = sorted([row for row in master if row["static_dynamic"] == "static"
                        and row["concurrency"] == "10" and row["algorithm"] == algorithm],
                       key=lambda row: row["round"])
        median = statistics.median(num(row["throughput_req_s"]) for row in group)
        for row in group:
            throughput = num(row["throughput_req_s"])
            output.append({"round": row["round"], "algorithm": algorithm,
                           "throughput_req_s": fmt(throughput), "algorithm_median": fmt(median),
                           "deviation_from_own_median_percent": fmt((throughput / median - 1) * 100)})
    return output


def controller_timing_groups(master: list[dict]) -> list[dict]:
    output = []
    for scope, concurrency in [("static", "10"), ("static", "100"), ("static", "500"),
                               ("dynamic", "100-10-200-0-50"), ("all", "all")]:
        for algorithm in ALGORITHMS:
            group = [row for row in master if row["algorithm"] == algorithm and
                     (scope == "all" or (row["static_dynamic"] == scope and row["concurrency"] == concurrency))]
            item = {"scope": scope, "concurrency": concurrency, "algorithm": algorithm, "n": len(group)}
            for field in ("controller_time_mean_ms", "controller_time_median_ms",
                          "controller_time_p95_ms", "controller_time_p99_ms",
                          "controller_time_max_ms", "controller_time_total_ms",
                          "controller_fraction_percent"):
                values = [num(row[field]) for row in group]
                item[f"{field}_mean"] = fmt(statistics.fmean(values), 9)
                item[f"{field}_sd"] = fmt(sample_sd(values), 9)
            output.append(item)
    return output


def t_mean_ci(values: list[float]) -> tuple[float, float, float]:
    mean = statistics.fmean(values)
    half = T_CRITICAL[len(values) - 1] * statistics.stdev(values) / math.sqrt(len(values))
    return mean, mean - half, mean + half


def plot_group_metric(master: list[dict], field: str, ylabel: str, basename: str) -> None:
    fig, ax = plt.subplots(figsize=(8.2, 4.8))
    offsets = {"QUEUE_HYSTERESIS": -0.25, "ADAPT_T": 0.0, "AS_ADAPT_T": 0.25}
    rng = np.random.default_rng(20261007)
    for x, concurrency in enumerate((10, 100, 500)):
        for algorithm in ALGORITHMS:
            values = [num(row[field]) for row in master if row["static_dynamic"] == "static"
                      and integer(row["concurrency"]) == concurrency and row["algorithm"] == algorithm]
            position = x + offsets[algorithm]
            jitter = rng.uniform(-0.035, 0.035, len(values))
            ax.scatter(np.full(len(values), position) + jitter, values, s=28, alpha=.72,
                       color=COLORS[algorithm], edgecolor="white", linewidth=.4, zorder=3)
            mean, low, high = t_mean_ci(values)
            ax.errorbar(position, mean, yerr=[[mean - low], [high - mean]], fmt="D", markersize=5,
                        capsize=4, color=COLORS[algorithm], linewidth=1.5, zorder=4,
                        label=LABELS[algorithm] if x == 0 else None)
    ax.set_xticks(range(3), ["10", "100", "500"])
    ax.set_xlabel("Concurrent users")
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", alpha=.22)
    ax.legend(frameon=False, ncol=3, loc="upper left")
    fig.tight_layout()
    for suffix in ("png", "pdf"):
        fig.savefig(FIG / f"{basename}.{suffix}", dpi=300, bbox_inches="tight")
    plt.close(fig)


def plot_av(master: list[dict]) -> None:
    fig, axes = plt.subplots(1, 2, figsize=(10.2, 4.2))
    x = np.arange(3); width = .24
    for axis, field in zip(axes, ("A", "V")):
        for offset, algorithm in zip((-width, 0, width), ALGORITHMS):
            means = []; errors = []
            for concurrency in (10, 100, 500):
                values = [num(row[field]) for row in master if row["static_dynamic"] == "static"
                          and integer(row["concurrency"]) == concurrency and row["algorithm"] == algorithm]
                mean, low, high = t_mean_ci(values); means.append(mean); errors.append(mean - low)
            axis.bar(x + offset, means, width, color=COLORS[algorithm], alpha=.83,
                     label=LABELS[algorithm] if field == "A" else None)
            axis.errorbar(x + offset, means, yerr=errors, fmt="none", ecolor="black", capsize=3, lw=.8)
        axis.set_xticks(x, ["10", "100", "500"]); axis.set_xlabel("Concurrent users")
        axis.set_ylabel("Adjustments A" if field == "A" else "Total variation V")
        axis.grid(axis="y", alpha=.2)
    axes[0].legend(frameon=False, fontsize=9)
    fig.tight_layout()
    for suffix in ("png", "pdf"):
        fig.savefig(FIG / f"figure_C_A_V.{suffix}", dpi=300, bbox_inches="tight")
    plt.close(fig)


def plot_search(master: list[dict]) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(11, 3.9), sharex=True)
    x = np.arange(3); width = .34
    for axis, field, title in zip(axes, ("candidate_count", "rejected_count", "rollback_count"),
                                  ("Candidates", "Rejected", "Rollbacks")):
        for offset, algorithm in zip((-width / 2, width / 2), ("ADAPT_T", "AS_ADAPT_T")):
            means=[]; errors=[]
            for concurrency in (10,100,500):
                values=[num(row[field]) for row in master if row["static_dynamic"]=="static"
                        and integer(row["concurrency"])==concurrency and row["algorithm"]==algorithm]
                mean,low,high=t_mean_ci(values); means.append(mean); errors.append(mean-low)
            axis.bar(x+offset,means,width,color=COLORS[algorithm],alpha=.85,
                     label=LABELS[algorithm] if field=="candidate_count" else None)
            axis.errorbar(x+offset,means,yerr=errors,fmt="none",ecolor="black",capsize=3,lw=.8)
        axis.set_title(title); axis.set_xticks(x,["10","100","500"]); axis.grid(axis="y",alpha=.2)
    axes[0].set_ylabel("Count per 150-window run"); axes[0].legend(frameon=False)
    fig.tight_layout()
    for suffix in ("png","pdf"): fig.savefig(FIG/f"figure_D_search_overhead.{suffix}",dpi=300,bbox_inches="tight")
    plt.close(fig)


def plot_dynamic_stage(stage_runs: list[dict]) -> None:
    fig, ax = plt.subplots(figsize=(8.4,4.7)); offsets={"QUEUE_HYSTERESIS":-.24,"ADAPT_T":0,"AS_ADAPT_T":.24}
    rng=np.random.default_rng(20261008)
    for x,users in enumerate((100,10,200,50)):
        for algorithm in ALGORITHMS:
            values=[num(row["P99.9"]) for row in stage_runs if row["algorithm"]==algorithm
                    and integer(row["target_users"])==users]
            pos=x+offsets[algorithm]; jitter=rng.uniform(-.03,.03,len(values))
            ax.scatter(np.full(len(values),pos)+jitter,values,color=COLORS[algorithm],s=30,alpha=.75,zorder=3)
            mean,low,high=t_mean_ci(values)
            ax.errorbar(pos,mean,yerr=[[mean-low],[high-mean]],fmt="D",color=COLORS[algorithm],capsize=4,
                        markersize=5,label=LABELS[algorithm] if x==0 else None)
    ax.set_xticks(range(4),["100","10","200","50"]); ax.set_xlabel("Dynamic workload stage (users)")
    ax.set_ylabel("Request-level P99.9 latency (ms)"); ax.grid(axis="y",alpha=.2); ax.legend(frameon=False,ncol=3)
    fig.tight_layout()
    for suffix in ("png","pdf"): fig.savefig(FIG/f"figure_E_dynamic_P99_9.{suffix}",dpi=300,bbox_inches="tight")
    plt.close(fig)


def plot_dynamic_pool() -> None:
    fig, ax=plt.subplots(figsize=(9.2,4.5))
    for algorithm,suffix in [("QUEUE_HYSTERESIS","queue"),("ADAPT_T","adaptt"),("AS_ADAPT_T","as-adaptt")]:
        rows=read_csv(RESULTS/f"ccpe-tail-54-dynamic-r2-{suffix}-server.csv")
        ax.step([integer(row["window_index"]) for row in rows],
                [integer(row["pool_size_after_control"]) for row in rows],where="post",
                color=COLORS[algorithm],label=LABELS[algorithm],lw=1.45)
    for boundary in (30,60,90,120): ax.axvline(boundary,color="#777777",lw=.7,ls="--",alpha=.7)
    ax.text(15,.98,"100",transform=ax.get_xaxis_transform(),ha="center",va="top")
    ax.text(45,.98,"10",transform=ax.get_xaxis_transform(),ha="center",va="top")
    ax.text(75,.98,"200",transform=ax.get_xaxis_transform(),ha="center",va="top")
    ax.text(105,.98,"0",transform=ax.get_xaxis_transform(),ha="center",va="top")
    ax.text(135,.98,"50 users",transform=ax.get_xaxis_transform(),ha="center",va="top")
    ax.set_xlabel("10-second window (dynamic R2)"); ax.set_ylabel("Pool size after control")
    ax.set_xlim(1,150); ax.grid(axis="y",alpha=.2)
    ax.legend(frameon=False,ncol=3,loc="upper center",bbox_to_anchor=(.5,1.14))
    fig.tight_layout()
    for suffix in ("png","pdf"): fig.savefig(FIG/f"figure_F_dynamic_pool_R2.{suffix}",dpi=300,bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True); FIG.mkdir(parents=True, exist_ok=True)
    master=build_master(); write_csv(OUT/"CCPE_54_run_master.csv",master,MASTER_FIELDS)
    stable=stable_descriptives(master)
    write_csv(OUT/"stable_online_comparison.csv",stable,list(stable[0].keys()))
    effects=stable_effects(master)
    write_csv(OUT/"stable_pairwise_effects.csv",effects,list(effects[0].keys()))
    overhead_rows=overhead(master)
    write_csv(OUT/"controller_search_overhead.csv",overhead_rows,list(overhead_rows[0].keys()))
    overhead_pairwise=overhead_effects(master)
    write_csv(OUT/"controller_search_pairwise_effects.csv",overhead_pairwise,list(overhead_pairwise[0].keys()))
    dyn_effects=dynamic_full_effects(master)
    write_csv(OUT/"dynamic_full_run_pairwise_effects.csv",dyn_effects,list(dyn_effects[0].keys()))
    stage_runs,stage_groups=dynamic_stages()
    write_csv(OUT/"dynamic_stage_run_values.csv",stage_runs,list(stage_runs[0].keys()))
    write_csv(OUT/"dynamic_stage_results.csv",stage_groups,list(stage_groups[0].keys()))
    recovery=recovery_analysis(); write_csv(OUT/"dynamic_recovery_analysis.csv",recovery,list(recovery[0].keys()))
    queue=queue_response(); write_csv(OUT/"queue_dynamic_response.csv",queue,list(queue[0].keys()))
    java_dist,java_sensitivity=java_distribution_and_sensitivity(master)
    write_csv(OUT/"java_version_distribution.csv",java_dist,list(java_dist[0].keys()))
    write_csv(OUT/"java_patch_sensitivity_effects.csv",java_sensitivity,list(java_sensitivity[0].keys()))
    batch=r5_batch(master); write_csv(OUT/"prime10_round_batch_effect.csv",batch,list(batch[0].keys()))
    timing=controller_timing_groups(master)
    write_csv(OUT/"controller_execution_time_comparison.csv",timing,list(timing[0].keys()))
    plot_group_metric(master,"p99_ms","Request-level P99 latency (ms)","figure_A_stable_P99")
    plot_group_metric(master,"p99_9_ms","Request-level P99.9 latency (ms)","figure_B_stable_P99_9")
    plot_av(master); plot_search(master); plot_dynamic_stage(stage_runs); plot_dynamic_pool()


if __name__ == "__main__":
    main()
