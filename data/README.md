# Public experimental data

该目录包含三组彼此独立的脱敏正式实验数据。原始证据保存在不公开的 `results/`；本目录中的实验数值、时间戳、CSV 和 HLOG 未被重新计算或改写。

## Dataset boundaries

| Dataset | Design | Runs | Public raw artifacts | HLOG |
|---|---|---:|---:|---:|
| `prime-54` | PRIME：10/100/500 用户 × 5 轮 × 3 策略；动态模式 × 3 轮 × 3 策略 | 54 | 288 | 54 |
| `paper-73` | 45 稳定矩阵 + 25 动态 + 3 AS-NO-STABLE 消融 | 73 | 202 | 0 |
| `sensitivity-21` | PRIME 100 用户；7 个 AS-ADAPT-T 参数配置 × 3 轮 | 21 | 42 | 0 |

三组 Run 不得合并为同一种重复实验。paper-73 的 70 个主体 Run 和 3 个消融 Run 也不得误写为 73 轮同质重复。

## Collection design

正式单轮持续 1500 s。服务器每 10 s 输出一个窗口，共 150 个窗口。动态负载按 100 → 10 → 200 → 0 → 50 用户变化，每阶段 300 s；0 用户阶段没有有效请求延迟。动态 Run 还保存约 1 s 采样的进程数据及其 10 s 汇总（公开范围只保留必要 CSV/metadata）。

策略范围：

- PRIME-54：ADAPT-T、AS-ADAPT-T、Queue Hysteresis。
- paper-73 主体：FIXED2、FIXED10、FIXED100、ADAPT-T、AS-ADAPT-T；另有 3 个 AS-NO-STABLE 消融 Run。
- sensitivity-21：AS-ADAPT-T 默认参数及六个单因素阈值变体，每组 3 轮。

## File relationships

- `*-server.csv`：10 s 服务端窗口。字段包括窗口起止时间、吞吐量、成功/失败请求、平均和分位延迟、队列长度、线程池大小、控制阶段/动作，以及累计调整指标。
- `*.hlog`：HdrHistogram 窗口直方图，只存在于 PRIME-54。每条记录对应一个非空窗口，可重新计算分位数，但不是逐请求明细。
- `*-metadata.txt`：Run ID、应用、策略、Java 版本、CPU 亲和性、JVM 堆和历史 JAR hash 等来源信息；个人路径已替换为占位符。
- latency/controller summary：从窗口 CSV/HLOG 汇总的运行级证据，不是新的独立 Run。
- `derived/`：既有审计、统计表和图表；用于复核论文分析，不应被计入原始 Run 数。

运行级吞吐量使用成功请求总数除以有效窗口时长；运行级平均延迟按成功请求数加权。论文中的“加权窗口 P99”是按窗口成功请求数加权的窗口 P99 均值，不是把全部请求合并后的全局逐请求 P99。

## Manifests and integrity

每个数据集的 `manifests/` 包含：

- `experiment-matrix.csv`：Run、场景、轮次、策略、原始/公开工件数及缺失项。
- `public-files.csv`：公开文件路径、角色、大小、SHA-256 和转换方式。
- `checksums-sha256.txt`：数据集下全部公开文件的 SHA-256，校验文件自身除外。

CSV、HLOG 和二进制图表为原始文件的字节副本。必要 metadata 只进行了路径/地址占位符替换。checksum mismatch 时应停止复核，不得自动重写数据。

## Known evidence gaps

- PRIME-54 没有客户端 JTL、客户端日志或逐轮客户端 metadata。
- paper-73 和 sensitivity-21 没有 HLOG。
- 部分 JMX 未保存；不能从现有数据恢复或编造。
- HLOG 不能恢复单请求时间戳、成功状态或客户端错误。
- 历史 JAR 可通过 metadata hash 关联，但缺少原始 Git commit/tag 的完整构建溯源。

## License

本目录中作者自有的实验数据和作者创建的数据说明采用 [Creative Commons Attribution 4.0 International](LICENSE)。该许可不适用于软件源码、第三方材料、未公开论文 PDF、投稿文件或不在公开仓库中的原始材料；软件许可见仓库根目录 `LICENSE`。

推荐的数据公开署名为 **AS-ADAPT-T Project Contributors**。使用数据时应引用 AS-ADAPT-T、注明该署名，并清楚标示所作修改。
