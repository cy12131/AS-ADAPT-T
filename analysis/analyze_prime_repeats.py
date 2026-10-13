#!/usr/bin/env python3
"""Analyze five independent PRIME rounds for ADAPT_T and AS_ADAPT_T."""

from __future__ import annotations

import math
import re
from pathlib import Path

import numpy as np
import pandas as pd


RESULTS = Path(__file__).resolve().parents[1] / "results"
OUTPUT = Path(__file__).resolve().parent / "output" / "prime-repeats"
ROUNDS = ("R1", "R2", "R3", "R4", "R5")
T_CRITICAL_DF4_95 = 2.7764451052
T_CRITICAL_DF4_ONE_SIDED_95 = 2.1318467863


def summarize(group: pd.DataFrame) -> dict[str, float | int]:
    successes = group.successful_requests.to_numpy(float)
    durations = group.window_duration_seconds.to_numpy(float)
    changes = (
        group.pool_size_after_control.to_numpy(int)
        - group.configured_pool_size.to_numpy(int)
    )
    stable = group.control_state.fillna("").eq("STABLE")
    stable_pools = group.loc[stable, "configured_pool_size"]
    return {
        "successful_requests": int(successes.sum()),
        "failed_requests": int(group.failed_requests.sum()),
        "rejected_tasks": int(group.rejected_tasks_delta.sum()),
        "throughput_rps": float(successes.sum() / durations.sum()),
        "mean_latency_ms": float(
            np.average(group.mean_latency_ms.to_numpy(float), weights=successes)
        ),
        "mean_window_p99_ms": float(
            np.average(group.p99_latency_ms.to_numpy(float), weights=successes)
        ),
        "pool_change_count": int(np.count_nonzero(changes)),
        "thread_total_variation": int(np.abs(changes).sum()),
        "average_pool_size": float(
            np.average(group.configured_pool_size.to_numpy(float), weights=durations)
        ),
        "pool_size_standard_deviation": float(
            group.configured_pool_size.std(ddof=0)
        ),
        "stable_window_fraction": float(stable.mean()),
        "dominant_stable_pool_size": (
            int(stable_pools.mode().iloc[0]) if len(stable_pools) else math.nan
        ),
        "load_change_detection_count": int(
            group.control_stage.fillna("").eq("LOAD_CHANGE_DETECTED").sum()
        ),
    }


def load_run(path: Path, round_name: str, users: int, policy: str) -> dict:
    windows = pd.read_csv(path)
    if len(windows) != 150:
        raise RuntimeError(f"{path.name}: expected 150 windows, found {len(windows)}")
    return {
        "round": round_name,
        "users": users,
        "policy": policy,
        "run_id": path.name.removesuffix("-server.csv"),
        **summarize(windows),
    }


def load_all() -> pd.DataFrame:
    records = []
    for users in (10, 100, 500):
        for policy, suffix in (("ADAPT_T", "adaptt"), ("AS_ADAPT_T", "as-adaptt")):
            records.append(
                load_run(
                    RESULTS / f"formal-r1-prime-u{users}-{suffix}-server.csv",
                    "R1",
                    users,
                    policy,
                )
            )
            for round_name in ROUNDS[1:]:
                records.append(
                    load_run(
                        RESULTS
                        / f"prime-repeat-{round_name.lower()}-u{users}-{suffix}-server.csv",
                        round_name,
                        users,
                        policy,
                    )
                )
    runs = pd.DataFrame(records)
    if len(runs) != 30:
        raise RuntimeError(f"Expected 30 runs, found {len(runs)}")
    return runs.sort_values(["users", "round", "policy"]).reset_index(drop=True)


def paired_effects(runs: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for users in (10, 100, 500):
        for round_name in ROUNDS:
            pair = runs[(runs.users == users) & (runs["round"] == round_name)].set_index(
                "policy"
            )
            adapt = pair.loc["ADAPT_T"]
            improved = pair.loc["AS_ADAPT_T"]
            rows.append(
                {
                    "users": users,
                    "round": round_name,
                    "throughput_change_percent": (
                        improved.throughput_rps / adapt.throughput_rps - 1
                    )
                    * 100,
                    "mean_latency_change_percent": (
                        improved.mean_latency_ms / adapt.mean_latency_ms - 1
                    )
                    * 100,
                    "p99_change_percent": (
                        improved.mean_window_p99_ms / adapt.mean_window_p99_ms - 1
                    )
                    * 100,
                    "pool_change_reduction_percent": (
                        1 - improved.pool_change_count / adapt.pool_change_count
                    )
                    * 100,
                    "thread_total_variation_reduction_percent": (
                        1
                        - improved.thread_total_variation
                        / adapt.thread_total_variation
                    )
                    * 100,
                    "as_stable_window_fraction": improved.stable_window_fraction,
                    "as_dominant_stable_pool_size": improved.dominant_stable_pool_size,
                    "as_load_change_detection_count": int(
                        improved.load_change_detection_count
                    ),
                }
            )
    effects = pd.DataFrame(rows)
    effects["point_noninferior"] = (
        (effects.throughput_change_percent >= -5)
        & (effects.mean_latency_change_percent <= 5)
        & (effects.p99_change_percent <= 10)
    )
    return effects


def ratio_ci(values: np.ndarray) -> tuple[float, float, float, float, float]:
    logs = np.log(values)
    mean = logs.mean()
    standard_error = logs.std(ddof=1) / math.sqrt(len(logs))
    half_width = T_CRITICAL_DF4_95 * standard_error
    one_sided_half_width = T_CRITICAL_DF4_ONE_SIDED_95 * standard_error
    return tuple(
        (
            np.exp(
                [
                    mean,
                    mean - half_width,
                    mean + half_width,
                    mean - one_sided_half_width,
                    mean + one_sided_half_width,
                ]
            )
            - 1
        )
        * 100
    )


def aggregate_effects(runs: pd.DataFrame, effects: pd.DataFrame) -> pd.DataFrame:
    rows = []
    metrics = {
        "throughput": "throughput_rps",
        "mean_latency": "mean_latency_ms",
        "p99": "mean_window_p99_ms",
    }
    for users in (10, 100, 500):
        subset = runs[runs.users == users]
        ratios: dict[str, list[float]] = {name: [] for name in metrics}
        for round_name in ROUNDS:
            pair = subset[subset["round"] == round_name].set_index("policy")
            for name, column in metrics.items():
                ratios[name].append(
                    float(pair.loc["AS_ADAPT_T", column])
                    / float(pair.loc["ADAPT_T", column])
                )
        estimates = {
            name: ratio_ci(np.array(values, dtype=float))
            for name, values in ratios.items()
        }
        user_effects = effects[effects.users == users]
        rows.append(
            {
                "users": users,
                "throughput_change_percent": estimates["throughput"][0],
                "throughput_ci_low": estimates["throughput"][1],
                "throughput_ci_high": estimates["throughput"][2],
                "throughput_one_sided_95_lower": estimates["throughput"][3],
                "mean_latency_change_percent": estimates["mean_latency"][0],
                "mean_latency_ci_low": estimates["mean_latency"][1],
                "mean_latency_ci_high": estimates["mean_latency"][2],
                "mean_latency_one_sided_95_upper": estimates["mean_latency"][4],
                "p99_change_percent": estimates["p99"][0],
                "p99_ci_low": estimates["p99"][1],
                "p99_ci_high": estimates["p99"][2],
                "p99_one_sided_95_upper": estimates["p99"][4],
                "mean_pool_change_reduction_percent": user_effects.pool_change_reduction_percent.mean(),
                "mean_thread_total_variation_reduction_percent": user_effects.thread_total_variation_reduction_percent.mean(),
                "mean_as_stable_window_fraction": user_effects.as_stable_window_fraction.mean(),
                "point_noninferior_rounds": int(user_effects.point_noninferior.sum()),
                "statistical_noninferiority_all_metrics": (
                    estimates["throughput"][3] >= -5
                    and estimates["mean_latency"][4] <= 5
                    and estimates["p99"][4] <= 10
                ),
            }
        )
    return pd.DataFrame(rows)


def absolute_summary(runs: pd.DataFrame) -> pd.DataFrame:
    metrics = [
        "throughput_rps",
        "mean_latency_ms",
        "mean_window_p99_ms",
        "pool_change_count",
        "thread_total_variation",
        "stable_window_fraction",
    ]
    rows = []
    for (users, policy), group in runs.groupby(["users", "policy"], sort=True):
        row = {"users": users, "policy": policy, "independent_runs": len(group)}
        for metric in metrics:
            row[f"{metric}_mean"] = group[metric].mean()
            row[f"{metric}_sd"] = group[metric].std(ddof=1)
        rows.append(row)
    return pd.DataFrame(rows)


def markdown_table(frame: pd.DataFrame, columns: list[str]) -> str:
    lines = [
        "| " + " | ".join(columns) + " |",
        "| " + " | ".join(["---"] * len(columns)) + " |",
    ]
    for _, row in frame[columns].iterrows():
        values = []
        for value in row:
            if isinstance(value, (float, np.floating)):
                values.append(f"{value:.2f}")
            elif isinstance(value, (bool, np.bool_)):
                values.append("是" if value else "否")
            else:
                values.append(str(value))
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines)


def write_report(effects: pd.DataFrame, aggregate: pd.DataFrame) -> None:
    round_table = effects.rename(
        columns={
            "users": "用户数",
            "round": "轮次",
            "throughput_change_percent": "吞吐量变化%",
            "mean_latency_change_percent": "平均延迟变化%",
            "p99_change_percent": "P99变化%",
            "thread_total_variation_reduction_percent": "线程变化量减少%",
            "point_noninferior": "单轮通过",
        }
    )
    aggregate_table = aggregate.rename(
        columns={
            "users": "用户数",
            "throughput_change_percent": "吞吐量变化%",
            "throughput_ci_low": "吞吐量CI下界",
            "throughput_ci_high": "吞吐量CI上界",
            "mean_latency_change_percent": "平均延迟变化%",
            "mean_latency_ci_low": "平均延迟CI下界",
            "mean_latency_ci_high": "平均延迟CI上界",
            "p99_change_percent": "P99变化%",
            "p99_ci_low": "P99 CI下界",
            "p99_ci_high": "P99 CI上界",
            "mean_thread_total_variation_reduction_percent": "线程变化量减少%",
            "point_noninferior_rounds": "通过轮数",
            "statistical_noninferiority_all_metrics": "CI联合非劣",
        }
    )
    report = [
        "# PRIME 五轮独立重复实验分析",
        "",
        "## 各轮点估计",
        "",
        markdown_table(
            round_table,
            [
                "用户数",
                "轮次",
                "吞吐量变化%",
                "平均延迟变化%",
                "P99变化%",
                "线程变化量减少%",
                "单轮通过",
            ],
        ),
        "",
        "## 五轮合并（配对对数比，双侧95% t区间，df=4）",
        "",
        markdown_table(
            aggregate_table,
            [
                "用户数",
                "吞吐量变化%",
                "吞吐量CI下界",
                "吞吐量CI上界",
                "平均延迟变化%",
                "平均延迟CI下界",
                "平均延迟CI上界",
                "P99变化%",
                "P99 CI下界",
                "P99 CI上界",
                "线程变化量减少%",
                "通过轮数",
                "CI联合非劣",
            ],
        ),
        "",
        "说明：五轮是独立实验单位；表中展示双侧95%区间，联合非劣判断使用单侧95%界限。",
        "由于只有5个配对，置信区间仍需谨慎解释。",
        "窗口数据只用于计算每轮指标，没有把750个窗口当成750次独立重复。",
        "",
    ]
    (OUTPUT / "prime-five-round-analysis.md").write_text(
        "\n".join(report), encoding="utf-8"
    )


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    runs = load_all()
    effects = paired_effects(runs)
    aggregate = aggregate_effects(runs, effects)
    absolute = absolute_summary(runs)
    runs.to_csv(OUTPUT / "prime-five-round-run-summary.csv", index=False)
    effects.to_csv(OUTPUT / "prime-five-round-paired-effects.csv", index=False)
    aggregate.to_csv(OUTPUT / "prime-five-round-aggregate-effects.csv", index=False)
    absolute.to_csv(OUTPUT / "prime-five-round-absolute-summary.csv", index=False)
    write_report(effects, aggregate)
    print(f"runs={len(runs)} paired_comparisons={len(effects)}")
    print(aggregate.to_string(index=False, float_format=lambda value: f"{value:.3f}"))


if __name__ == "__main__":
    main()
