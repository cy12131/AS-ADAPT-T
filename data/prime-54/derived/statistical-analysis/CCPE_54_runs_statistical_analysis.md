# CCPE 54 Runs Statistical Analysis

# 1. Executive Summary

本次补充实验总体增强核心贡献，但不是“所有指标全面改善”。最终判断为：

**B. 总体增强，但部分 claim 需要收缩。**

主要结论：

- request-level P99 支持原论文的尾延迟结论。AS-ADAPT-T 相对 ADAPT-T 在稳定 10/100/500 users 下分别下降 47.9%、27.1%、31.0%，dynamic 下降 29.4%；所有 paired runs 方向一致。
- P99.9 同样支持，并给出更强的极端尾部证据：稳定下降 47.3%、31.0%、36.1%，dynamic 下降 36.2%。
- weighted-window P99 与 global request P99 在全部稳定并发和 dynamic 中方向一致，说明旧结论没有因统计口径切换而反转。
- AS 并非改善所有 latency 指标：相对 ADAPT-T，100/500 users 的 mean 增加 5.5%/22.4%，dynamic mean 增加 8.8%；dynamic P95 估计增加 0.9%。论文应明确这是 mean–tail trade-off。
- 相对 Queue，AS 的 P99/P99.9 优势稳定且明显，但 100/500/dynamic 的 mean 更高。Queue/u500 的真实 max spike 已经推高 P99.9，不只是孤立 max。
- 搜索开销直接支持“减少无效搜索”：稳定 completed trials 下降 68%–82%，rejected/rollback 下降 72%–84%；dynamic completed trials 下降 38%。
- dynamic recovery 有效但具有条件性：三轮均在 100→10 和 10→200 后恢复搜索并重入 STABLE；200→0 和 0→50 没有满足连续变化阈值，不能写成“每次 workload change 都触发 recovery”。
- Java patch 不与主要 AS–ADAPT 算法身份混杂；两个 patch 子集中的 P99/P99.9、A/V 方向一致，但小样本不足以证明 patch 完全无效应。

# 2. Dataset and Statistical Method

分析单位为完整独立 run：稳定负载每组 n=5，dynamic 每算法 n=3。稳定比较按同 concurrency、同 R1–R5 配对；runner 在各轮轮换算法执行顺序，因此 AS–Queue 也按同 round 作为补充配对比较。dynamic 同样按 R1–R3 配对。

组级原始结果报告 arithmetic mean 和 sample SD。配对效应采用预设 log-ratio：

`r_i = ln(X_AS,i / X_reference,i)`

在 mean log-ratio 上计算 t-based 95% CI，再指数变换为百分比变化。stable df=4，dynamic df=2。没有以 p-value 作为主要证据，也没有 bootstrap、outlier 删除或事后选择统计方法。

global request Mean/P50/P95/P99/P99.9 来自合并每轮全部非空 HLOG 分布。weighted-window P99 单独按 `Σ(success_w × window_P99_w) / Σsuccess_w` 计算。两者不混用。Dynamic window 91 的排空请求保留；92–120 没有 latency sample。

Master 数据见 `CCPE_54_run_master.csv`；所有 Queue candidate/trial/rate 字段为 `NA`，不把 hysteresis resize 强行解释为候选试探。

# 3. Stable PRIME Results

## 3.1 10 users

| Algorithm | Throughput | Mean ms | P95 ms | P99 ms | P99.9 ms | A | V |
|---|---:|---:|---:|---:|---:|---:|---:|
| Queue | 3503.2 | 1.689 | 6.015 | 10.048 | 15.303 | 29.0 | 145.0 |
| ADAPT-T | 4041.2 | 1.263 | 5.047 | 12.350 | 20.139 | 75.8 | 758.0 |
| AS-ADAPT-T | 4615.6 | 1.001 | 4.489 | 6.445 | 10.646 | 26.8 | 95.4 |

AS vs ADAPT 的 throughput +14.1% [9.0%, 19.5%]，Mean −20.8% [−22.8%, −18.7%]，P95 −11.1%，P99 −47.9%，P99.9 −47.3%；五轮方向均一致。10 users 是三项性能与控制稳定性同时改善的场景。

R1/R3/R4 的 AS 发生稳定状态恢复，导致本组 A/candidate 离散度高于 100/500 users，但仍远低于 ADAPT-T。R5 三算法吞吐共同下降，配对比值部分抵消了共享批次波动。

## 3.2 100 users

| Algorithm | Throughput | Mean ms | P95 ms | P99 ms | P99.9 ms | A | V |
|---|---:|---:|---:|---:|---:|---:|---:|
| Queue | 8777.1 | 9.108 | 26.477 | 63.537 | 125.082 | 22.8 | 113.0 |
| ADAPT-T | 8869.8 | 9.098 | 26.886 | 54.021 | 73.230 | 76.2 | 762.0 |
| AS-ADAPT-T | 8852.4 | 9.602 | 24.075 | 39.381 | 50.495 | 13.2 | 54.2 |

AS vs ADAPT throughput −0.2% [−0.7%, 0.3%]，Mean +5.5% [4.5%, 6.5%]，但 P95 −10.4%、P99 −27.1%、P99.9 −31.0%，且尾延迟五轮全部改善。这是明确的 mean–tail trade-off，不应概括为“延迟全面下降”。

## 3.3 500 users

| Algorithm | Throughput | Mean ms | P95 ms | P99 ms | P99.9 ms | A | V |
|---|---:|---:|---:|---:|---:|---:|---:|
| Queue | 8356.2 | 43.422 | 85.669 | 149.396 | 452.408 | 25.6 | 127.0 |
| ADAPT-T | 8292.2 | 44.277 | 85.590 | 142.397 | 213.044 | 76.0 | 760.0 |
| AS-ADAPT-T | 8742.0 | 54.215 | 78.722 | 98.291 | 135.633 | 14.2 | 56.4 |

AS vs ADAPT throughput +5.4% [4.8%, 6.1%]，Mean +22.4% [21.4%, 23.5%]，P95 −8.0%、P99 −31.0%、P99.9 −36.1%。尾部与 throughput 改善的同时，mean 明显上升，是必须保留的权衡。

Queue 的五轮 max 为 1.14–2.24 s；其 P99.9 均值达到 452.4 ms，而 P99 为 149.4 ms。因而这些慢请求不仅影响 max，至少已有足够质量进入最慢 0.1% 的分布。AS vs Queue 的 P99.9 下降 69.8% [64.7%, 74.2%]。

# 4. Request-level Tail Latency

AS vs ADAPT 的主要结果：

| 场景 | P95 change | P99 change | P99.9 change | P99/P99.9 paired direction |
|---|---:|---:|---:|---:|
| stable 10 | −11.1% | −47.9% [−53.0%, −42.3%] | −47.3% [−52.7%, −41.2%] | 5/5 lower |
| stable 100 | −10.4% | −27.1% [−28.5%, −25.7%] | −31.0% [−32.6%, −29.4%] | 5/5 lower |
| stable 500 | −8.0% | −31.0% [−34.2%, −27.6%] | −36.1% [−43.0%, −28.4%] | 5/5 lower |
| dynamic | +0.9% | −29.4% [−34.5%, −23.9%] | −36.2% [−44.5%, −26.7%] | 3/3 lower |

weighted-window P99 的 AS vs ADAPT 变化为 stable −47.8%/−25.9%/−33.2%，dynamic −25.2%。它与 global request P99 的方向完全一致，但幅度不等，因此两者可以相互支持、不能互相替代。

P99.9 暴露了 Queue/u500 的更深尾部，也加强了 AS 在 100/500/dynamic 下的证据。它没有发现 AS 自身相对于 ADAPT 的方向反转。

# 5. Queue-based Online Baseline

AS vs Queue 的稳定 P99 在 10/100/500 users 分别下降 36.0%/38.0%/34.2%，P99.9 分别下降 30.6%/59.6%/69.8%；三组都是 5/5 paired runs 更低。A 在 100/500 users 下降约 42%/44%，V 下降约 52%/55%；10 users 的 A/V CI 较宽，不能声称稳定占优。

Queue 并非整体劣于 ADAPT-T：10 users Queue 的 P99/P99.9 好于 ADAPT-T，但 throughput 较低；100/500 users ADAPT-T 的深尾部好于 Queue，却使用约 3 倍 A 和 5–7 倍 V。Queue 的价值是提供一个控制简单、调整幅度中等的工程基线，而不是三算法单一总排名。

Dynamic 中 AS vs Queue throughput +1.1% [−0.02%, 2.3%]，Mean +9.6%，P95 +2.1%，但 P99 −31.7%、P99.9 −39.5%、A −31.1%、V −49.1%。结论仍是尾部与控制稳定性优势，而不是所有 latency 指标优势。

# 6. Controller Search Overhead

稳定 10/100/500 users 下，AS completed trials 分别下降 68.2%/81.8%/81.3%，rejected/rollback 下降 72.1%/83.7%/81.5%。对应 A 下降 69.6%/82.7%/81.4%，V 下降 89.2%/92.9%/92.6%。

Dynamic 中 candidate 下降 38.8%，completed trials 下降 38.2%，rejected/rollback 下降 32.6%，A/V 下降 36.0%/76.4%。较小的 candidate 降幅是恢复机制主动重启搜索的结果。

没有“A/V 很低但 candidate 很高”的反常 run。V 的降幅系统性大于 candidate 降幅，说明收益同时来自减少试探次数和缩小每次线程变化幅度。完整分析见 `controller_search_overhead_analysis.md`。

# 7. Controller Execution Time

计时口径是 `controller.onWindow` 的 wall-clock elapsed time，不是 CPU time。18 轮/算法的平均值如下：

| Algorithm | per-decision mean | median | P95 | P99 | run total | total/run |
|---|---:|---:|---:|---:|---:|---:|
| Queue | 1.559 ms | 0.0077 ms | 11.004 ms | 18.298 ms | 233.9 ms | 0.0156% |
| ADAPT-T | 4.356 ms | 0.659 ms | 19.738 ms | 27.530 ms | 653.4 ms | 0.0436% |
| AS-ADAPT-T | 1.294 ms | 0.316 ms | 5.677 ms | 14.187 ms | 194.2 ms | 0.0129% |

AS 的状态机没有形成可观察的整体计算负担；稳定保持减少了昂贵 resize/rollback 路径，使 total 反而低于 ADAPT-T。Queue 的普通 hold 决策中位数极低，但 resize 决策抬高了 run mean/tail。所有 controller time 都只占 1500 s 的万分之几，不能据此推断系统 CPU usage 降低。

# 8. Dynamic Workload Results

全轮（含 window 91 排空请求，不含 92–120 的不存在样本）均值：

| Algorithm | Throughput | Mean ms | P95 ms | P99 ms | P99.9 ms | A | V |
|---|---:|---:|---:|---:|---:|---:|---:|
| Queue | 5950.8 | 9.287 | 29.174 | 65.143 | 104.355 | 57.0 | 284.7 |
| ADAPT-T | 5959.4 | 9.352 | 29.529 | 62.915 | 98.828 | 61.3 | 613.3 |
| AS-ADAPT-T | 6019.3 | 10.178 | 29.786 | 44.433 | 63.089 | 39.3 | 145.0 |

AS vs ADAPT throughput +1.0% [−0.2%, 2.2%]，Mean +8.8%，P95 +0.9%，P99 −29.4%，P99.9 −36.2%。n=3 下 CI 较宽，但三轮 P99/P99.9/A/V 均同方向。

阶段结果见 `dynamic_stage_results.csv`。AS 的 P99 在 100/200/50 users 为 39.7/54.8/30.7 ms，均低于 ADAPT 的 51.4/84.3/32.3 ms；10-user 阶段两者几乎相同（12.98 vs 12.97 ms）。AS 在 200 users 的 Mean 较高（20.07 vs 17.15 ms），但 P99/P99.9 明显更低，再次体现分布形状而非整体平移。

Queue response delay 在大多数变化为 10–40 s，但 dynamic R3 的 100→10 缩容延迟约 100 s，因为队列未立即连续落到低阈值。每轮 A=56–59、V=279–295。三轮在 50-user 阶段均出现 increase→decrease→increase 的方向块，说明阈值附近存在有限振荡；不是每窗口抖动，但比 AS 稳定保持更持续。

# 9. Recovery Analysis

每轮 AS 均在 100→10 后 window 33 检出变化；在 10→200 后 window 62 或 63 检出。detection delay 为 20–30 s，随后固定用约 30 s 收集三个 recovery baseline 窗口。自 baseline 建立到重新进入 STABLE 为约 150–230 s，从 workload change 到 re-stable 为约 210–290 s。

部分 recovery 搜索没有 accepted candidate，最终通过失败/边界收敛重新进入 STABLE；这不等于 recovery 失败，因为算法目标是重新验证当前局部配置，而非强制接受新线程数。

200→0 只有 window 91 的排空请求，随后空窗口清零连续变化计数，故不触发 recovery。0→50 阶段出现 1–3 个 pending 窗口但未连续满足 3 窗口条件；其 AS stage P99/P99.9 为 30.7/39.7 ms，低于 ADAPT 与 Queue，当前没有性能失控证据。完整事件见 `dynamic_recovery_analysis.csv`。

# 10. Java Patch Sensitivity

17.0.20 的算法分布为 ADAPT/AS/Queue=6/6/5，17.0.20.1 为 12/12/13。AS–ADAPT 的所有同 round pair 都是同 patch，因此主要算法效应不与 patch 身份混杂。

两个 patch 子集内，AS 的 P99/P99.9 在所有 concurrency 和 dynamic 中均低于 ADAPT，A/V 方向也一致。子组 n=1–4，不能估计可靠 interaction。结论为：**未发现 patch 改变主要结果方向的证据，但不能证明 patch 无影响。** 详见 `java_patch_sensitivity.md`。

# 11. Batch Effects and Outliers

PRIME 10/R5 相对各算法自身五轮中位数：Queue −5.83%、ADAPT-T −5.61%、AS −6.21%。三算法同方向、近似同幅度下降，符合 likely shared batch/environmental fluctuation。保留 R5，并采用 paired log-ratio，可以抵消一部分共同乘性变化。

AS/u10 R1 相对自身中位数也偏低，且使用旧 Java patch；它没有被删除。同 patch 子组和五轮逐对结果方向均未因该点改变。

Queue/u500 的高 max 和 P99.9 均完整保留。图 A/B 显示全部原始点，没有裁剪纵轴以隐藏异常值。

# 12. Claim Validation Matrix

逐项矩阵见 `claim_validation_matrix.md`。核心 claim “降低尾延迟、减少控制变化、减少重复试探、保持动态恢复能力”得到支持；需要限定的是：

- tail latency 指 P95/P99/P99.9 或 weighted-window P99，不等于 Mean；
- dynamic adaptability 不等于每次 workload transition 必须触发 recovery；
- throughput 没有系统性损失，但不同场景可有正负变化；
- Queue 对比支持 AS 的深尾和稳定性优势，不支持“所有指标全面胜出”。

# 13. Recommended Paper Changes

不修改正文的前提下，建议后续编辑时：

1. 在实验设计中增加 HdrHistogram 全轮合并口径、Queue baseline、candidate completion 定义和 controller elapsed timing。
2. 在结果中新增 global request P95/P99/P99.9 表，并把 weighted-window P99 明确保留为旧控制过程指标。
3. 增加 Queue 对照，但围绕 tail/control trade-off 讨论，不扩展成三算法全面排名。
4. 用 completed trial 作 acceptance/rejection/rollback rate 分母，单列两个 endpoint unevaluated candidates。
5. 明确报告 100/500/dynamic 的 Mean 增加与 P99/P99.9 下降并存。
6. Dynamic recovery 写成 threshold-conditioned recovery；报告 20–30 s detection、30 s baseline 和 210–290 s re-stable。
7. 将 controller time 称为 elapsed execution time，不称 CPU time。
8. 在 limitations 中披露 Java patch 批次和客户端 JTL/逐请求明细缺失。

# 14. Final Interpretation

**B. 总体增强，但部分 claim 需要收缩。**

新增 request-level P99/P99.9、Queue baseline 和直接搜索计数显著增强了“AS-ADAPT-T 降低深尾延迟并减少无效在线试探”的证据，且与原 weighted-window P99 方向一致。与此同时，数据明确反对把贡献扩写成“全面降低延迟”或“每次负载变化都恢复”：Mean 在高并发和 dynamic 中上升，dynamic P95 基本持平，恢复由连续阈值条件控制。最稳妥的核心定位是：**在保持大致可比 throughput 的同时，以更少、更小的控制动作改善 P99/P99.9；代价是在部分场景中 Mean latency 上升。**
