# CCPE 54 Runs Data Audit

审计对象是 `results/` 中以 `ccpe-tail-54-` 为前缀的正式运行。未修改任何原始文件；所有 CSV/Markdown 均为派生审计产物。

# 1. Executive Summary

- 预期运行：54；找到且唯一匹配：54。
- 数据有效：54/54。
- `PASS`：0/54；`WARNING`：54/54；`FAIL`：0/54。
- 需要重跑：无。
- 最终结论：**A. 54 组数据整体有效，可以进入正式统计分析。**

这里的 54 个 `WARNING` 不是运行失败：每轮都缺少客户端 JMeter JTL/log/metadata，且“request-level”采用窗口标记的可合并 HdrHistogram，而不是逐请求明细。因此，服务器侧全轮延迟、吞吐量、控制器和线程池指标可用，但逐请求 timestamp/stage、HTTP timeout/connection error 不能直接验证。另有两个非破坏性警告：Java patch level 分为 17.0.20 与 17.0.20.1；两个运行在最后一个窗口应用了候选，来不及在第 151 窗口评价。

独立解码全部 HLOG 后，共交叉验证 **561,070,013** 个成功请求；server failure、rejected task 均为 0。HLOG 重算的全轮 Mean/P50/P95/P99/P99.9/min/max 与 54 个 latency summary 全部一致。

# 2. Experiment Inventory

完整逐运行清单见 `experiment_inventory.csv`，逐运行检查见 `per_run_integrity_checks.csv`。54 组构成如下；表中每个单元格均代表列出的独立 Run ID 数量且状态均为 `WARNING`：

| 负载 | 并发/模式 | ADAPT-T | AS-ADAPT-T | Queue | 合计 |
|---|---|---:|---:|---:|---:|
| PRIME static | 10 users，R1–R5 | 5 | 5 | 5 | 15 |
| PRIME static | 100 users，R1–R5 | 5 | 5 | 5 | 15 |
| PRIME static | 500 users，R1–R5 | 5 | 5 | 5 | 15 |
| PRIME dynamic | 100→10→200→0→50，R1–R3 | 3 | 3 | 3 | 9 |
| **合计** |  | **18** | **18** | **18** | **54** |

每轮存在 7 个必要服务器文件：window CSV、server log、metadata、HLOG、latency summary、controller summary、stopped PID；9 个 dynamic 运行另有 process CSV/log/metadata/stopped PID。所有文件位于一个扁平的 `results/` 目录，以 Run ID 隔离，而不是 54 个物理子目录。因此，“54 个独立运行”成立，“54 个独立运行目录”字面上不成立。

# 3. Missing / Duplicate Runs

- 缺失 Run：无。
- 重复 Run：无。
- 多出的 `ccpe-tail-54-*-server.csv`：无。
- 命名不一致：无；CSV 内部 `application=PRIME` 和 policy 与 Run ID 全部匹配。
- 测试混入：`results/` 同目录另有 724 个非本批文件，包括 preflight/旧实验产物；没有任何一个与 54 个正式 Run ID 冲突。正式统计必须使用 inventory 白名单，不应遍历整个目录后按文件类型盲目汇总。

# 4. Configuration Consistency

详细结果见 `configuration_consistency_report.md`。

54 轮具有同一 JAR hash、JVM 堆、CPU 绑定、初始线程数、控制窗口和 PRIME 配置。AS 的 18 轮参数完全相同；Queue 的 18 轮均使用 Nmin/Nmax=1/100、step=5、q_low/q_high=0/5、连续 2 窗口，未按负载调参。

唯一明确差异是 Java patch：17 轮为 17.0.20，37 轮为 17.0.20.1。它没有按算法系统性偏置，但少量 R2 配对跨 patch，应在统计时做敏感性检查。JMeter 5.6.3 和共同 JMX 可从脚本设计确认，但因客户端归档缺失，不能逐轮取证确认。

# 5. Request-level Data Integrity

可验证项：

- 54 个 HLOG 均可完整解码；tag 与所有非空 window index 一致。
- 动态 HLOG 各有 121 条非空窗口记录，静态 HLOG 各有 150 条；动态缺少的 29 条正是 window 92–120 的空窗口，不是截断。
- HLOG request count、window CSV success 总数、latency summary success 总数逐轮完全相等。
- 全轮延迟最小值范围 0.006988–0.182272 ms，没有 0、负值、NaN 或 Inf；真实最大值范围 26.968063–2243.952639 ms，未删除或裁剪。
- 新 P95/P99/P99.9 是合并全轮成功请求直方图所得，不是窗口 P99 的平均。

不可验证项：逐请求 timestamp 的递增/重复/跳变、每请求 success 标志、workload stage、pool/state，以及客户端 HTTP error、timeout 和 connection error。这些字段没有落盘。HdrHistogram 以 3 位有效数字保存分布，因此是可合并的请求分布记录，而不是无损逐请求样本列表。

# 6. Dynamic Workload Integrity

9/9 dynamic 均有 150 个窗口和约 1500 s server measurement；process monitor 均有约 1512 s 覆盖且数值合法。runner 明确按每 30 个窗口映射 100→10→200→0→50 users。

所有动态运行的零请求窗口完全一致：**92–120，共 29 个**。window 91 是 200→0 边界的排空窗口，仍有 14,231–16,792 个真实请求；这些请求保留在全轮统计中。92–120 的成功请求和全部延迟字段均为 0，HLOG 没有为这些空窗口写占位延迟。因此不存在把 0-user 当 latency=0 样本参与分位数的问题。

`dynamic_stage_summary.csv` 按 30-window 设计口径合并各阶段 HLOG。四个非零阶段给出 count/throughput/Mean/P50/P95/P99/P99.9；0-user 阶段的 latency 明确为 `NA`。由于缺少逐请求 stage，不能把 window 91 内的排空请求进一步拆分。

AS 动态恢复时间线见 `as_dynamic_recovery_timeline.csv`。三轮均发生 2 次 recovery：第一次在 window 33 检出，第二次在 window 62 或 63 检出；随后均收满 3 个 recovery baseline 窗口再重启 SEARCH，并最终重新进入 STABLE。0-user 空窗口会清除连续变化计数，50-user 阶段未连续满足恢复阈值；这与实现逻辑一致。

# 7. Controller Log Integrity

从每行 `configured_pool_size -> pool_size_after_control` 独立重算 A 和 V，并与 AS/Queue 内部累计 telemetry 比较，全部一致。汇总如下：

| 算法 | A | V | candidates | accepted | rejected | rollback |
|---|---:|---:|---:|---:|---:|---:|
| ADAPT-T | 1324 | 13240 | 713 | 101 | 611 | 611 |
| AS-ADAPT-T | 389 | 1465 | 212 | 34 | 177 | 177 |
| Queue | 558 | 2779 | 558 | 558 | 0 | 0 |

全部 788 个 rejected trial 都能在同一决策行看到真实 rollback。通常 `candidate=accepted+rejected`；两个例外恰好是运行末尾的右删失候选：

- `ccpe-tail-54-dynamic-r3-adaptt`：window 150 `INCREASE_APPLIED`；
- `ccpe-tail-54-stable-r4-u10-as-adaptt`：window 150 `TRIAL_APPLIED`。

它们需要后续完整窗口才能被接受或拒绝，但正式 measurement 正好结束。这是定义上可解释的边界事件，不是计数错误，也不应伪造结果补齐。

# 8. AS-ADAPT-T State-machine Verification

本审计不是抽样，而是逐行重建全部 18 个 AS 运行，共 2700 个决策。未发现非法跳转、TRIAL 未验证即 SEARCH、rejected 未回滚、reference 未收满 3 窗口、recovery 未建立 baseline 即 SEARCH。

总体状态/事件证据包括：212 次 TRIAL、28 次进入 STABLE、12 次 LOAD_CHANGE_DETECTED。3 个动态 AS 运行中，每轮首次进入 STABLE 约在 180.03 s；每轮均进入 STABLE 3 次、recovery 2 次。empty window 保持既有状态并清零变化检测，符合代码与论文描述。

# 9. Queue-based Baseline Fairness

Queue 与 ADAPT-T/AS-ADAPT-T 使用同一服务器二进制、PRIME、CPU 绑定、JVM 堆、1500 s/150 windows、初始线程数 1 和运行矩阵。Queue 参数在所有并发与动态运行中完全固定，运行顺序按轮次轮换。没有配置证据表明为了让 Queue 更好而针对结果调参。

限制有两点：客户端 JMeter 逐轮证据缺失；底层 ADAPT-T 理论上可到 101，而 AS/Queue 上限为 100，但 ADAPT-T 实际最大仅 71，因此本批未触发该差异。

# 10. Controller Timing Integrity

三种策略都在同一个 `WindowCoordinator` 中，用 `System.nanoTime()` 包围 `controller.onWindow`，单位统一为 ns，测量口径是 wall-clock elapsed time，不是 CPU time。累计 total 单调递增且每个差分均为正，没有整数溢出或单位错置。

通过相邻累计 total 的差分恢复每轮 150 个决策耗时后，生成 `controller_timing_summary.csv`：

- run mean：0.187–6.855 ms；
- run median：0.007–1.522 ms；
- run P95：1.544–32.831 ms；
- run P99：2.596–43.193 ms；
- 单次最大：62.240 ms；
- 单轮 total：27.998–1028.237 ms。

原生 summary 的 p95/max 使用 3 位有效数字、10 s 上限的 HdrHistogram；审计重算值来自未量化 cumulative total 差分。实际最大远低于 10 s 上限，因此没有截断。

# 11. Suspicious Runs

以下现象保留并报告，不构成删除或重跑依据：

1. 低并发吞吐批次波动。相对各自五轮中位数：
   - `ccpe-tail-54-stable-r5-u10-adaptt`：−5.61%；
   - `ccpe-tail-54-stable-r5-u10-as-adaptt`：−6.21%；
   - `ccpe-tail-54-stable-r5-u10-queue`：−5.83%；
   - `ccpe-tail-54-stable-r1-u10-as-adaptt`：−8.43%。
   这些轮次仍有 150 窗口、0 failure、完整 HLOG、正常生命周期和无 server error，故分类为真实运行/批次波动候选，不是 logging artifact。
2. Queue/u500 的五轮最大延迟均较高：1139.802–2243.953 ms；P99 为 146.407–155.320 ms，P99.9 为 382.206–540.541 ms。现象跨五轮复现，HLOG 与 summary 一致，分类为 real performance tail，必须保留。
3. 两个 final-window pending candidate 如第 7 节所述，属于观察终点右删失，不应算 accepted/rejected。
4. Java patch 批次差异如第 4 节所述，属于 environmental consistency warning。

没有任何异常被排除，未进行平滑、winsorize 或 outlier 删除。

# 12. Runs Recommended for Rerun

**无。** 当前没有运行中断、缺窗口、计数不一致、日志截断、JVM crash、服务器异常、失败请求或状态机错误证据。

如果论文必须声称“逐请求时间戳与阶段已审计”或“客户端 HTTP/timeout/connection error 为零”，应先寻找原 Windows 客户端归档。只有确认这些文件从未保存、且这些声明是投稿硬性要求时，才需要因证据缺口重新采集；这不是现有性能结果失败。

# 13. Statistical Readiness

| 分析 | 就绪性 | 说明 |
|---|---|---|
| Throughput / Mean | Ready | window CSV 与 HLOG/count 交叉验证 |
| 全轮 P95/P99/P99.9 | Ready | 直接合并全轮 HLOG，不是平均窗口分位数 |
| A / V | Ready | 从 pool trajectory 独立重算 |
| candidate/accept/reject/rollback | Ready | 两个 final pending candidate 须按右删失注明 |
| Queue baseline comparison | Ready with caveat | 服务器配置公平；客户端逐轮归档缺失，Java patch 做敏感性检查 |
| Dynamic recovery | Ready | window/state 时间线可用；逐请求阶段边界不可拆分 |
| Controller timing | Ready | mean/median/P95/P99/max/total 均可重算；称 elapsed time，不称 CPU time |
| 客户端错误率细分 | Not ready | JTL/log 缺失；只能报告 server failure=0 |
| 逐请求 timestamp/stage 分析 | Not ready | 原始格式没有这些字段 |

`run_level_summary.csv` 已按每轮输出请求、全轮尾延迟、A/V、搜索计数、比例、稳定/恢复和 controller timing；不适用指标使用 `NA`，没有用 0 冒充不适用。

# 14. Final Verdict

**A. 54 组数据整体有效，可以进入正式统计分析。**

进入统计时必须保留全部 54 轮，并同时披露三项限制：Java patch 批次差异、客户端日志缺失、HLOG 不是逐请求行。Queue/u500 高尾延迟和低并发批次波动是应保留的观察结果，不是删除依据。当前没有证据支持重跑任何指定 Run。
