#!/usr/bin/env python3
"""Read-only, reproducible audit of the 54 CCPE supplemental runs."""

from __future__ import annotations

import csv
import json
import math
import os
import re
import statistics
import subprocess
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"
OUT = ROOT / "analysis" / "output" / "ccpe-54-audit"
JAR = ROOT / "target" / "adapt-t-server-1.0.0-SNAPSHOT.jar"
JAVA_SRC = ROOT / "analysis" / "HlogAudit.java"
JAVA_CLASSES = OUT / ".classes"

POLICY_LABEL = {
    "adaptt": "ADAPT_T",
    "as-adaptt": "AS_ADAPT_T",
    "queue": "QUEUE_HYSTERESIS",
}


def f(value: str | int | float | None, default: float = 0.0) -> float:
    if value is None or value == "":
        return default
    return float(value)


def i(value: str | int | float | None, default: int = 0) -> int:
    if value is None or value == "":
        return default
    return int(float(value))


def iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def read_metadata(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def expected_runs() -> list[dict]:
    runs: list[dict] = []
    for round_no in range(1, 6):
        for users in (10, 100, 500):
            for suffix, algorithm in POLICY_LABEL.items():
                run_id = f"ccpe-tail-54-stable-r{round_no}-u{users}-{suffix}"
                runs.append({
                    "run_id": run_id, "algorithm": algorithm,
                    "dynamic_or_static": "static", "concurrency": str(users),
                    "round": round_no,
                })
    for round_no in range(1, 4):
        for suffix, algorithm in POLICY_LABEL.items():
            run_id = f"ccpe-tail-54-dynamic-r{round_no}-{suffix}"
            runs.append({
                "run_id": run_id, "algorithm": algorithm,
                "dynamic_or_static": "dynamic",
                "concurrency": "100-10-200-0-50", "round": round_no,
            })
    return sorted(runs, key=lambda row: row["run_id"])


def decode_hlogs(paths: list[Path]) -> tuple[dict, dict, dict]:
    JAVA_CLASSES.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "javac", "-cp", str(JAR), "-d", str(JAVA_CLASSES), str(JAVA_SRC)
    ], check=True)
    command = ["java", "-cp", f"{JAVA_CLASSES}{os.pathsep}{JAR}", "HlogAudit"]
    command.extend(str(path) for path in paths)
    completed = subprocess.run(command, check=True, text=True, capture_output=True)
    totals: dict[str, dict] = {}
    windows: dict[str, list[dict]] = defaultdict(list)
    phases: dict[str, list[dict]] = defaultdict(list)
    metrics = ["count", "mean", "p50", "p95", "p99", "p999", "min", "max"]
    for line in completed.stdout.splitlines():
        parts = line.split("\t")
        kind, path = parts[:2]
        if kind == "TOTAL":
            values = parts[6:]
            totals[path] = {
                "windows": int(parts[2]), "sequential_tags": parts[3] == "true",
                "nondecreasing_time": parts[4] == "true",
                **dict(zip(metrics, [int(parts[5])] + [float(x) for x in values])),
            }
        else:
            values = parts[5:]
            row = {
                "index": int(parts[2]), "start_ms": int(parts[3]),
                "end_ms": int(parts[4]),
                **dict(zip(metrics, [int(values[0])] + [float(x) for x in values[1:]])),
            }
            (windows if kind == "WINDOW" else phases)[path].append(row)
    return totals, windows, phases


def as_state_issues(rows: list[dict[str, str]]) -> list[str]:
    issues: list[str] = []
    allowed = {
        "INITIAL_BASELINE": {"INITIAL_BASELINE_ESTABLISHED", "EMPTY_WINDOW_DEFERRED"},
        "SEARCH": {"TRIAL_APPLIED", "SEARCH_CONVERGED_AT_BOUND",
                   "ABLATION_SEARCH_RESTARTED_AT_BOUND", "EMPTY_WINDOW_DEFERRED"},
        "TRIAL": {"TRIAL_ACCEPTED", "EMPTY_WINDOW_DEFERRED"},
        "REFERENCE_COLLECTION": {"REFERENCE_WINDOW_COLLECTED",
                                 "STABLE_REFERENCE_ESTABLISHED", "EMPTY_WINDOW_DEFERRED"},
        "STABLE": {"STABLE_HELD", "LOAD_CHANGE_PENDING", "LOAD_CHANGE_DETECTED",
                   "EMPTY_WINDOW_DEFERRED"},
        "RECOVERY_BASELINE_COLLECTION": {"LOAD_CHANGE_BASELINE_COLLECTED",
                                         "LOAD_CHANGE_BASELINE_ESTABLISHED",
                                         "EMPTY_WINDOW_DEFERRED"},
    }
    state = "INITIAL_BASELINE"
    for row in rows:
        stage = row["control_stage"]
        if stage.startswith("TRIAL_REJECTED"):
            valid = state == "TRIAL"
        else:
            valid = stage in allowed.get(state, set())
        if not valid:
            issues.append(f"w{row['window_index']} {state}->{stage}")
        post = row["control_state"] or state
        if stage.startswith("TRIAL_REJECTED") and i(row["pool_size_after_control"]) != i(row["best_pool_size"]):
            issues.append(f"w{row['window_index']} rejected trial did not restore best pool")
        if stage in {"REFERENCE_WINDOW_COLLECTED", "LOAD_CHANGE_BASELINE_COLLECTED"}:
            if not 1 <= i(row["reference_window_count"]) < 3:
                issues.append(f"w{row['window_index']} invalid reference count")
        if stage in {"STABLE_REFERENCE_ESTABLISHED", "LOAD_CHANGE_BASELINE_ESTABLISHED"}:
            if i(row["reference_window_count"]) != 3:
                issues.append(f"w{row['window_index']} baseline established before 3 windows")
        state = post
    return issues


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    expected = expected_runs()
    hlog_paths = [RESULTS / f"{run['run_id']}-server-request-latency.hlog" for run in expected]
    htotals, hwindows, hphases = decode_hlogs(hlog_paths)

    inventory: list[dict] = []
    summaries: list[dict] = []
    checks: list[dict] = []
    dynamic_stages: list[dict] = []
    timing_summaries: list[dict] = []
    recovery_timelines: list[dict] = []
    evidence: dict = {
        "expected_count": len(expected), "found_count": 0, "missing": [],
        "unexpected_formal_server_csv": [], "jar_hashes": Counter(),
        "java_versions": Counter(), "status_counts": Counter(),
        "as_state_issue_count": 0, "hlog_summary_mismatch_count": 0,
        "window_continuity_issue_count": 0, "log_error_count": 0,
        "dynamic_zero_window_patterns": {}, "group_throughputs": defaultdict(list),
    }

    expected_ids = {run["run_id"] for run in expected}
    actual_ids = {
        path.name.removesuffix("-server.csv")
        for path in RESULTS.glob("ccpe-tail-54-*-server.csv")
    }
    evidence["found_count"] = len(actual_ids & expected_ids)
    evidence["missing"] = sorted(expected_ids - actual_ids)
    evidence["unexpected_formal_server_csv"] = sorted(actual_ids - expected_ids)

    for spec in expected:
        run_id = spec["run_id"]
        server_csv = RESULTS / f"{run_id}-server.csv"
        metadata_file = RESULTS / f"{run_id}-metadata.txt"
        server_log = RESULTS / f"{run_id}-server.log"
        hlog = RESULTS / f"{run_id}-server-request-latency.hlog"
        latency_summary_file = RESULTS / f"{run_id}-server-request-latency-summary.csv"
        controller_summary_file = RESULTS / f"{run_id}-server-controller-summary.csv"
        process_csv = RESULTS / f"{run_id}-process.csv"
        required = [server_csv, metadata_file, server_log, hlog,
                    latency_summary_file, controller_summary_file]
        missing_files = [path.name for path in required if not path.is_file()]
        if missing_files:
            inventory.append({**spec, "workload": "PRIME", "result_directory": str(RESULTS),
                              "status": "FAIL", "notes": "missing: " + ";".join(missing_files)})
            checks.append({"run_id": run_id, "status": "FAIL", "issues": inventory[-1]["notes"]})
            evidence["status_counts"]["FAIL"] += 1
            continue

        rows = read_csv(server_csv)
        meta = read_metadata(metadata_file)
        lat = read_csv(latency_summary_file)[0]
        ctrl = read_csv(controller_summary_file)[0]
        h = htotals[str(hlog)]
        log_text = server_log.read_text(encoding="utf-8", errors="replace")
        issues: list[str] = []
        hard: list[str] = []

        policy_values = {row["policy"] for row in rows}
        app_values = {row["application"] for row in rows}
        indices = [i(row["window_index"]) for row in rows]
        duration = sum(f(row["window_duration_seconds"]) for row in rows)
        successes = sum(i(row["successful_requests"]) for row in rows)
        failures = sum(i(row["failed_requests"]) for row in rows)
        rejected_tasks = sum(i(row["rejected_tasks_delta"]) for row in rows)
        start = rows[0]["window_start"] if rows else ""
        end = rows[-1]["window_end"] if rows else ""
        wall_duration = (iso(end) - iso(start)).total_seconds() if rows else 0.0
        gaps = []
        for prior, current in zip(rows, rows[1:]):
            gaps.append((iso(current["window_start"]) - iso(prior["window_end"])).total_seconds())

        if len(rows) != 150 or indices != list(range(1, 151)):
            hard.append(f"window count/index invalid: {len(rows)}")
        if abs(duration - 1500.0) > 1.0 or abs(wall_duration - 1500.0) > 1.0:
            hard.append(f"duration outside tolerance: summed={duration:.6f}, wall={wall_duration:.6f}")
        if gaps and max(abs(x) for x in gaps) > 0.01:
            hard.append(f"window gap/overlap exceeds 10ms: {max(abs(x) for x in gaps):.6f}s")
            evidence["window_continuity_issue_count"] += 1
        if policy_values != {spec["algorithm"]} or app_values != {"PRIME"}:
            hard.append(f"internal policy/application mismatch: {policy_values}/{app_values}")
        if failures or rejected_tasks:
            hard.append(f"server failures={failures}, rejected tasks={rejected_tasks}")
        bad_numeric = 0
        for row in rows:
            for field in ("throughput_rps", "mean_latency_ms", "p95_latency_ms",
                          "p99_latency_ms", "p999_latency_ms", "min_latency_ms",
                          "max_latency_ms"):
                value = f(row[field])
                if not math.isfinite(value) or value < 0:
                    bad_numeric += 1
        if bad_numeric:
            hard.append(f"invalid window latency/throughput values={bad_numeric}")
        error_hits = re.findall(r"(?im)\b(ERROR|FATAL|Exception|OutOfMemoryError)\b", log_text)
        if error_hits:
            hard.append(f"server log error tokens={len(error_hits)}")
            evidence["log_error_count"] += len(error_hits)
        if log_text.count("Measurement started") != 1 or log_text.count("Measurement completed") != 1:
            hard.append("measurement lifecycle markers are not exactly one each")

        nonempty_indices = [i(row["window_index"]) for row in rows
                            if i(row["successful_requests"]) > 0]
        hlog_indices = [row["index"] for row in hwindows[str(hlog)]]
        summary_match = (
            i(lat["window_count"]) == 150
            and h["windows"] == len(nonempty_indices)
            and i(lat["successful_requests"]) == successes == h["count"]
            and h["sequential_tags"] and hlog_indices == nonempty_indices
            and all(abs(f(lat[name + "_latency_ms"]) - h[key]) <= 1.1e-6
                    for name, key in [("mean", "mean"), ("p50", "p50"),
                                      ("p95", "p95"), ("p99", "p99"),
                                      ("p999", "p999"), ("min", "min"),
                                      ("max", "max")])
        )
        if not summary_match:
            hard.append("HLOG, window CSV and latency summary do not match")
            evidence["hlog_summary_mismatch_count"] += 1

        decision_count = i(ctrl["controller_decision_count"])
        final = rows[-1]
        controller_fields = ["controller_decision_count", "candidate_count", "accepted_count",
                             "rejected_count", "controller_rollback_count", "stable_enter_count",
                             "recovery_count", "controller_decision_total_ns",
                             "controller_decision_p95_ns", "controller_decision_max_ns"]
        for field in controller_fields:
            if i(ctrl[field]) != i(final[field]):
                hard.append(f"controller summary mismatch: {field}")
        if decision_count != 150:
            hard.append(f"controller decision count={decision_count}")
        if not (0 <= i(ctrl["controller_decision_p95_ns"]) <= i(ctrl["controller_decision_max_ns"]) <= 10_000_000_000):
            hard.append("controller timing range/order invalid")
        timing_delta = abs(f(ctrl["controller_decision_mean_ns"]) * decision_count
                           - i(ctrl["controller_decision_total_ns"]))
        if timing_delta > max(1.0, i(ctrl["controller_decision_total_ns"]) * 0.001):
            hard.append("controller mean/total timing mismatch")
        cumulative_times = [i(row["controller_decision_total_ns"]) for row in rows]
        decision_times = [cumulative_times[0]] + [
            current - prior for prior, current in zip(cumulative_times, cumulative_times[1:])]
        if any(value <= 0 for value in decision_times):
            hard.append("non-positive controller decision time reconstructed from cumulative totals")
        ordered_times = sorted(decision_times)
        nearest = lambda percentile: ordered_times[math.ceil(percentile * len(ordered_times)) - 1]
        timing_summaries.append({
            "run_id": run_id, "algorithm": spec["algorithm"], "decision_count": len(decision_times),
            "mean_ns": f"{statistics.fmean(decision_times):.3f}",
            "median_ns": f"{statistics.median(decision_times):.3f}",
            "p95_ns_recomputed_nearest_rank": nearest(0.95),
            "p95_ns_stored_hdr": i(ctrl["controller_decision_p95_ns"]),
            "p99_ns_recomputed_nearest_rank": nearest(0.99),
            "max_ns": max(decision_times), "total_ns": sum(decision_times),
            "unit": "ns", "measurement_scope": "WindowCoordinator controller.onWindow call",
        })

        changes = [(i(row["pool_size_after_control"]) != i(row["configured_pool_size"]),
                    abs(i(row["pool_size_after_control"]) - i(row["configured_pool_size"])))
                   for row in rows]
        adjustment_a = sum(changed for changed, _ in changes)
        variation_v = sum(delta for _, delta in changes)
        candidate_recomputed = sum(row["control_stage"] in {
            "TRIAL_APPLIED", "INCREASE_APPLIED", "DECREASE_APPLIED",
            "QUEUE_INCREASE_APPLIED", "QUEUE_DECREASE_APPLIED"} for row in rows)
        accepted_recomputed = sum(row["control_stage"] in {
            "TRIAL_ACCEPTED", "INCREASE_ACCEPTED", "DECREASE_ACCEPTED",
            "QUEUE_INCREASE_APPLIED", "QUEUE_DECREASE_APPLIED"} for row in rows)
        rejected_recomputed = sum(row["control_stage"].startswith("TRIAL_REJECTED") or
                                  row["control_stage"] in {"INCREASE_ROLLED_BACK", "DECREASE_ROLLED_BACK"}
                                  for row in rows)
        rollback_recomputed = sum((row["control_stage"].startswith("TRIAL_REJECTED") or
                                   row["control_stage"] in {"INCREASE_ROLLED_BACK", "DECREASE_ROLLED_BACK"})
                                  and i(row["configured_pool_size"]) != i(row["pool_size_after_control"])
                                  for row in rows)
        if [candidate_recomputed, accepted_recomputed, rejected_recomputed, rollback_recomputed] != [
                i(ctrl["candidate_count"]), i(ctrl["accepted_count"]),
                i(ctrl["rejected_count"]), i(ctrl["controller_rollback_count"])]:
            hard.append("recomputed candidate/accept/reject/rollback differs from summary")
        if candidate_recomputed != accepted_recomputed + rejected_recomputed:
            pending = (candidate_recomputed == accepted_recomputed + rejected_recomputed + 1
                       and rows[-1]["control_stage"] in {
                           "TRIAL_APPLIED", "INCREASE_APPLIED", "DECREASE_APPLIED"})
            if pending:
                issues.append("one final-window candidate is right-censored before evaluation")
            else:
                hard.append("candidate_count != accepted_count + rejected_count without boundary explanation")

        if spec["algorithm"] in {"AS_ADAPT_T", "QUEUE_HYSTERESIS"}:
            if adjustment_a != i(final["adjustment_count"]) or variation_v != i(final["thread_total_variation"]):
                hard.append("recomputed A/V differs from controller telemetry")
        state_issues = as_state_issues(rows) if spec["algorithm"] == "AS_ADAPT_T" else []
        if state_issues:
            hard.append("illegal AS state transitions: " + "; ".join(state_issues[:5]))
            evidence["as_state_issue_count"] += len(state_issues)

        if spec["dynamic_or_static"] == "dynamic" and spec["algorithm"] == "AS_ADAPT_T":
            trigger_positions = [position for position, row in enumerate(rows)
                                 if row["control_stage"] == "LOAD_CHANGE_DETECTED"]
            for ordinal, position in enumerate(trigger_positions, 1):
                following = rows[position + 1:]
                baseline = next((row for row in following
                                 if row["control_stage"] == "LOAD_CHANGE_BASELINE_ESTABLISHED"), None)
                baseline_position = rows.index(baseline) if baseline else len(rows)
                search = next((row for row in rows[baseline_position + 1:]
                               if row["control_stage"] == "TRIAL_APPLIED"), None)
                stable = next((row for row in rows[baseline_position + 1:]
                               if row["control_stage"] == "STABLE_REFERENCE_ESTABLISHED"), None)
                elapsed = lambda row: (f"{(iso(row['window_end']) - iso(start)).total_seconds():.6f}"
                                       if row else "NA")
                recovery_timelines.append({
                    "run_id": run_id, "recovery_ordinal": ordinal,
                    "trigger_window": rows[position]["window_index"],
                    "trigger_elapsed_s": elapsed(rows[position]),
                    "baseline_start_window": rows[position + 1]["window_index"],
                    "baseline_established_window": baseline["window_index"] if baseline else "NA",
                    "baseline_established_elapsed_s": elapsed(baseline),
                    "search_restart_window": search["window_index"] if search else "NA",
                    "search_restart_elapsed_s": elapsed(search),
                    "stable_reentry_window": stable["window_index"] if stable else "NA",
                    "stable_reentry_elapsed_s": elapsed(stable),
                })

        zero_windows: list[int] = []
        if spec["dynamic_or_static"] == "dynamic":
            zero_windows = [i(row["window_index"]) for row in rows
                            if i(row["successful_requests"]) == 0]
            evidence["dynamic_zero_window_patterns"][run_id] = zero_windows
            if zero_windows != list(range(92, 121)):
                hard.append(f"unexpected zero-request windows: {zero_windows}")
            if i(rows[90]["successful_requests"]) <= 0:
                hard.append("window 91 lacks expected draining requests at 200->0 boundary")
            if any(any(f(row[name]) != 0 for name in ("mean_latency_ms", "p95_latency_ms",
                                                       "p99_latency_ms", "p999_latency_ms"))
                   for row in rows[91:120]):
                hard.append("empty 0-user windows contain nonzero latency placeholders")
            process_rows = read_csv(process_csv) if process_csv.is_file() else []
            if len(process_rows) < 1490 or f(process_rows[-1].get("elapsed_seconds")) < 1500:
                hard.append("dynamic process monitor missing or incomplete")

            for phase_row in hphases[str(hlog)]:
                phase = phase_row["index"]
                users = [100, 10, 200, 0, 50][phase - 1]
                window_slice = rows[(phase - 1) * 30:phase * 30]
                stage_duration = sum(f(row["window_duration_seconds"]) for row in window_slice)
                dynamic_stages.append({
                    "run_id": run_id, "algorithm": spec["algorithm"], "phase": phase,
                    "target_users": users, "duration": f"{stage_duration:.6f}",
                    "request_count": phase_row["count"],
                    "throughput": f"{phase_row['count'] / stage_duration:.6f}",
                    "mean_latency": "NA" if users == 0 else f"{phase_row['mean']:.6f}",
                    "P50": "NA" if users == 0 else f"{phase_row['p50']:.6f}",
                    "P95": "NA" if users == 0 else f"{phase_row['p95']:.6f}",
                    "P99": "NA" if users == 0 else f"{phase_row['p99']:.6f}",
                    "P99.9": "NA" if users == 0 else f"{phase_row['p999']:.6f}",
                    "note": ("latency intentionally NA; phase contains only transition-drain requests in window 91"
                             if users == 0 else "merged from the 30 phase-window histograms"),
                })

        # Evidence limitations are WARNINGs, not reasons to discard or rerun.
        issues.append("request-row timestamp/success/stage/pool/state fields not persisted; HLOG is window-tagged distribution data")
        issues.append("client JMeter JTL/log/metadata not present in this server results directory")
        if spec["dynamic_or_static"] == "dynamic":
            issues.append("per-request workload stage unavailable; phase statistics use the runner's fixed 30-window mapping")
        if meta.get("java_version", "").startswith('openjdk version "17.0.20"'):
            issues.append("minority Java patch level 17.0.20 (matrix also contains 17.0.20.1)")

        status = "FAIL" if hard else ("WARNING" if issues else "PASS")
        all_issues = hard + issues
        evidence["status_counts"][status] += 1
        evidence["jar_hashes"][meta.get("jar_sha256", "Missing")] += 1
        evidence["java_versions"][meta.get("java_version", "Missing")] += 1

        stable_rows = [row for row in rows if row["control_state"] == "STABLE"]
        first_stable = next((row for row in rows if row["control_stage"] == "STABLE_REFERENCE_ESTABLISHED"), None)
        candidate = i(ctrl["candidate_count"])
        accepted = i(ctrl["accepted_count"])
        rejected = i(ctrl["rejected_count"])
        rollback = i(ctrl["controller_rollback_count"])
        throughput = successes / duration
        summaries.append({
            **{k: spec[k] for k in ("algorithm", "concurrency", "run_id", "dynamic_or_static")},
            "workload": "PRIME", "duration": f"{duration:.6f}",
            "request_count": successes + failures, "success_count": successes,
            "failure_count": failures, "throughput": f"{throughput:.6f}",
            "mean_latency": f"{h['mean']:.6f}", "P50": f"{h['p50']:.6f}",
            "P95": f"{h['p95']:.6f}", "P99": f"{h['p99']:.6f}",
            "P99.9": f"{h['p999']:.6f}", "min_latency": f"{h['min']:.6f}",
            "max_latency": f"{h['max']:.6f}", "A": adjustment_a, "V": variation_v,
            "candidate_count": candidate, "accepted_count": accepted,
            "rejected_count": rejected, "rollback_count": rollback,
            "acceptance_rate": f"{accepted / candidate:.6f}" if candidate else "NA",
            "rejected_trial_ratio": f"{rejected / candidate:.6f}" if candidate else "NA",
            "rollback_ratio": f"{rollback / candidate:.6f}" if candidate else "NA",
            "stable_enter_count": i(ctrl["stable_enter_count"]) if spec["algorithm"] == "AS_ADAPT_T" else "NA",
            "recovery_count": i(ctrl["recovery_count"]) if spec["algorithm"] == "AS_ADAPT_T" else "NA",
            "time_to_first_stable": (f"{(iso(first_stable['window_end']) - iso(start)).total_seconds():.6f}"
                                     if first_stable else "NA"),
            "stable_ratio": (f"{len(stable_rows) / len(rows):.6f}"
                             if spec["algorithm"] == "AS_ADAPT_T" else "NA"),
            "controller_time_mean": f"{f(ctrl['controller_decision_mean_ns']):.3f}",
            "controller_time_p95": i(ctrl["controller_decision_p95_ns"]),
            "controller_time_total": i(ctrl["controller_decision_total_ns"]),
            "controller_time_unit": "ns", "audit_status": status,
        })
        group = (spec["dynamic_or_static"], spec["concurrency"], spec["algorithm"])
        evidence["group_throughputs"]["|".join(group)].append({
            "run_id": run_id, "round": spec["round"], "throughput": throughput,
            "max_latency": h["max"], "p999": h["p999"],
        })

        inventory.append({
            **{k: spec[k] for k in ("algorithm", "concurrency", "run_id", "dynamic_or_static")},
            "workload": "PRIME", "result_directory": str(RESULTS),
            "request_log": str(hlog), "request_log_format": "HdrHistogram per window (not request rows)",
            "window_log": str(server_csv), "controller_log": str(server_csv),
            "config_file": str(metadata_file), "start_time": start, "end_time": end,
            "duration": f"{duration:.6f}", "window_count": len(rows),
            "status": status, "notes": " | ".join(all_issues),
        })
        checks.append({
            "run_id": run_id, "status": status, "window_count": len(rows),
            "duration": f"{duration:.6f}", "success_count": successes,
            "failure_count": failures, "rejected_tasks": rejected_tasks,
            "zero_request_windows": ";".join(map(str, zero_windows)) or "none",
            "issues": " | ".join(all_issues),
        })

    inv_fields = ["algorithm", "workload", "concurrency", "run_id", "dynamic_or_static",
                  "result_directory", "request_log", "request_log_format", "window_log",
                  "controller_log", "config_file", "start_time", "end_time", "duration",
                  "window_count", "status", "notes"]
    summary_fields = ["algorithm", "workload", "concurrency", "run_id", "dynamic_or_static",
                      "duration", "request_count", "success_count", "failure_count", "throughput",
                      "mean_latency", "P50", "P95", "P99", "P99.9", "min_latency", "max_latency",
                      "A", "V", "candidate_count", "accepted_count", "rejected_count",
                      "rollback_count", "acceptance_rate", "rejected_trial_ratio", "rollback_ratio",
                      "stable_enter_count", "recovery_count", "time_to_first_stable", "stable_ratio",
                      "controller_time_mean", "controller_time_p95", "controller_time_total",
                      "controller_time_unit", "audit_status"]
    write_csv(OUT / "experiment_inventory.csv", inventory, inv_fields)
    write_csv(OUT / "run_level_summary.csv", summaries, summary_fields)
    write_csv(OUT / "per_run_integrity_checks.csv", checks,
              ["run_id", "status", "window_count", "duration", "success_count",
               "failure_count", "rejected_tasks", "zero_request_windows", "issues"])
    write_csv(OUT / "dynamic_stage_summary.csv", dynamic_stages,
              ["run_id", "algorithm", "phase", "target_users", "duration", "request_count",
               "throughput", "mean_latency", "P50", "P95", "P99", "P99.9", "note"])
    write_csv(OUT / "controller_timing_summary.csv", timing_summaries,
              ["run_id", "algorithm", "decision_count", "mean_ns", "median_ns",
               "p95_ns_recomputed_nearest_rank", "p95_ns_stored_hdr",
               "p99_ns_recomputed_nearest_rank", "max_ns", "total_ns", "unit",
               "measurement_scope"])
    write_csv(OUT / "as_dynamic_recovery_timeline.csv", recovery_timelines,
              ["run_id", "recovery_ordinal", "trigger_window", "trigger_elapsed_s",
               "baseline_start_window", "baseline_established_window",
               "baseline_established_elapsed_s", "search_restart_window",
               "search_restart_elapsed_s", "stable_reentry_window",
               "stable_reentry_elapsed_s"])

    # Convert non-JSON containers and add global totals.
    evidence["jar_hashes"] = dict(evidence["jar_hashes"])
    evidence["java_versions"] = dict(evidence["java_versions"])
    evidence["status_counts"] = dict(evidence["status_counts"])
    evidence["group_throughputs"] = dict(evidence["group_throughputs"])
    evidence["total_successes"] = sum(i(row["success_count"]) for row in summaries)
    evidence["total_failures"] = sum(i(row["failure_count"]) for row in summaries)
    evidence["controller_timing_global"] = {
        "min_mean_ns": min(f(row["controller_time_mean"]) for row in summaries),
        "max_mean_ns": max(f(row["controller_time_mean"]) for row in summaries),
        "max_p95_ns": max(i(row["controller_time_p95"]) for row in summaries),
        "max_single_ns": max(i(read_csv(RESULTS / f"{row['run_id']}-server-controller-summary.csv")[0]["controller_decision_max_ns"])
                             for row in summaries),
    }
    (OUT / "audit_evidence.json").write_text(
        json.dumps(evidence, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
