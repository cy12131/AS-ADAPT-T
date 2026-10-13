# CCPE 54 组实验配置一致性报告

## 结论

服务器侧可核验配置总体一致，唯一实质性差异是 Java 17 的补丁版本分成两批：17 个运行使用 `17.0.20`，37 个运行使用 `17.0.20.1`。54 个运行的服务器 JAR SHA-256 完全相同，因此没有发现实验期间更换应用实现的证据。Java 补丁差异应作为批次效应协变量保留并在统计前做敏感性检查，但目前没有失败、崩溃、窗口缺失或计数错位证据支持仅因此重跑。

客户端 JMeter JTL、日志和每轮客户端 metadata 不在当前服务器结果目录中。因此，JMeter 版本、命令行参数、客户端错误、超时和连接错误不能从本目录逐轮独立复核。运行脚本固定使用同一个稳定负载 JMX、同一个动态负载 JMX 和统一的 1500 s/150 窗口计划，这只能证明设计配置，不能替代缺失的客户端运行证据。

## 完全一致的服务器配置

| 配置 | 值 | 覆盖 |
|---|---:|---:|
| 应用 | `PRIME` | 54/54 |
| 初始线程数 | 1 | 54/54 |
| 控制窗口 | 10 s | 54/54；每轮实得 150 窗口 |
| JVM 堆 | `-Xms2g -Xmx2g` | 54/54 |
| Java CPU 集 | `4,6` | 54/54 |
| MySQL CPU 集 | `8 10` | 54/54 |
| 控制目标 | `MEAN` | 54/54 |
| JAR SHA-256 | `1cf520d8a71b72819510aeee0a69b68455ca68b763182073c2059d57b8adb55f` | 54/54 |
| PRIME 范围 | 999990–1000010 | 同一应用配置 |
| 请求延迟 HdrHistogram | 1 ns–300 s，3 位有效数字 | 同一实现 |
| 动态进程监控 CPU | 12 | 9/9 dynamic |
| 动态进程采样间隔 | 1 s | 9/9 dynamic |

运行脚本还要求 Linux 为 `performance` 模式、关闭 Turbo 和 swap、MySQL 运行且绑定 CPU。每轮 metadata 没有保存这些检查项的实时快照，因此这里只能确认脚本约束以及用户提供的运行前环境检查，不能声称逐轮均有独立配置证据。

## ADAPT-T 固定参数

| 参数 | 值 |
|---|---:|
| `adapt.stepSize` | 10 |
| `adapt.latencyThresholdPercent` | 5.0% |
| increase/decrease lock | 6/6 stages |
| objective | Mean latency |
| 初始线程数 | 1 |

这些值由同一 JAR/配置和启动脚本提供；metadata 仅逐轮显式保存 objective 和初始线程数，未逐轮重复保存 step/threshold/lock。

## AS-ADAPT-T 固定参数

以下值在全部 18 个 AS-ADAPT-T metadata 中完全一致：

| 参数 | 值 |
|---|---:|
| Nmin / Nmax | 1 / 100 |
| Smin / Smax | 1 / 10 |
| Mean improvement | 5% |
| 最大 Throughput degradation | 5% |
| 最大 P99 degradation | 10% |
| reference windows | 3 |
| change-detection windows | 3 |
| Mean / P99 / Throughput change | 10% / 10% / 10% |
| Queue growth | 10%，且至少 +1 |
| Queue-assisted Mean / Throughput | 5% / 5% |
| stable holding | true |

## Queue baseline 固定参数与公平性

以下值在全部 18 个 Queue 运行中完全一致，未按并发数或结果调参：

| 参数 | 值 |
|---|---:|
| Nmin / Nmax | 1 / 100 |
| initial pool | 1 |
| step `k` | 5 |
| `q_low` / `q_high` | 0 / 5 |
| continuous-window requirement | 2 |
| cooldown | 无独立 cooldown；连续窗口滞回即抑制机制 |
| control window | 10 s |

Queue 与另两种算法使用同一 JAR、CPU 绑定、JVM 堆、PRIME 实现、运行长度和窗口长度。运行顺序在不同轮次轮换，未发现为了改善 Queue 结果而改变参数的证据。

底层 worker pool 的实现上限是 101，而 AS 和 Queue 的算法上限显式为 100；ADAPT-T 没有单独的 100 上限。本批 ADAPT-T 实际最大线程数仅为 71，所以该理论差异未在本数据中触发，不影响这 54 轮的实际轨迹，但后续公平性描述应明确算法可行域。

## Java 补丁版本差异

`17.0.20` 的 17 个运行为：

- dynamic R1 的 ADAPT-T、AS-ADAPT-T、Queue；
- stable R1 的全部 9 个运行；
- stable R2/u100 的全部 3 个运行；
- stable R2/u500 的 ADAPT-T 与 AS-ADAPT-T。

其余 37 个运行使用 `17.0.20.1`。差异不是按算法选择的：多数同批次配置仍三种策略成组运行，但 stable R2/u500 的 Queue 和 stable R2/u10 三种策略位于新补丁批次。因此严格的逐轮配对存在小范围环境不完全一致。建议正式统计时报告该事实，并做“排除跨补丁不完整配对后的结论方向是否改变”的敏感性检查；不要删除原始轮次。

## 影响判断

- 应用二进制、算法参数和服务器资源约束没有发现系统性漂移。
- Java 补丁差异构成 `WARNING`，不是当前证据下的 failed experiment。
- 客户端逐轮配置证据缺失构成全体运行的审计限制。
- 现有数据可以进入服务器侧指标统计；任何关于客户端 HTTP/timeout/connection-error 为零的论断，需要补回客户端归档后才能成立。
