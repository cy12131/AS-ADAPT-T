# PRIME-54 public dataset

## Scope

54 PRIME runs: 45 stable (10/100/500 users × R1-R5 × 3 policies) and 9 dynamic (100→10→200→0→50 users × R1-R3 × 3 policies). Policies are ADAPT-T, AS-ADAPT-T and Queue Hysteresis.

## Artifact counts

- Original raw artifacts: 414.
- Public raw artifacts: 288.
- Stable: 45 runs × 5 files; dynamic: 9 runs × 7 files.
- Existing derived analysis files: 40.

Stable runs include metadata, server-window CSV, HLOG, latency summary and controller summary. Dynamic runs add process CSV and process metadata. Logs/PID markers are excluded. Client JTL/log/metadata were absent from the source archive.

## HLOG semantics

HLOG records mergeable HdrHistogram distributions for successful requests in non-empty 10-second windows. It is not one row per request. Dynamic 0-user empty windows do not contain artificial zero-latency observations.

## Sanitization and manifests

CSV/HLOG and PNG/PDF are byte copies. Metadata and existing derived text replace only known local paths or remote addresses. No performance value, timestamp, Run ID or Java version was intentionally changed.

- `experiment-matrix.csv`: exactly 54 rows.
- `public-files.csv`: copied raw/derived files and transformations.
- `checksums-sha256.txt`: all public files except itself.

`derived/` contains existing outputs copied from the private analysis archive, not new conclusions generated during publication preparation.

