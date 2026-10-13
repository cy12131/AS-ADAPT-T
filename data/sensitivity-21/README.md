# sensitivity-21 public dataset

## Scope

21 PRIME/100-user AS-ADAPT-T runs: seven parameter configurations across R1-R3. Variants are current (`c0`), mean low/high, throughput strict/loose and P99 strict/loose.

## Artifact counts

- Original raw artifacts: 84.
- Public raw artifacts: 42 (21 metadata + 21 server-window CSV).
- Server logs and PID markers are excluded.
- Request HLOG, separate latency/controller summaries and a dedicated confirmed statistical report were not present.

CSV measurements are byte copies. Metadata receives only known path/address sanitization. `experiment-matrix.csv` is the authoritative 21-run whitelist; this collection must not be merged with PRIME-54 or paper-73 counts.

