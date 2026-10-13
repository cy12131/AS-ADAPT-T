# 实现、论文与补充计划差异

本文件只记录差异，不修改代码、论文或原始数据。

## 1. “request-level”保存格式不是逐请求明细

- 实现行为：每个非空 10 s 窗口保存一个可合并 HdrHistogram，记录成功请求延迟分布；数值单位为 ns，显示换算为 ms，精度为 3 位有效数字。全轮 summary 由这些窗口直方图合并后计算。
- 补充计划：建议至少保存 request timestamp、latency、success/failure、workload stage、pool size、controller state。
- 差异：当前格式能可靠重算全轮成功请求数、Mean、P50、P95、P99、P99.9、min、max，但不能恢复每条请求的时间戳、结果、阶段、线程池或状态，也不能审查重复时间戳或单条样本越界。
- 涉及：54/54。
- 有效性影响：不影响基于完整成功请求分布的全轮尾延迟比较；限制逐请求取证、精确阶段边界拆分和客户端错误诊断。不能把 HLOG 描述为“逐请求原始明细”。

## 2. HLOG 不写空窗口

- 实现行为：`window_count` 每个窗口都会增加，但空窗口没有 mergeable histogram，因此不会写 HLOG 记录。
- 论文行为：0-user 阶段无有效延迟观测，不应产生 P99。
- 差异：不是算法差异；文件表现为动态运行各有 121 个 HLOG 记录，tag 从 `window-91` 跳到 `window-121`，同时 summary 仍正确写 150 个窗口。
- 涉及：9/9 dynamic；空窗口均为 92–120。
- 有效性影响：符合“不把 0-user 当 latency=0”的要求；审计工具必须按 tag 而非物理行号合并。

## 3. 动态阶段不是逐请求字段

- 实现行为：runner 通过窗口 1–30、31–60、61–90、91–120、121–150 映射到 100/10/200/0/50 users。window 91 含 14,231–16,792 个从 200→0 边界排空的真实请求，92–120 才完全为零。
- 补充计划：建议逐请求保存 workload stage。
- 差异：阶段直方图只能按完整窗口合并，无法把边界窗口内部请求再按请求级阶段拆分。
- 涉及：9/9 dynamic。
- 有效性影响：全轮统计有效；非零四阶段统计可按既定 30-window 口径重算。0-user 阶段的 latency 必须记为 `NA`，不能将 window 91 的排空请求解释为 0-user 延迟。

## 4. Controller time 是 elapsed wall-clock，不是线程 CPU time

- 实现行为：`System.nanoTime()` 包围 `controller.onWindow(...)`，记录调用经过时间。三种算法口径相同。累计 total 是未量化的差值；p95/max 来自 3 位有效数字 HdrHistogram，单次上限 10 s。
- 补充计划/未来论文措辞：写作中可能称 controller CPU time。
- 差异：当前字段测量 wall-clock execution time，不是 OS/JVM 线程 CPU 消耗。
- 涉及：54/54。
- 有效性影响：三策略可按统一的调用耗时口径比较，但不得写成 CPU time。实际最大单次 62.240 ms，远低于 10 s 截断上限。

## 5. Median 与 P99 timing 未单独落盘，但可由累计 total 恢复

- 实现行为：每个窗口保存累计 `controller_decision_total_ns`，summary 直接保存 mean/p95/max。
- 审计要求：mean、median、P95、P99、max、total。
- 差异：median/P99 没有原生 summary 字段。
- 处理：审计通过相邻窗口累计 total 的差分恢复全部 150 个决策耗时，并生成 `controller_timing_summary.csv`；没有改动原始数据。
- 有效性影响：所需指标可重算，无需重跑。

## 6. 算法可行域上限存在理论差异

- 实现行为：底层 worker `maxSize=101`；AS-ADAPT-T 和 Queue 显式 `Nmax=100`；ADAPT-T 使用底层上限。
- 论文描述：明确下界 Nmin=1，但当前正文未给这批补充比较统一的 Nmax=100 声明。
- 差异：ADAPT-T 理论上可到 101。
- 涉及：18 个 ADAPT-T 运行的潜在行为。
- 有效性影响：本批 ADAPT-T 实际最大 71，没有触发 100/101 差异，所以不改变已有结果；后续实验应统一或明确报告。

## 7. Queue baseline 尚不在所附论文正文中

- 实现行为：已新增并完成 18 个 Queue hysteresis 运行。
- 所附论文：实验对照仍列 FIXED2/FIXED10/FIXED100/ADAPT-T/AS-ADAPT-T。
- 差异：这是预期的补充实验，尚未反映到论文，不是实现错误。
- 有效性影响：Queue 数据可用于后续补充比较；当前任务不修改论文。
