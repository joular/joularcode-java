# <a href="https://www.noureddine.org/research/joular/"><img src="https://raw.githubusercontent.com/joular/.github/main/profile/joular.png" alt="Joular Project" width="64" /></a> Joular Code - Java

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](https://openjdk.java.net)

Joular Code - Java is a lightweight and efficient Java agent for monitoring the energy consumption of methods and execution branches at the source code level.

This project is part of [Joular Code](https://github.com/joular/joularcode), and is the successor of [JoularJX](https://github.com/joular/joularjx).

## :rocket: Features

- Monitor power consumption and energy of each method and execution branch at runtime
- Works as a Java agent — no source code instrumentation or modification needed
- Samples the JVM stack at high frequency (default: every 10 ms) and attributes energy every second
- Supports three power data source backends from [Joular Core](https://github.com/joular/joularcore):
  - Shared memory ring buffer (IPC) — lowest latency, recommended
  - CSV file — file-based polling
  - HTTP endpoint — remote or containerized setups
- Generates CSV files with per-method and per-branch power (Watts) and energy (Joules)
- Produces two output sets: one for all methods (including JDK internals), one filtered to your application packages
- Configurable method filtering by package/class prefix to focus energy data on your code
- Cross-platform: Windows, macOS, Linux, and Raspberry Pi

## :bulb: How It Works

Joular Code - Java runs as a Java instrumentation agent alongside your application. Every monitoring cycle (default: 1 second), it:

1. **Samples the JVM stack** — every `stack-monitoring-sample-rate` milliseconds, it captures the stack trace of every `RUNNABLE` thread, building sample counts for each call branch.
2. **Measures thread CPU time** — it takes CPU-time snapshots at the start and end of each cycle using `ThreadMXBean`, computing how much CPU time each thread consumed during the window.
3. **Reads system power** from Joular Core — the total CPU power in Watts for the current monitoring cycle.
4. **Scales to process power** — it estimates the JVM process's share of CPU power using `(processCpuLoad / systemCpuLoad) * totalCpuPower`.
5. **Attributes energy to methods** — each thread receives a fraction of process power proportional to its CPU time. Within each thread, power is further distributed to call branches proportionally to their sample counts.
6. **Writes results** to CSV files — both power (W) and energy (J = W × interval) are recorded per branch per cycle.

## :package: Compilation and Installation

### Requirements

- Java 21 or later
- Apache Maven 3.6 or later
- [Joular Core](https://github.com/joular/joularcore) running on the same machine

### Build

Clone the repository and build with Maven:

```bash
git clone https://github.com/joular/joularcode-java.git
cd joularcode-java
mvn clean install
```

This produces a fat JAR at:

```
target/joularcodejava-<version>.jar
```

The JAR bundles all dependencies (including JNA) via the Maven Shade plugin, so no additional classpath setup is needed.

## :bulb: Usage

Joular Code - Java attaches to your Java application as a Java agent. You must have [Joular Core](https://github.com/joular/joularcore) running before starting your application.

### Basic usage

```bash
java -javaagent:joularcodejava-<version>.jar YourMainClass
```

### Running a JAR application

```bash
java -javaagent:joularcodejava-<version>.jar -jar yourApplication.jar
```

### Specifying a custom configuration file

By default, Joular Code - Java looks for `joularcodejava.properties` in the current working directory. To specify a custom path:

```bash
java -Djoularcodejava.properties=/path/to/joularcodejava.properties -javaagent:joularcodejava-<version>.jar -jar yourApplication.jar
```

## :gear: Configuration

All configuration is done via a `joularcodejava.properties` file. Joular Code - Java loads it from the following locations in order:

1. Path specified by the `-Djoularcodejava.properties=<path>` JVM property
2. `joularcodejava.properties` in the current working directory
3. Built-in defaults (bundled inside the JAR)

### Configuration properties

| Property | Default | Description |
|---|---|---|
| `power-source-type` | `ringbuffer` | Power data backend: `ringbuffer`, `csv`, or `http` |
| `joular-core-ringbuffer-path` | OS-dependent | Path to the Joular Core ring buffer (see below) |
| `joular-core-csv-path` | `joularcore-data.csv` | Path to the Joular Core CSV output file |
| `joular-core-http-url` | `http://localhost:8080/data` | URL of the Joular Core HTTP endpoint |
| `stack-monitoring-sample-rate` | `10` | Stack sampling interval in milliseconds. Lower = more accurate but higher overhead |
| `results-path` | `joular-code-java-results` | Directory where CSV result files are written |
| `methods-filtering-prefix` | *(empty)* | Comma-separated list of package/class prefixes to filter app-specific methods (e.g., `com.example,org.myapp`) |

### Power source backends

#### Ring buffer (recommended)

The ring buffer backend reads power data from a POSIX shared memory or Windows named shared memory object written by Joular Core. This is the lowest-latency option and has minimal overhead.

Default paths by OS:
- **Linux**: `/dev/shm/joularcorering`
- **macOS**: `/tmp/joularcorering`
- **Windows**: `Local\JoularCoreRing`

```properties
power-source-type=ringbuffer
joular-core-ringbuffer-path=/dev/shm/joularcorering
```

#### CSV file

Joular Core can be configured to write power data to a CSV file. Joular Code - Java reads the latest row from that file each monitoring cycle. Use this when IPC is unavailable.

```properties
power-source-type=csv
joular-core-csv-path=/path/to/joularcore-data.csv
```

#### HTTP endpoint

Reads power data from a JSON HTTP endpoint exposed by Joular Core. The response must contain a `cpu_power` field. Suitable for remote or containerized deployments.

```properties
power-source-type=http
joular-core-http-url=http://localhost:8080/data
```

### Method filtering

The `methods-filtering-prefix` property controls which methods appear in the `methods-power-app.csv` output. Provide one or more comma-separated package or class name prefixes:

```properties
methods-filtering-prefix=com.example,org.myapp
```

- If left empty, all methods (except the agent's own thread) appear in both output files.
- When set, `methods-power-app.csv` only contains branches where at least one stack element matches a prefix. Energy from unmatched (e.g., JDK) frames that are called by a matched method is attributed upward to the matched method.
- `methods-power-all.csv` always contains every observed branch regardless of this setting.


## :bar_chart: Generated Files

Joular Code - Java writes results into the directory configured by `results-path`. Two CSV files are produced and appended to during execution:

| File | Contents |
|---|---|
| `methods-power-all.csv` | Power and energy for all observed call branches, including JDK internals |
| `methods-power-app.csv` | Power and energy for call branches filtered to your application packages |

### CSV format

Both files share the same schema:

```
timestamp,branch,power_watts,energy_joules,interval_seconds
```

| Column | Type | Description |
|---|---|---|
| `timestamp` | long (ms) | Unix timestamp in milliseconds at the end of the monitoring cycle |
| `branch` | string | Semicolon-separated call chain from oldest to newest frame (e.g., `com.example.Main.run;com.example.Service.process`) |
| `power_watts` | double | Estimated power consumed by this branch during the cycle (W) |
| `energy_joules` | double | Energy = power_watts × interval_seconds (J) |
| `interval_seconds` | double | Duration of the monitoring cycle (s), typically ~1.0 |

### Example output

```
timestamp,branch,power_watts,energy_joules,interval_seconds
1746000000000,com.example.Main.main;com.example.Worker.compute,2.341500000,2.341500000,1.000000000
1746000001000,com.example.Main.main;com.example.Worker.compute,2.158300000,2.158300000,1.000000000
```

## :warning: Troubleshooting

- **"Joular Core ring buffer appears stale"** (`WARNING` log): Joular Core has stopped advancing the ring buffer, so confirm that the Joular Core process is still running and still writing data. Until that resumes, Joular Code - Java gives a power value of `0.0`.
- **"Could not read power data from CSV: ..."** (`WARNING` log): the configured `joular-core-csv-path` does not exist. Check that Joular Core is running in CSV export mode and writing to the same path. The warning is logged only once until the file becomes available again.
- **HTTP mode returns 0.0 power**: the HTTP endpoint must return a JSON object containing a `"cpu_power": <number>` key directly on the top-level object (not nested). Keys like `"last_cpu_power"` or `"cpu_power_limit"` are ignored.
- **No rows in `methods-power-app.csv`**: either `methods-filtering-prefix` is unset (in which case rows still go to `methods-power-all.csv`), or the configured prefix does not match any fully-qualified method name in your application.

## :information_source: Notes

- Joular Code - Java requires `com.sun.management.OperatingSystemMXBean` to measure process and system CPU load. This is available in all standard HotSpot JVMs (OpenJDK, Oracle JDK). Minimal or embedded JVMs that do not provide this class are not supported.
- Thread CPU time attribution requires `ThreadMXBean.isThreadCpuTimeSupported()` to return `true`. If it does not, method-level energy attribution will be degraded.
- The agent's own monitoring thread is excluded from all energy measurements.
- Power values of `0.0` are suppressed in the output (rows with zero power are not written).
- The `NO_COLOR` environment variable disables ANSI color output in the agent banner.

## :newspaper: License

Joular Code - Java is licensed under the GNU LGPL 3 license only (LGPL-3.0-only).

Copyright © 2025-2026, Adel Noureddine.
All rights reserved. This program and the accompanying materials are made available under the terms of the [GNU Lesser General Public License v3.0 (LGPL-3.0-only)](https://www.gnu.org/licenses/lgpl-3.0.en.html) which accompanies this distribution.

Author: Prof. Adel Noureddine
