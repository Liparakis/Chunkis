# Full benchmark results — 6 November 2026

Source: `v4.1.0-unpinned-rerun-20261106-full`, commit `d43d6a82`. Ryzen 7 5700X at 4.6 GHz, 32 GB DDR4-3600 RAM. Three
alternating runs per side, unpinned. Background load was not recorded.

**Aggregation:** every number is the arithmetic mean of the corresponding field from three reports. Percentiles and
maxima are means of per-run statistics, not percentiles or maxima pooled across runs. Sample counts are also averaged.
N/A means the field was absent; missing fields are never treated as zero.

**Interpretation:** lower execution times, CPU, allocations, GC, memory, total storage and lateness are preferable for
this workload. Counts and directory components are context, not a score. Tick intervals describe cadence around a 50 ms
target; lower is not automatically better. Windows thread CPU measurements have coarse resolution, so zero readings and
repeated percentile values need care. Per-chunk size fields compare encodings of sampled chunks, not whole-world
footprint; CIS-specific fields have no vanilla-mode equivalent.

**Regression result:** the original guard checks only a subset of these measurements. Tick p90 failed its local baseline
margin (+12.2%, margin 8%); other checked metrics passed. Total lateness had no baseline entry. Expanded metrics were
not baseline-checked.

Vanilla storage was tested with Chunkis still loaded. World disk usage is measured before shutdown compaction.

## CPU, GC, memory and storage

### CPU and garbage collection

| Report field                  |    Chunkis |    Vanilla | Change vs. vanilla |
|:------------------------------|-----------:|-----------:|-------------------:|
| `cpu.wallSeconds`             |     15.7 s |     15.9 s |              -1.5% |
| `cpu.processCpuSeconds`       |     52.6 s |     62.3 s |             -15.6% |
| `cpu.serverThreadCpuSeconds`  |     11.3 s |     10.0 s |             +13.5% |
| `cpu.serverThreadAllocatedMb` | 4,737.2 MB | 4,352.6 MB |              +8.8% |
| `cpu.gcCount`                 |        9.7 |       13.0 |             -25.6% |
| `cpu.gcMillis`                |   450.0 ms |   736.7 ms |             -38.9% |

### Memory

| Report field            |    Chunkis |    Vanilla | Change vs. vanilla |
|:------------------------|-----------:|-----------:|-------------------:|
| `memory.peakHeapMb`     | 4,860.1 MB | 4,791.4 MB |              +1.4% |
| `memory.retainedHeapMb` |   874.0 MB | 1,173.4 MB |             -25.5% |

### World storage

| Report field                   | Chunkis | Vanilla | Change vs. vanilla |
|:-------------------------------|--------:|--------:|-------------------:|
| `derived.Total storage growth` |  5.1 MB |  7.7 MB |             -33.3% |
| `derived.Total world storage`  | 42.9 MB | 61.7 MB |             -30.5% |
| `disk.chunkisDirMb`            | 40.6 MB |  0.1 MB |                  — |
| `disk.vanillaDirsMb`           |  2.3 MB | 61.6 MB |                  — |
| `disk.growthMb`                |  5.1 MB |  0.0 MB |                  — |
| `disk.vanillaGrowthMb`         |  0.0 MB |  7.7 MB |                  — |

## Tick execution time

### All phases

| Report field           |  Chunkis |  Vanilla | Change vs. vanilla |
|:-----------------------|---------:|---------:|-------------------:|
| `tickMillis.all.count` |    343.7 |    366.0 |                  — |
| `tickMillis.all.mean`  |  30.8 ms |  26.9 ms |             +14.6% |
| `tickMillis.all.p50`   |  31.0 ms |  27.3 ms |             +13.6% |
| `tickMillis.all.p90`   |  44.4 ms |  35.4 ms |             +25.3% |
| `tickMillis.all.p95`   |  50.3 ms |  40.8 ms |             +23.3% |
| `tickMillis.all.p99`   | 195.4 ms | 314.3 ms |             -37.8% |
| `tickMillis.all.max`   | 331.6 ms | 434.6 ms |             -23.7% |

### Unload phase

| Report field              |  Chunkis |  Vanilla | Change vs. vanilla |
|:--------------------------|---------:|---------:|-------------------:|
| `tickMillis.unload.count` |     55.7 |     74.0 |                  — |
| `tickMillis.unload.mean`  |  24.7 ms |  18.4 ms |             +34.2% |
| `tickMillis.unload.p50`   |   3.3 ms |   0.1 ms |           +5172.6% |
| `tickMillis.unload.p90`   |  49.3 ms |  29.1 ms |             +69.4% |
| `tickMillis.unload.p95`   | 126.7 ms | 152.0 ms |             -16.7% |
| `tickMillis.unload.p99`   | 331.6 ms | 353.2 ms |              -6.1% |
| `tickMillis.unload.max`   | 331.6 ms | 353.2 ms |              -6.1% |

### Reload phase

| Report field              |  Chunkis |  Vanilla | Change vs. vanilla |
|:--------------------------|---------:|---------:|-------------------:|
| `tickMillis.reload.count` |     40.0 |     44.0 |                  — |
| `tickMillis.reload.mean`  |  11.2 ms |   8.4 ms |             +32.6% |
| `tickMillis.reload.p50`   |   3.0 ms |   1.7 ms |             +71.1% |
| `tickMillis.reload.p90`   |   7.6 ms |   3.1 ms |            +143.4% |
| `tickMillis.reload.p95`   |  51.3 ms |  29.6 ms |             +73.1% |
| `tickMillis.reload.p99`   | 160.9 ms | 151.0 ms |              +6.5% |
| `tickMillis.reload.max`   | 160.9 ms | 151.0 ms |              +6.5% |

### Edit phase

| Report field            |  Chunkis |  Vanilla | Change vs. vanilla |
|:------------------------|---------:|---------:|-------------------:|
| `tickMillis.edit.count` |    206.0 |    206.0 |                  — |
| `tickMillis.edit.mean`  |  33.9 ms |  31.0 ms |              +9.4% |
| `tickMillis.edit.p50`   |  33.1 ms |  29.9 ms |             +10.5% |
| `tickMillis.edit.p90`   |  42.8 ms |  35.9 ms |             +19.4% |
| `tickMillis.edit.p95`   |  45.5 ms |  38.8 ms |             +17.1% |
| `tickMillis.edit.p99`   |  54.7 ms |  53.4 ms |              +2.3% |
| `tickMillis.edit.max`   | 104.7 ms | 101.8 ms |              +2.9% |

### Save phase

| Report field            |  Chunkis |  Vanilla | Change vs. vanilla |
|:------------------------|---------:|---------:|-------------------:|
| `tickMillis.save.count` |     42.0 |     42.0 |                  — |
| `tickMillis.save.mean`  |  45.0 ms |  44.1 ms |              +1.9% |
| `tickMillis.save.p50`   |  34.8 ms |  25.1 ms |             +38.7% |
| `tickMillis.save.p90`   |  48.6 ms |  36.7 ms |             +32.4% |
| `tickMillis.save.p95`   |  88.7 ms |  43.9 ms |            +102.0% |
| `tickMillis.save.p99`   | 218.7 ms | 434.6 ms |             -49.7% |
| `tickMillis.save.max`   | 218.7 ms | 434.6 ms |             -49.7% |

## Chunk unload and reload latency

### Unload phase

| Report field                      |  Chunkis |  Vanilla | Change vs. vanilla |
|:----------------------------------|---------:|---------:|-------------------:|
| `chunkLatencyMillis.unload.count` |  8,192.0 |  8,192.0 |                  — |
| `chunkLatencyMillis.unload.mean`  | 254.8 ms | 202.1 ms |             +26.1% |
| `chunkLatencyMillis.unload.p50`   | 222.9 ms | 162.8 ms |             +36.9% |
| `chunkLatencyMillis.unload.p90`   | 430.2 ms | 392.3 ms |              +9.7% |
| `chunkLatencyMillis.unload.p95`   | 454.8 ms | 412.4 ms |             +10.3% |
| `chunkLatencyMillis.unload.p99`   | 473.8 ms | 427.8 ms |             +10.7% |
| `chunkLatencyMillis.unload.max`   | 478.5 ms | 433.3 ms |             +10.4% |

### Reload phase

| Report field                      |    Chunkis |    Vanilla | Change vs. vanilla |
|:----------------------------------|-----------:|-----------:|-------------------:|
| `chunkLatencyMillis.reload.count` |    8,192.0 |    8,192.0 |                  — |
| `chunkLatencyMillis.reload.mean`  |   903.5 ms |   988.2 ms |              -8.6% |
| `chunkLatencyMillis.reload.p50`   |   891.5 ms |   976.5 ms |              -8.7% |
| `chunkLatencyMillis.reload.p90`   | 1,011.3 ms | 1,120.4 ms |              -9.7% |
| `chunkLatencyMillis.reload.p95`   | 1,023.6 ms | 1,134.4 ms |              -9.8% |
| `chunkLatencyMillis.reload.p99`   | 1,058.8 ms | 1,162.4 ms |              -8.9% |
| `chunkLatencyMillis.reload.max`   | 1,060.4 ms | 1,166.4 ms |              -9.1% |

## Tick cadence and lateness

### Tick interval / cadence (50 ms target)

| Report field               |  Chunkis |  Vanilla | Change vs. vanilla |
|:---------------------------|---------:|---------:|-------------------:|
| `tickIntervalMillis.count` |    343.7 |    366.0 |                  — |
| `tickIntervalMillis.mean`  |  45.8 ms |  43.7 ms |                  — |
| `tickIntervalMillis.p50`   |  49.8 ms |  49.7 ms |                  — |
| `tickIntervalMillis.p90`   |  50.9 ms |  50.9 ms |                  — |
| `tickIntervalMillis.p95`   |  55.3 ms |  52.8 ms |                  — |
| `tickIntervalMillis.p99`   | 204.1 ms | 314.4 ms |                  — |
| `tickIntervalMillis.max`   | 333.4 ms | 434.7 ms |                  — |

### Late ticks

| Report field                                 | Chunkis | Vanilla | Change vs. vanilla |
|:---------------------------------------------|--------:|--------:|-------------------:|
| `tickIntervalMillis.lateTicks`               |    17.3 |    15.7 |             +10.6% |
| `tickIntervalMillis.lateTicksByPhase.unload` |     4.0 |     5.0 |             -20.0% |
| `tickIntervalMillis.lateTicksByPhase.reload` |     6.0 |     5.0 |             +20.0% |
| `tickIntervalMillis.lateTicksByPhase.edit`   |     3.7 |     3.7 |              +0.0% |
| `tickIntervalMillis.lateTicksByPhase.save`   |     3.7 |     2.0 |             +83.3% |

### Accumulated lateness

| Report field                                      |    Chunkis |    Vanilla | Change vs. vanilla |
|:--------------------------------------------------|-----------:|-----------:|-------------------:|
| `derived.totalLateness`                           | 1,399.3 ms | 2,031.7 ms |             -31.1% |
| `tickIntervalMillis.latenessMillisByPhase.unload` |   572.3 ms |   938.3 ms |             -39.0% |
| `tickIntervalMillis.latenessMillisByPhase.reload` |   316.0 ms |   298.0 ms |              +6.0% |
| `tickIntervalMillis.latenessMillisByPhase.edit`   |   130.0 ms |    99.3 ms |             +30.9% |
| `tickIntervalMillis.latenessMillisByPhase.save`   |   381.0 ms |   696.0 ms |             -45.3% |

## Phase duration

### Unload phase · elapsed time

| Report field                     |  Chunkis |  Vanilla | Change vs. vanilla |
|:---------------------------------|---------:|---------:|-------------------:|
| `phaseLengthMillis.unload.count` |      2.0 |      2.0 |                  — |
| `phaseLengthMillis.unload.mean`  | 391.0 ms | 376.1 ms |              +4.0% |
| `phaseLengthMillis.unload.p50`   | 290.3 ms | 295.4 ms |              -1.7% |
| `phaseLengthMillis.unload.p90`   | 491.6 ms | 456.9 ms |              +7.6% |
| `phaseLengthMillis.unload.p95`   | 491.6 ms | 456.9 ms |              +7.6% |
| `phaseLengthMillis.unload.p99`   | 491.6 ms | 456.9 ms |              +7.6% |
| `phaseLengthMillis.unload.max`   | 491.6 ms | 456.9 ms |              +7.6% |

### Reload phase · elapsed time

| Report field                     |    Chunkis |    Vanilla | Change vs. vanilla |
|:---------------------------------|-----------:|-----------:|-------------------:|
| `phaseLengthMillis.reload.count` |        2.0 |        2.0 |                  — |
| `phaseLengthMillis.reload.mean`  | 1,016.4 ms | 1,117.3 ms |              -9.0% |
| `phaseLengthMillis.reload.p50`   |   961.4 ms | 1,030.4 ms |              -6.7% |
| `phaseLengthMillis.reload.p90`   | 1,071.3 ms | 1,204.1 ms |             -11.0% |
| `phaseLengthMillis.reload.p95`   | 1,071.3 ms | 1,204.1 ms |             -11.0% |
| `phaseLengthMillis.reload.p99`   | 1,071.3 ms | 1,204.1 ms |             -11.0% |
| `phaseLengthMillis.reload.max`   | 1,071.3 ms | 1,204.1 ms |             -11.0% |

### Unload phase · ticks

| Report field                    |    Chunkis |    Vanilla | Change vs. vanilla |
|:--------------------------------|-----------:|-----------:|-------------------:|
| `phaseLengthTicks.unload.count` |        2.0 |        2.0 |                  — |
| `phaseLengthTicks.unload.mean`  | 27.8 ticks | 37.0 ticks |             -24.8% |
| `phaseLengthTicks.unload.p50`   | 10.7 ticks | 12.0 ticks |             -11.1% |
| `phaseLengthTicks.unload.p90`   | 45.0 ticks | 62.0 ticks |             -27.4% |
| `phaseLengthTicks.unload.p95`   | 45.0 ticks | 62.0 ticks |             -27.4% |
| `phaseLengthTicks.unload.p99`   | 45.0 ticks | 62.0 ticks |             -27.4% |
| `phaseLengthTicks.unload.max`   | 45.0 ticks | 62.0 ticks |             -27.4% |

### Reload phase · ticks

| Report field                    |    Chunkis |    Vanilla | Change vs. vanilla |
|:--------------------------------|-----------:|-----------:|-------------------:|
| `phaseLengthTicks.reload.count` |        2.0 |        2.0 |                  — |
| `phaseLengthTicks.reload.mean`  | 20.0 ticks | 22.0 ticks |              -9.1% |
| `phaseLengthTicks.reload.p50`   | 19.0 ticks | 20.3 ticks |              -6.6% |
| `phaseLengthTicks.reload.p90`   | 21.0 ticks | 23.7 ticks |             -11.3% |
| `phaseLengthTicks.reload.p95`   | 21.0 ticks | 23.7 ticks |             -11.3% |
| `phaseLengthTicks.reload.p99`   | 21.0 ticks | 23.7 ticks |             -11.3% |
| `phaseLengthTicks.reload.max`   | 21.0 ticks | 23.7 ticks |             -11.3% |

## Server-thread CPU time per tick

### Unload phase

| Report field                 |  Chunkis |  Vanilla | Change vs. vanilla |
|:-----------------------------|---------:|---------:|-------------------:|
| `tickCpuMillis.unload.count` |     55.7 |     74.0 |                  — |
| `tickCpuMillis.unload.mean`  |  23.9 ms |  15.4 ms |             +55.9% |
| `tickCpuMillis.unload.p50`   |   0.0 ms |   0.0 ms |                N/A |
| `tickCpuMillis.unload.p90`   |  52.1 ms |   5.2 ms |            +900.0% |
| `tickCpuMillis.unload.p95`   | 125.0 ms |  78.1 ms |             +60.0% |
| `tickCpuMillis.unload.p99`   | 317.7 ms | 354.2 ms |             -10.3% |
| `tickCpuMillis.unload.max`   | 317.7 ms | 354.2 ms |             -10.3% |

### Reload phase

| Report field                 |  Chunkis |  Vanilla | Change vs. vanilla |
|:-----------------------------|---------:|---------:|-------------------:|
| `tickCpuMillis.reload.count` |     40.0 |     44.0 |                  — |
| `tickCpuMillis.reload.mean`  |  10.2 ms |   6.3 ms |             +62.3% |
| `tickCpuMillis.reload.p50`   |   0.0 ms |   0.0 ms |                N/A |
| `tickCpuMillis.reload.p90`   |  15.6 ms |   5.2 ms |            +200.0% |
| `tickCpuMillis.reload.p95`   |  52.1 ms |  15.6 ms |            +233.3% |
| `tickCpuMillis.reload.p99`   | 151.0 ms | 140.6 ms |              +7.4% |
| `tickCpuMillis.reload.max`   | 151.0 ms | 140.6 ms |              +7.4% |

### Edit phase

| Report field               | Chunkis | Vanilla | Change vs. vanilla |
|:---------------------------|--------:|--------:|-------------------:|
| `tickCpuMillis.edit.count` |   206.0 |   206.0 |                  — |
| `tickCpuMillis.edit.mean`  | 32.8 ms | 28.9 ms |             +13.2% |
| `tickCpuMillis.edit.p50`   | 31.2 ms | 31.2 ms |              +0.0% |
| `tickCpuMillis.edit.p90`   | 46.9 ms | 41.7 ms |             +12.5% |
| `tickCpuMillis.edit.p95`   | 46.9 ms | 41.7 ms |             +12.5% |
| `tickCpuMillis.edit.p99`   | 52.1 ms | 46.9 ms |             +11.1% |
| `tickCpuMillis.edit.max`   | 57.3 ms | 52.1 ms |             +10.0% |

### Save phase

| Report field               |  Chunkis |  Vanilla | Change vs. vanilla |
|:---------------------------|---------:|---------:|-------------------:|
| `tickCpuMillis.save.count` |     42.0 |     42.0 |                  — |
| `tickCpuMillis.save.mean`  |  41.0 ms |  37.5 ms |              +9.6% |
| `tickCpuMillis.save.p50`   |  31.2 ms |  26.0 ms |             +20.0% |
| `tickCpuMillis.save.p90`   |  46.9 ms |  36.5 ms |             +28.6% |
| `tickCpuMillis.save.p95`   |  57.3 ms |  41.7 ms |             +37.5% |
| `tickCpuMillis.save.p99`   | 197.9 ms | 265.6 ms |             -25.5% |
| `tickCpuMillis.save.max`   | 197.9 ms | 265.6 ms |             -25.5% |

## Per-chunk encoding diagnostics

### Size and coverage

| Report field                               |    Chunkis |   Vanilla | Change vs. vanilla |
|:-------------------------------------------|-----------:|----------:|-------------------:|
| `sizePerChunk.chunks`                      |    4,096.0 |   4,096.0 |                  — |
| `sizePerChunk.chunkisOwnedChunks`          |    4,096.0 |       0.0 |                  — |
| `sizePerChunk.vanillaBytes`                |  6,828.2 B | 6,828.9 B |                  — |
| `sizePerChunk.vanillaOnDiskBytes`          |  9,579.7 B | 9,580.7 B |                  — |
| `sizePerChunk.chunkisBytes`                |  5,768.1 B |       N/A |                  — |
| `sizePerChunk.chunkisRawBytes`             | 24,353.7 B |       N/A |                  — |
| `sizePerChunk.chunkisToVanilla`            |    0.845 × |       N/A |                  — |
| `sizePerChunk.chunkisToVanillaOnDisk`      |    0.602 × |       N/A |                  — |
| `sizePerChunk.chunksWithBaseChunk`         |        0.0 |       N/A |                  — |
| `sizePerChunk.chunksWithFullBlockBaseline` |    4,096.0 |       N/A |                  — |
| `sizePerChunk.metadataRawBytes`            | 19,978.6 B |       N/A |                  — |

## Scenario configuration

| Field                   | Chunkis                           | Vanilla                           |
|:------------------------|:----------------------------------|:----------------------------------|
| `config.chunks`         | 4096                              | 4096                              |
| `config.chunksPerTick`  | 4                                 | 4                                 |
| `config.dimension`      | minecraft:overworld               | minecraft:overworld               |
| `config.editsPerChunk`  | 64                                | 64                                |
| `config.fillHeight`     | 64                                | 64                                |
| `config.measuredRounds` | 2                                 | 2                                 |
| `config.natural`        | True                              | True                              |
| `config.scenario`       | round-trip-reload-and-edits-async | round-trip-reload-and-edits-async |
| `config.storage`        | chunkis                           | vanilla                           |
| `config.ticksPerSecond` | 20                                | 20                                |
| `config.touchedChunks`  | 410                               | 410                               |
| `config.touchedPercent` | 10                                | 10                                |
