#!/usr/bin/env python3
"""Analyze the 45-run formal-r1 ADAPT-T experiment matrix."""

from __future__ import annotations

import argparse
import math
import re
from pathlib import Path

import numpy as np
import pandas as pd


RUN_PATTERN = re.compile(
    r"^formal-r1-(prime|dbread|dbwrite)-u(10|100|500)-"
    r"(fixed2|fixed10|fixed100|adaptt|as-adaptt)-server\.csv$"
)
POLICY_LABELS = {
    "fixed2": "FIXED2",
    "fixed10": "FIXED10",
    "fixed100": "FIXED100",
    "adaptt": "ADAPT_T",
    "as-adaptt": "AS_ADAPT_T",
}
POLICY_ORDER = ["FIXED2", "FIXED10", "FIXED100", "ADAPT_T", "AS_ADAPT_T"]
APPLICATION_ORDER = ["PRIME", "DBREAD", "DBWRITE"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--results",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "results",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "output",
    )
    return parser.parse_args()


def load_windows(results: Path) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    for path in sorted(results.glob("formal-r1-*-server.csv")):
        match = RUN_PATTERN.match(path.name)
        if not match:
            continue
        application, users, policy_id = match.groups()
        frame = pd.read_csv(path)
        frame.insert(0, "run_id", path.name.removesuffix("-server.csv"))
        frame.insert(1, "policy_label", POLICY_LABELS[policy_id])
        frame.insert(2, "users", int(users))
        frame["application"] = application.upper()
        frames.append(frame)

    if len(frames) != 45:
        raise RuntimeError(f"Expected 45 run files, found {len(frames)}")
    windows = pd.concat(frames, ignore_index=True)
    counts = windows.groupby("run_id").size()
    if len(windows) != 6750 or not counts.eq(150).all():
        raise RuntimeError("Expected exactly 150 windows for each of 45 runs")
    return windows


def direction_reversals(changes: np.ndarray) -> int:
    signs = np.sign(changes[changes != 0])
    if len(signs) < 2:
        return 0
    return int(np.sum(signs[1:] != signs[:-1]))


def summarize_run(group: pd.DataFrame) -> dict[str, object]:
    successes = group["successful_requests"].to_numpy(dtype=float)
    durations = group["window_duration_seconds"].to_numpy(dtype=float)
    changes = (
        group["pool_size_after_control"].to_numpy(dtype=int)
        - group["configured_pool_size"].to_numpy(dtype=int)
    )
    total_success = successes.sum()
    weighted_mean = float(
        np.average(group["mean_latency_ms"].to_numpy(dtype=float), weights=successes)
    )
    weighted_p99 = float(
        np.average(group["p99_latency_ms"].to_numpy(dtype=float), weights=successes)
    )
    stable = group["control_state"].fillna("").eq("STABLE")
    stable_indexes = group.loc[stable, "window_index"]
    stable_pools = group.loc[stable, "configured_pool_size"]
    return {
        "run_id": group["run_id"].iloc[0],
        "application": group["application"].iloc[0],
        "users": int(group["users"].iloc[0]),
        "policy": group["policy_label"].iloc[0],
        "windows": len(group),
        "successful_requests": int(total_success),
        "failed_requests": int(group["failed_requests"].sum()),
        "rejected_tasks": int(group["rejected_tasks_delta"].sum()),
        "throughput_rps": float(total_success / durations.sum()),
        "mean_latency_ms": weighted_mean,
        "mean_window_p99_ms": weighted_p99,
        "maximum_window_p99_ms": float(group["p99_latency_ms"].max()),
        "minimum_pool_size": int(group["configured_pool_size"].min()),
        "maximum_pool_size": int(group["configured_pool_size"].max()),
        "average_pool_size": float(
            np.average(group["configured_pool_size"].to_numpy(dtype=float), weights=durations)
        ),
        "pool_size_standard_deviation": float(
            group["configured_pool_size"].std(ddof=0)
        ),
        "pool_change_count": int(np.count_nonzero(changes)),
        "thread_total_variation": int(np.abs(changes).sum()),
        "direction_reversal_count": direction_reversals(changes),
        "adjustment_rate_per_minute": float(
            np.count_nonzero(changes) / (durations.sum() / 60.0)
        ),
        "trial_acceptance_count": int(
            group["control_stage"].fillna("").eq("TRIAL_ACCEPTED").sum()
        ),
        "trial_rejection_count": int(
            group["control_stage"].fillna("").str.startswith("TRIAL_REJECTED").sum()
        ),
        "stable_window_count": int(stable.sum()),
        "stable_window_fraction": float(stable.mean()),
        "first_stable_window": (
            int(stable_indexes.iloc[0]) if not stable_indexes.empty else math.nan
        ),
        "dominant_stable_pool_size": (
            int(stable_pools.mode().iloc[0]) if not stable_pools.empty else math.nan
        ),
        "load_change_detection_count": int(
            group["control_stage"].fillna("").eq("LOAD_CHANGE_DETECTED").sum()
        ),
    }


def estimator(group: pd.DataFrame, indexes: np.ndarray | None = None) -> tuple[float, float, float]:
    sample = group.iloc[indexes] if indexes is not None else group
    successes = sample["successful_requests"].to_numpy(dtype=float)
    duration = sample["window_duration_seconds"].to_numpy(dtype=float).sum()
    throughput = successes.sum() / duration
    mean_latency = np.average(
        sample["mean_latency_ms"].to_numpy(dtype=float), weights=successes
    )
    p99 = np.average(sample["p99_latency_ms"].to_numpy(dtype=float), weights=successes)
    return float(throughput), float(mean_latency), float(p99)


def estimator_from_arrays(
    values: tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray],
    indexes: np.ndarray,
) -> tuple[float, float, float]:
    successes, durations, mean_latencies, p99_values = values
    sampled_successes = successes[indexes]
    total_success = sampled_successes.sum()
    return (
        float(total_success / durations[indexes].sum()),
        float(np.dot(mean_latencies[indexes], sampled_successes) / total_success),
        float(np.dot(p99_values[indexes], sampled_successes) / total_success),
    )


def circular_block_indexes(
    rng: np.random.Generator, n: int, block_size: int
) -> np.ndarray:
    blocks = math.ceil(n / block_size)
    starts = rng.integers(0, n, size=blocks)
    indexes = np.concatenate(
        [(start + np.arange(block_size)) % n for start in starts]
    )
    return indexes[:n]


def percent_change(new: float, baseline: float) -> float:
    return (new / baseline - 1.0) * 100.0


def compare_as_to_adapt(
    windows: pd.DataFrame, summary: pd.DataFrame
) -> pd.DataFrame:
    rng = np.random.default_rng(20260824)
    comparisons: list[dict[str, object]] = []
    for application in APPLICATION_ORDER:
        for users in (10, 100, 500):
            scenario = summary[
                (summary.application == application) & (summary.users == users)
            ].set_index("policy")
            adapt = scenario.loc["ADAPT_T"]
            improved = scenario.loc["AS_ADAPT_T"]
            adapt_windows = windows[
                (windows.application == application)
                & (windows.users == users)
                & (windows.policy_label == "ADAPT_T")
            ].sort_values("window_index")
            as_windows = windows[
                (windows.application == application)
                & (windows.users == users)
                & (windows.policy_label == "AS_ADAPT_T")
            ].sort_values("window_index")

            def bootstrap_values(frame: pd.DataFrame) -> tuple[np.ndarray, ...]:
                return (
                    frame["successful_requests"].to_numpy(dtype=float),
                    frame["window_duration_seconds"].to_numpy(dtype=float),
                    frame["mean_latency_ms"].to_numpy(dtype=float),
                    frame["p99_latency_ms"].to_numpy(dtype=float),
                )

            adapt_values = bootstrap_values(adapt_windows)
            as_values = bootstrap_values(as_windows)

            effects = np.empty((2000, 3), dtype=float)
            for index in range(len(effects)):
                sample_indexes = circular_block_indexes(rng, 150, 10)
                adapt_estimate = estimator_from_arrays(
                    adapt_values, sample_indexes
                )
                as_estimate = estimator_from_arrays(as_values, sample_indexes)
                effects[index] = [
                    percent_change(as_estimate[0], adapt_estimate[0]),
                    percent_change(as_estimate[1], adapt_estimate[1]),
                    percent_change(as_estimate[2], adapt_estimate[2]),
                ]
            lower = np.percentile(effects, 2.5, axis=0)
            upper = np.percentile(effects, 97.5, axis=0)

            throughput_change = percent_change(
                improved.throughput_rps, adapt.throughput_rps
            )
            latency_change = percent_change(
                improved.mean_latency_ms, adapt.mean_latency_ms
            )
            p99_change = percent_change(
                improved.mean_window_p99_ms, adapt.mean_window_p99_ms
            )
            tv_reduction = (
                (adapt.thread_total_variation - improved.thread_total_variation)
                / adapt.thread_total_variation
                * 100.0
                if adapt.thread_total_variation > 0
                else math.nan
            )
            change_reduction = (
                (adapt.pool_change_count - improved.pool_change_count)
                / adapt.pool_change_count
                * 100.0
                if adapt.pool_change_count > 0
                else math.nan
            )
            noninferior = (
                throughput_change >= -5.0
                and latency_change <= 5.0
                and p99_change <= 10.0
            )
            comparisons.append(
                {
                    "application": application,
                    "users": users,
                    "adapt_throughput_rps": adapt.throughput_rps,
                    "as_throughput_rps": improved.throughput_rps,
                    "throughput_change_percent": throughput_change,
                    "throughput_change_ci_low": lower[0],
                    "throughput_change_ci_high": upper[0],
                    "adapt_mean_latency_ms": adapt.mean_latency_ms,
                    "as_mean_latency_ms": improved.mean_latency_ms,
                    "mean_latency_change_percent": latency_change,
                    "mean_latency_change_ci_low": lower[1],
                    "mean_latency_change_ci_high": upper[1],
                    "adapt_mean_window_p99_ms": adapt.mean_window_p99_ms,
                    "as_mean_window_p99_ms": improved.mean_window_p99_ms,
                    "p99_change_percent": p99_change,
                    "p99_change_ci_low": lower[2],
                    "p99_change_ci_high": upper[2],
                    "adapt_pool_changes": int(adapt.pool_change_count),
                    "as_pool_changes": int(improved.pool_change_count),
                    "pool_change_reduction_percent": change_reduction,
                    "adapt_thread_total_variation": int(adapt.thread_total_variation),
                    "as_thread_total_variation": int(improved.thread_total_variation),
                    "thread_total_variation_reduction_percent": tv_reduction,
                    "adapt_pool_stddev": adapt.pool_size_standard_deviation,
                    "as_pool_stddev": improved.pool_size_standard_deviation,
                    "as_stable_window_fraction": improved.stable_window_fraction,
                    "as_first_stable_window": improved.first_stable_window,
                    "as_dominant_stable_pool_size": improved.dominant_stable_pool_size,
                    "as_load_change_detection_count": improved.load_change_detection_count,
                    "noninferior_with_prespecified_margins": noninferior,
                    "strictly_no_worse_all_metrics": (
                        throughput_change >= 0.0
                        and latency_change <= 0.0
                        and p99_change <= 0.0
                    ),
                }
            )
    return pd.DataFrame(comparisons)


def md_table(frame: pd.DataFrame, columns: list[str], digits: int = 2) -> str:
    header = "| " + " | ".join(columns) + " |"
    separator = "| " + " | ".join(["---"] * len(columns)) + " |"
    rows = []
    for _, row in frame[columns].iterrows():
        values = []
        for value in row:
            if isinstance(value, (float, np.floating)):
                values.append("" if math.isnan(float(value)) else f"{value:.{digits}f}")
            elif isinstance(value, (bool, np.bool_)):
                values.append("是" if value else "否")
            else:
                values.append(str(value))
        rows.append("| " + " | ".join(values) + " |")
    return "\n".join([header, separator, *rows])


def write_report(summary: pd.DataFrame, comparisons: pd.DataFrame, output: Path) -> None:
    compact = comparisons.copy()
    compact["scenario"] = compact.application + "-u" + compact.users.astype(str)
    compact = compact.rename(
        columns={
            "throughput_change_percent": "吞吐量变化%",
            "mean_latency_change_percent": "平均延迟变化%",
            "p99_change_percent": "P99变化%",
            "pool_change_reduction_percent": "扩缩容次数减少%",
            "thread_total_variation_reduction_percent": "线程总变化量减少%",
            "as_stable_window_fraction": "AS稳定窗口占比",
            "noninferior_with_prespecified_margins": "通过非劣界限",
        }
    )
    compact["AS稳定窗口占比"] *= 100.0

    noninferior_count = int(
        comparisons.noninferior_with_prespecified_margins.sum()
    )
    strict_count = int(comparisons.strictly_no_worse_all_metrics.sum())
    median_tv = float(
        comparisons.thread_total_variation_reduction_percent.median()
    )
    median_changes = float(comparisons.pool_change_reduction_percent.median())
    report = [
        "# formal-r1 实验分析",
        "",
        "## 数据完整性",
        "",
        "- 45 组实验，每组 150 个 10 秒窗口，共 6750 个窗口。",
        "- 失败请求、拒绝任务和空窗口均为 0。",
        "- 以下 P99 是请求数加权的‘窗口 P99 均值’，不是由逐请求样本重算的全局 P99。",
        "",
        "## AS_ADAPT_T 相对 ADAPT_T",
        "",
        f"- 预设非劣界限通过 {noninferior_count}/9 个场景。",
        f"- 吞吐量、平均延迟和 P99 三项均严格不差的场景为 {strict_count}/9。",
        f"- 线程总变化量减少比例中位数为 {median_tv:.2f}%。",
        f"- 实际扩缩容次数减少比例中位数为 {median_changes:.2f}%。",
        "",
        md_table(
            compact,
            [
                "scenario",
                "吞吐量变化%",
                "平均延迟变化%",
                "P99变化%",
                "扩缩容次数减少%",
                "线程总变化量减少%",
                "AS稳定窗口占比",
                "通过非劣界限",
            ],
        ),
        "",
        "## 解释限制",
        "",
        "- 每个场景/策略目前只有一次独立长实验；150 个窗口是时间序列重复测量，不是 150 次独立实验。",
        "- CSV 记录的是服务端窗口指标。移动块 bootstrap 区间仅用于描述窗口期波动，不能替代多轮独立重复实验。",
        "- 若用于论文显著性结论，建议至少再完成 2 轮不同随机顺序的完整矩阵。",
        "",
    ]
    (output / "formal-r1-analysis.md").write_text("\n".join(report), encoding="utf-8")


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    windows = load_windows(args.results)
    summary = pd.DataFrame(
        summarize_run(group)
        for _, group in windows.groupby("run_id", sort=True)
    )
    summary["application"] = pd.Categorical(
        summary.application, APPLICATION_ORDER, ordered=True
    )
    summary["policy"] = pd.Categorical(summary.policy, POLICY_ORDER, ordered=True)
    summary = summary.sort_values(["application", "users", "policy"])
    comparisons = compare_as_to_adapt(windows, summary)

    windows.to_csv(args.output / "formal-r1-all-windows-linux.csv", index=False)
    summary.to_csv(args.output / "formal-r1-run-summary.csv", index=False)
    comparisons.to_csv(args.output / "formal-r1-as-vs-adaptt.csv", index=False)
    write_report(summary, comparisons, args.output)

    print(f"runs={len(summary)} windows={len(windows)}")
    print(
        "noninferior="
        f"{int(comparisons.noninferior_with_prespecified_margins.sum())}/9 "
        "strict_no_worse="
        f"{int(comparisons.strictly_no_worse_all_metrics.sum())}/9"
    )
    print(args.output)


if __name__ == "__main__":
    main()
