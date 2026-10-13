# paper-73 public dataset

## Scope

73 runs: 45 stable formal, 25 dynamic formal and 3 PRIME `AS_NO_STABLE` ablation runs. The 70 formal runs cover PRIME, DBREAD and DBWRITE with five policies according to the matrix. This dataset is distinct from PRIME-54.

## Artifact counts

- Original raw artifacts: 404.
- Public raw artifacts: 202.
- Stable: 45 runs × metadata and server CSV.
- Dynamic/ablation: 28 runs × metadata, server CSV, process CSV and process metadata.
- Existing derived summaries: 4.

Logs and stopped PID markers are excluded. The source has no request HLOG, request-level rows or complete client archive.

Measurement CSV files are byte copies. Metadata replaces known personal paths/remote addresses only. New public checksums apply to the sanitized tree and are not interchangeable with private source checksums. `experiment-matrix.csv` has exactly 73 rows and preserves STABLE, DYNAMIC and ABLATION labels.

