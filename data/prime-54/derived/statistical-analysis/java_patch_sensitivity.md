# Java Patch Sensitivity

## 分布与混杂判断

17.0.20 共 17 轮：ADAPT-T 6、AS-ADAPT-T 6、Queue 5；17.0.20.1 共 37 轮：ADAPT-T 12、AS-ADAPT-T 12、Queue 13。主要比较 AS vs ADAPT 在每个 concurrency/round 内 Java patch 完全匹配：

- 10 users：R1 为旧 patch，R2–R5 为新 patch；
- 100 users：R1–R2 旧，R3–R5 新；
- 500 users：R1–R2 旧，R3–R5 新；
- dynamic：R1 旧，R2–R3 新。

因此 patch 与运行批次相关，但不与主要比较的算法身份混杂。AS vs Queue 只有 stable 500/R2 跨 patch，其他 round 内匹配。

## 同 patch 方向检查

AS vs ADAPT 的 request-level P99/P99.9 在两个 patch 子集中方向一致：

| 场景 | 17.0.20 P99 / P99.9 | 17.0.20.1 P99 / P99.9 |
|---|---:|---:|
| 10 users | −43.1% / −40.1%（n=1） | −49.1% / −48.9%（n=4） |
| 100 users | −26.0% / −30.4%（n=2） | −27.8% / −31.5%（n=3） |
| 500 users | −29.3% / −34.8%（n=2） | −32.1% / −37.0%（n=3） |
| dynamic | −26.9% / −31.9%（n=1） | −30.6% / −38.2%（n=2） |

A/V 的方向在两个 patch 子集中也一致。子组 n=1–4，不能可靠估计 patch interaction 或子组 CI；这些数值只用于检查结论方向，不用于声称 patch 无效应。

R5 的三算法共同吞吐下降发生在新 patch 内，但同为新 patch 的 R2–R4 没有统一下降，说明“patch 更新”不能单独解释 R5；更符合共享运行批次/环境波动。

## 结论

**A. 未发现改变主要结果方向的证据。**

这不等于证明两个 Java patch 性能完全相同。正式写作应披露 patch/batch 变化，并强调主要 AS–ADAPT 比较采用同 round、同 patch 配对。不要删除旧 patch 数据。
