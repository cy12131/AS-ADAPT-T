# Claim Validation Matrix

| 当前论文 claim | 旧证据 | 新证据 | 状态 | 建议 |
|---|---|---|---|---|
| AS-ADAPT-T improves tail latency | 稳定 PRIME 加权窗口 P99 下降约 25.93%–51.21%；dynamic 下降约 28.37% | 稳定 request P99 下降 27.1%–47.9%，P99.9 下降 31.0%–47.3%；dynamic P99/P99.9 下降 29.4%/36.2%，各 paired run 方向一致 | SUPPORTED | 将“tail latency”拆成 weighted-window P99 与 global request P99/P99.9，避免混称 |
| AS-ADAPT-T reduces control variation | 旧 A/V 对比 | 稳定 A 下降 69.6%–82.7%，V 下降 89.2%–92.9%；dynamic A/V 下降 36.0%/76.4% | SUPPORTED | 加入新三算法 A/V 表，并保留 n/CI |
| Stable mechanism reduces repeated probing | 主要由 A/V 和消融推断 | 稳定 completed trials 下降 68.2%–81.8%，rejected/rollback 下降 72.1%–83.7% | SUPPORTED | 用 candidate/reject/rollback 作为直接证据，说明 10-user recovery 会重新开启搜索 |
| Dynamic recovery preserves adaptability | PRIME dynamic 状态轨迹和 A/V | 三轮均在 100→10、10→200 后触发 recovery，20–30 s 检出，30 s 重建 baseline，约 210–290 s 重入 STABLE | SUPPORTED_WITH_QUALIFICATION | 说明 200→0 空阶段与 0→50 未满足连续触发条件，不应描述为每次变化都恢复 |
| Throughput remains approximately comparable | 旧 PRIME stable/dynamic 吞吐差异较小 | AS vs ADAPT：10 users +14.1%，100 users −0.2%，500 users +5.4%，dynamic +1.0%；100/dynamic CI 包含轻微双向差异 | SUPPORTED_WITH_QUALIFICATION | “未以尾延迟改善换取系统性吞吐损失”比“完全相同”准确 |
| Mean latency may increase in some cases | 原文已经报告部分并发下平均延迟不改善 | AS vs ADAPT：100 +5.5%、500 +22.4%、dynamic +8.8%；同时 P99/P99.9 明显下降 | SUPPORTED | 明确写成 mean–tail trade-off，不把 tail 改善外推为所有 latency 指标改善 |
| Weighted-window P99 and global request P99 have compatible conclusions | 旧论文只有 weighted-window P99 | 三个稳定并发及 dynamic 的方向完全一致；幅度相近但不相同 | NEW_EVIDENCE | 同时报告两种口径，保留旧指标用于控制过程 |
| AS compares favorably with a simple online baseline | 旧论文没有 Queue dynamic baseline | AS vs Queue 的稳定 P99 下降 34.2%–38.0%，P99.9 下降 30.6%–69.8%；dynamic P99/P99.9 下降 31.7%/39.5%，但 mean 在 100/500/dynamic 更高 | NEW_EVIDENCE | 表述为尾延迟与控制稳定性优势，不宣称所有指标全面占优 |
| AS complexity creates acceptable controller overhead | 旧论文没有 controller timing | AS 每轮 controller elapsed total 平均 194 ms，占 1500 s 的 0.0129%；低于 ADAPT-T 的 653 ms | NEW_EVIDENCE | 称 controller elapsed time，不称 CPU time；复杂状态机没有形成实质运行时负担 |
| Queue u500 extreme max values are isolated and irrelevant | 无 | Queue/u500 P99.9 均值 452.4 ms，明显高于其 P99 149.4 ms，五轮 max 1.14–2.24 s | NOT_SUPPORTED | 不使用该 claim；真实极端尾部已影响 P99.9，必须展示 |
