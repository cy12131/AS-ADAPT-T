# AS-ADAPT-T

AS-ADAPT-T 是一个面向应用服务器工作线程池的在线调节算法。它在 ADAPT-T 的运行时反馈搜索基础上加入候选配置联合验证、失败回滚、自适应缩步、局部收敛、稳定保持和负载变化恢复机制。仓库同时保留 ADAPT-T、Queue Hysteresis 和固定线程池对照实现，以及论文正式实验的脱敏数据。

本仓库用于检查算法实现和复核已有实验数据，不提供完整的 Linux/Windows 部署方案，也不声称能够在任意硬件上精确重现论文性能数值。

## Public contents

```text
src/main/       Java 服务器、工作负载、指标采集和控制器实现
src/test/       单元测试
analysis/       正式数据审计、统计分析和 HLOG 读取源码
data/           三组脱敏正式实验数据、清单、校验值和既有统计摘要
pom.xml         Maven 构建配置
CITATION.cff    待作者补全的引用元数据
```

远程调度、SSH、JMeter 执行及操作系统部署脚本不属于最终公开范围。

## Build

要求 JDK 17 或可编译 Java 17 target 的更新 JDK，以及 Maven：

```text
mvn test
mvn package
```

`target/` 不进入版本控制。当前源码在本地验证中通过 43 个单元测试；这不等于历史实验 JAR 已由同一 Git commit 字节级重建。

## Datasets

三个数据集是不同实验设计，Run 数不得相加后解释为同一实验的重复次数。

| 数据集 | 设计 | Run | 主要论文用途 |
|---|---|---:|---|
| [PRIME-54](data/prime-54/README.md) | PRIME；10/100/500 用户稳定负载，5 轮，3 策略；动态负载 3 轮，3 策略 | 54 | ADAPT-T、AS-ADAPT-T、Queue Hysteresis 的重复实验与尾延迟/控制稳定性复核 |
| [paper-73](data/paper-73/README.md) | 45 个稳定矩阵 Run、25 个动态 Run、3 个稳定保持消融 Run | 73 | PRIME、DBREAD、DBWRITE 主体实验与 AS-NO-STABLE 消融 |
| [sensitivity-21](data/sensitivity-21/README.md) | PRIME 100 用户；7 组 AS-ADAPT-T 接受阈值配置，每组 3 轮 | 21 | 参数敏感性分析 |

详细目录、字段、采集关系和 SHA-256 使用方法见 [data/README.md](data/README.md)。

## Experimental Environment

以下内容只记录论文、正式 metadata、`pom.xml` 和项目配置能够支持的事实。

### Original experiments

| 项目 | 已确认信息 |
|---|---|
| 拓扑与角色 | 两台主机通过 1 Gbit 有线网络连接；Ubuntu 主机运行 Java 应用服务器和 MySQL，Windows 主机运行 JMeter 产生负载 |
| 服务端实现 | Java 17；基于 Netty，`pom.xml` 固定 Netty 4.1.118.Final；请求处理使用可调工作线程池 |
| 客户端 | 论文记录 Apache JMeter 5.6.3；客户端操作系统精确版本未记录 |
| 数据库 | 论文记录 MySQL 8.4；`pom.xml` 使用 MySQL Connector/J 8.4.0；MySQL Server 的完整 patch、配置和建库过程无法从现有 metadata 确认 |
| JVM | `-Xms2g -Xmx2g`；Java 进程绑定逻辑 CPU 4、6，MySQL 绑定逻辑 CPU 8、10 |
| Linux 设置 | CPU governor 为 performance，Intel Turbo 和 swap 关闭；两台主机通过 NTP 同步时钟 |
| 控制窗口 | 10 s；正式单轮 1500 s，即 150 个窗口 |
| 动态负载 | 100 → 10 → 200 → 0 → 50 用户，每阶段 300 s；动态实验另约每 1 s 采集 Java 进程 CPU、线程数和上下文切换指标 |

PRIME metadata 记录的 Java patch level 并不完全相同：paper-73 为 OpenJDK 17.0.19，sensitivity-21 为 17.0.20，PRIME-54 包含 17.0.20 和 17.0.20.1。该差异是原始证据的一部分，没有被清洗。

服务器 CPU 型号、物理核心拓扑、内存容量、存储设备、Ubuntu 发行版/内核、Windows 版本、原始 Maven 与 PowerShell 版本均未记录或无法从现有资料可靠确认。

### Workloads and policies

- PRIME 对 `999990` 至 `1000010` 范围执行 CPU 密集型素数判断。
- DBREAD 从约 100 万条新闻记录中按主键读取约 3 KiB 正文。
- DBWRITE 每请求写入约 3 KiB 事件记录；论文说明各次运行前后清空事件表。
- 主体对照包括 FIXED2、FIXED10、FIXED100、ADAPT-T 和 AS-ADAPT-T；PRIME-54 另使用 Queue Hysteresis；消融使用 AS-NO-STABLE。
- ADAPT-T 初始线程数为 1、固定步长为 10；AS-ADAPT-T 默认步长范围为 1–10，平均延迟改善要求 5%，最大吞吐量下降 5%，最大 P99 增长 10%，稳定参考和变化检测均使用 3 个窗口。

### Current local preparation environment

当前 Windows 本地环境仅用于源码构建、测试、数据脱敏整理、checksum 和静态审计：Windows 11、PowerShell 7.6.5、Maven 3.8.2、Oracle JDK 21.0.8（编译到 Java 17 target）和 Python 3.11.5。它不是正式性能实验环境，本次整理没有运行 JMeter 或性能实验。

## Data Provenance

`data/` 来自本机只读原始证据区 `results/` 的公开副本。CSV、HLOG 和既有二进制统计图按字节复制；公开 metadata 只替换个人绝对路径或私网地址，不改变实验参数、时间戳或测量值。原始—公开映射保存在不公开的本地审计区。

每轮服务器 CSV 以 10 秒窗口记录吞吐量、请求数、平均/分位延迟、队列和线程池状态及控制动作。PRIME-54 还保存每个非空窗口的 HdrHistogram HLOG，可重新聚合延迟分布；HLOG 不是逐请求日志。统计摘要由窗口 CSV、HLOG 和 metadata 派生，不增加 Run 数。paper-73 和 sensitivity-21 没有 HLOG。

每个数据集包含：

- `manifests/experiment-matrix.csv`：实验设计、Run ID 和文件映射。
- `manifests/public-files.csv`：公开文件角色、大小、SHA-256 和复制/脱敏方式。
- `manifests/checksums-sha256.txt`：该数据集公开文件的 SHA-256（不含清单自身）。

## Analysis source

公开的分析源码保留原统计口径：

- `analysis/audit_ccpe_54.py` 与 `analysis/HlogAudit.java`：PRIME-54 完整性、metadata、CSV 和 HLOG 审计。
- `analysis/analyze_ccpe_54.py`：读取上述审计结果和 PRIME-54 窗口数据，生成重复实验统计与图表。
- `analysis/analyze_formal_r1.py`：读取 paper-73 中 45 个稳定矩阵 server CSV。
- `analysis/analyze_prime_repeats.py`：读取 PRIME 稳定负载五轮 ADAPT-T/AS-ADAPT-T server CSV。

这些历史脚本按原始扁平 `results/` 文件名查找输入，而公开数据按数据集/Run 分层保存。公开的 manifests 给出文件映射；脚本尚不是针对 `data/` 的一键式入口。sensitivity-21 没有独立的专用重算脚本。重新分析时应写入新的输出目录，不覆盖 `data/` 中的既有结果。

## Reproducibility limits

- PRIME-54 缺少客户端 JTL、客户端日志和逐轮客户端 metadata。
- 稳定 PRIME、DBREAD、DBWRITE 的部分 JMX 没有保存，不能编造；现有动态 JMX 也不属于最终公开范围。
- paper-73 和 sensitivity-21 没有请求 HLOG；HLOG 也不能恢复逐请求 timestamp、stage、success 或客户端错误。
- 数据库 schema/DDL、种子数据和部分系统/硬件参数不完整。
- 公开区排除了 server/process 原始日志，因此不能重做全部日志级审计。
- 历史 JAR hash 与 metadata 可以关联，但缺少原始 Git commit/tag 和干净 checkout 的字节级重建证明。

## License and citation

本仓库采用分离许可：作者自有的 Java/Python 源码和作者创建的软件文档使用根目录 [Apache License 2.0](LICENSE)；`data/` 中作者自有的实验数据和数据说明使用 [Creative Commons Attribution 4.0 International](data/LICENSE)。

上述许可不覆盖 Maven/Python 第三方依赖、未公开论文 PDF、投稿材料、原始 `results/` 或其他不属于作者的材料。第三方组件继续适用各自许可证；仓库不分发依赖 JAR 或历史 shaded JAR。若未来发布二进制，必须另行汇总并保留依赖的 LICENSE/NOTICE。

本项目统一公开署名为 **AS-ADAPT-T Project Contributors**，不在仓库中公开贡献者真实姓名。`CITATION.cff` 不包含 DOI、仓库 URL、版本或发布日期；这些字段只能在实际确定后补充。
