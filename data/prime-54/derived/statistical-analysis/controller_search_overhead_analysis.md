# Controller Search Overhead Analysis

## 口径

只把 ADAPT-T/AS-ADAPT-T 的实际 trial 视为 candidate。Queue hysteresis 的 resize 不具有候选验证、接受或回滚语义，因此相关字段为 `NA`。两个 window-150 candidate 记为 `unevaluated`；A/V 保留实际 resize，但 accepted/rejected/rollback 不补记。

所有相对下降均按同 round 配对 log-ratio 计算；stable n=5、df=4，dynamic n=3、df=2。原始均值、中位数和 SD 在 `controller_search_overhead.csv`，配对下降与 CI 在 `controller_search_pairwise_effects.csv`。

## 稳定负载

| users | 指标 | ADAPT-T mean | AS mean | AS 相对下降（95% CI） |
|---:|---|---:|---:|---:|
| 10 | completed trials | 40.4 | 14.6 | 68.2% [36.1%, 84.2%] |
| 10 | rejected / rollback | 35.4 | 12.0 | 72.1% [33.7%, 88.3%] |
| 10 | A | 75.8 | 26.8 | 69.6% [35.1%, 85.7%] |
| 10 | V | 758.0 | 95.4 | 89.2% [76.6%, 95.0%] |
| 100 | completed trials | 39.4 | 7.2 | 81.8% [80.5%, 82.9%] |
| 100 | rejected / rollback | 36.8 | 6.0 | 83.7% [83.4%, 83.9%] |
| 100 | A | 76.2 | 13.2 | 82.7% [82.1%, 83.3%] |
| 100 | V | 762.0 | 54.2 | 92.9% [92.9%, 92.9%] |
| 500 | completed trials | 42.6 | 8.0 | 81.3% [79.1%, 83.2%] |
| 500 | rejected / rollback | 33.4 | 6.2 | 81.5% [80.0%, 82.8%] |
| 500 | A | 76.0 | 14.2 | 81.4% [79.5%, 83.0%] |
| 500 | V | 760.0 | 56.4 | 92.6% [92.4%, 92.8%] |

100/500 users 的 AS 轨迹在首次局部收敛后基本停止生成候选：每轮约 7–9 个候选、1 次进入 STABLE、0 recovery。10 users 的波动更大；R1/R3/R4 出现变化检测与恢复，使 candidate 为 27/20/10，但仍显著低于 ADAPT-T 的约 40。稳定保持并非永远冻结，而是在检测到持续变化时恢复搜索。

没有发现“A/V 很低但 candidate 仍很高”的反常运行。AS 的 candidate、rejected、rollback、A 和 V 同方向下降；V 降幅大于 candidate 降幅，是因为自适应步长和局部邻域试探还降低了单次变化幅度。

## 动态负载

AS 相对 ADAPT-T：candidate 下降 38.8% [16.5%, 55.2%]，completed trial 下降 38.2% [18.4%, 53.2%]，rejected/rollback 下降 32.6% [23.0%, 41.0%]，A 下降 36.0% [20.7%, 48.4%]，V 下降 76.4% [74.6%, 78.0%]。

AS 在动态负载中每轮仍发生两次 recovery，因此 candidate 不会降至稳定 100/500 users 的水平。这支持“收敛后停止重复试探，同时保留恢复能力”，而不是“AS 始终不再搜索”。

## Endpoint candidates

- `ccpe-tail-54-stable-r4-u10-as-adaptt`：1 个 unevaluated candidate；
- `ccpe-tail-54-dynamic-r3-adaptt`：1 个 unevaluated candidate。

二者均计入 candidate/A/V，不计入 completed/accepted/rejected/rollback。所有 rate 使用 completed trials 作分母。
