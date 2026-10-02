# <a href="https://www.noureddine.org/research/joular/"><img src="https://raw.githubusercontent.com/joular/.github/main/profile/joular.png" alt="Joular Project" width="64" /></a> Joular Code for Java

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.java.net)

Joular Code for Java is a lightweight and efficient Java agent for monitoring the energy consumption of methods and execution branches at the source code level.

This project is part of [Joular Code](https://github.com/joular/joularcode), and is the successor of [JoularJX](https://github.com/joular/joularjx).

## :rocket: Features

- Monitor power consumption and energy of each method and execution branch at runtime
- Works as a Java agent, with no source code instrumentation or modification needed
- Samples the JVM stack at high frequency (default: every 10 ms) and attributes energy every second
- No runtime dependencies: the agent runs on the JDK alone
- Measures CPU power in three ways:
  - Linux RAPL, read directly from powercap: nothing else to install
  - [PowerJoular](https://github.com/joular/powerjoular)'s shared memory ring buffer: Windows, macOS, Raspberry Pi, with the measuring in a separate process
  - Inside a virtual machine, using a shared power file between the host and the guest
- Generates CSV files with per-method and per-branch power (Watts) and energy (Joules)
- Produces two output sets: one for all methods (including JDK methods), and one filtered and calculated to your application's methods
- Configurable method filtering by package/class prefix to focus energy data on your code
- Cross-platform: Windows, macOS, Linux, and Raspberry Pi

## :bulb: How It Works

Joular Code for Java runs as a Java agent alongside your application. Every monitoring cycle (about 1 second), it:

1. **Samples the JVM stack**: every `stack-monitoring-sample-rate` milliseconds, it captures the stack trace of every `RUNNABLE` thread, counting how often each call branch is seen.
2. **Measures CPU time**: at the start and end of each cycle, it reads the CPU time of each thread and of the whole JVM.
3. **Reads the CPU power** of the machine over the cycle, from RAPL, PowerJoular, or the host of a virtual machine.
4. **Scales to process power**: the JVM's share of the CPU power is its own CPU load over the machine's: `cpuPower * processLoad / systemLoad`.
5. **Attributes energy to methods and execution branches**: each thread receives a fraction of process power proportional to its CPU time. Within each thread, power is further distributed to execution call branches proportionally to their sample counts.
6. **Writes results** to CSV files: both power (W) and energy (J = W × interval) are recorded per branch per cycle.

## :package: Compilation and Installation

### Requirements

- Java 21 or later, to build and to run the application being monitored
- Apache Maven 3.6.3 or later
- One way to measure the CPU (see [Power sources](#power-sources)): read access to Linux RAPL, [PowerJoular](https://github.com/joular/powerjoular) 2.0.0 or later, or, in a virtual machine, a host running PowerJoular (or another power monitoring tool)

### Build

Clone the repository and build with Maven:

```bash
git clone https://github.com/joular/joularcode-java.git
cd joularcode-java
mvn clean install
```

This produces a JAR at:

```
target/joularcodejava-<version>.jar
```

The agent has no runtime dependencies, so the JAR holds nothing but its own classes and needs no additional classpath setup.

## :bulb: Usage

Joular Code for Java attaches to your Java application as a Java agent, either on the command line with `-javaagent:` or to an already running JVM. On Linux it reads the CPU power from RAPL directly, and elsewhere (or on Linux with `power-source-type=ringbuffer`), start [PowerJoular](https://github.com/joular/powerjoular) with `-r` option (to export power data to a ring buffer), see [Power sources](#power-sources).

### Basic usage

```bash
java -javaagent:joularcodejava-<version>.jar YourMainClass
```

### Running a JAR application

```bash
java -javaagent:joularcodejava-<version>.jar -jar yourApplication.jar
```

### Attaching to a JVM that is already running

The agent can also be loaded into a running JVM through the Attach API, without restarting it:

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("/path/to/joularcodejava-<version>.jar");
vm.detach();
```

Monitoring covers the JVM from the moment it attaches, so whatever the application did before that is not in the results. JDK 21 and later allow the attach by default but print a warning: start the target JVM with `-XX:+EnableDynamicAgentLoading` to silence it, or with `-XX:-EnableDynamicAgentLoading` to forbid it.

Configuration is read when the agent attaches, so `-Djoularcodejava.properties=...` belongs on the target JVM's own command line. Attaching a second time is ignored, with a warning, rather than starting a second monitor.

### Specifying a custom configuration file

By default, Joular Code for Java looks for `joularcodejava.properties` in the current working directory. To specify another path:

```bash
java -Djoularcodejava.properties=/path/to/joularcodejava.properties -javaagent:joularcodejava-<version>.jar -jar yourApplication.jar
```

## :gear: Configuration

All configuration is done via a `joularcodejava.properties` file, read from:

1. The path given with the `-Djoularcodejava.properties=<path>` JVM property
2. or `joularcodejava.properties` in the current working directory

A missing file, or a property left empty, means the default value will be used. [joularcodejava.properties.example](joularcodejava.properties.example) is a commented example template you can use.

### Configuration properties

| Property | Default | Description |
|---|---|---|
| `power-source-type` | `auto` | Where the CPU power comes from: `auto`, `rapl`, `ringbuffer` or `vm` |
| `powerjoular-ringbuffer-path` | OS-dependent | Path to the PowerJoular ring buffer (see below) |
| `vm-power-file` | *(empty)* | In a virtual machine, the file the host writes the power of the guest virtual machine (see below) |
| `vm-power-format` | `powerjoular` | Format of `vm-power-file`: `powerjoular` or `watts` |
| `stack-monitoring-sample-rate` | `10` | Stack sampling interval in milliseconds, from 1 to 1000. Lower = more accurate but higher overhead |
| `results-path` | `joular-code-java-results` | Directory where CSV result files are written |
| `methods-filtering-prefix` | *(empty)* | Comma-separated list of package/class prefixes to filter app-specific methods (e.g., `com.example,org.myapp`) |

### Power sources

With `power-source-type=auto`, the default, the agent reads RAPL on Linux, and the PowerJoular ring buffer everywhere else, including Linux machines without RAPL such as the Raspberry Pi. On Linux, `ringbuffer` keeps the application unprivileged: run `sudo powerjoular -r` and set it explicitly. In a virtual machine, set `vm`.

#### Linux RAPL (`rapl`)

Reads the energy counters of the CPU packages directly from `/sys/class/powercap/intel-rapl:N`, Intel and AMD alike, adding up every package of a machine with several sockets. Nothing else is needed, but `energy_uj` is only readable by root on most distributions: run the application as root, give its user read access to those files (for example with a udev rule), or run PowerJoular with `-r` as root and set `power-source-type=ringbuffer`. PowerJoular reads the first package only, so on a machine with several sockets `rapl` and `ringbuffer` do not give the same figure.

```properties
power-source-type=rapl
```

#### PowerJoular ring buffer (`ringbuffer`)

Reads the shared memory area [PowerJoular](https://github.com/joular/powerjoular) writes with `-r`. PowerJoular measures the hardware through [Joular Core](https://github.com/joular/joularcore) (RAPL on Windows, powermetrics on Macs, where PowerJoular has to run with `sudo`, power models on Raspberry Pi), and runs as a process of its own, so the privileges needed to measure the hardware stay out of the Java application. Each monitoring cycle ends as PowerJoular publishes a measurement, so the stack samples and the power describe the same second. PowerJoular may be started before or after the application, and restarted while it runs, except on Windows: a mapped file cannot be deleted there, so PowerJoular has to be started before the application and not restarted while it runs. When PowerJoular cannot read the CPU, it publishes 0 W and no rows are written, without a warning: `powerjoular -d` shows whether it can read the CPU.

Default paths by OS:
- **Linux**: `/dev/shm/powerjoular`
- **macOS**: `/tmp/powerjoular`
- **Windows**: `%PROGRAMDATA%\powerjoular`

```properties
power-source-type=ringbuffer
powerjoular-ringbuffer-path=/dev/shm/powerjoular
```

#### Virtual machine (`vm`)

The CPU of a virtual machine cannot be measured from inside it, but its host can measure the process the virtual machine runs as. Run PowerJoular on the host for that process, share the file it writes with the virtual machine (virtiofs, 9p, a shared folder), and point the agent at it. Nothing else has to run in the virtual machine.

On the host, `-p` with `-o` writes two files: `<file>` for the whole host, and `<file>-<pid>.csv` for the process, which is the one to share:

```bash
powerjoular -p <pid of the virtual machine's process> -o /shared/vm-power.csv
```

In the virtual machine:

```properties
power-source-type=vm
vm-power-file=/mnt/shared/vm-power.csv-<pid>.csv
vm-power-format=powerjoular
```

Only the first line of the file is read, in one of the two formats:

- `powerjoular` (the default): the row PowerJoular rewrites every second with `-o` for a process, `timestamp,cpu_usage,cpu_power`. The file of the whole host, with five columns, is refused, since it would charge all of the host's power to one virtual machine. The host writes on its own one-second cycle, so a cycle may see the row the previous one saw, or catch the file while it is rewritten: the last row covers up to two more cycles, after which the host is taken to have stopped and no energy is attributed until it writes again.
- `watts`: the power alone, in watts, written by any tool. The value is used as it is, however long ago it was written.

Some shared folders cache files in the guest (9p with `cache=loose`, virtiofs with `cache=always`), which can hide the host's updates: mount the share without caching.

### Method filtering

The `methods-filtering-prefix` property controls which methods appear in the `methods-power-app.csv` output. Provide one or more comma-separated package or class name prefixes:

```properties
methods-filtering-prefix=com.example,org.myapp
```

- If left empty, all methods (except the agent's own thread) appear in both output files.
- When set, `methods-power-app.csv` only contains branches where at least one stack element matches a prefix. Energy from unmatched (e.g., JDK) frames that are called by a matched method is attributed upward to the matched method.
- `methods-power-all.csv` always contains every observed branch regardless of this setting.

## :bar_chart: Generated Files

Joular Code for Java writes results into the directory configured by `results-path`. Two CSV files are produced and appended to during execution (a new run adds its rows to the existing files):

| File | Contents |
|---|---|
| `methods-power-all.csv` | Power and energy for all observed call branches, including JDK ones |
| `methods-power-app.csv` | Power and energy for call branches filtered to your application's packages and methods |

### CSV format

Both files share the same format:

```
timestamp,branch,power_watts,energy_joules,interval_seconds,coverage
```

| Column | Type | Description |
|---|---|---|
| `timestamp` | long (ms) | Unix timestamp in milliseconds at the end of the monitoring cycle |
| `branch` | string | Semicolon-separated call chain from oldest to newest frame (e.g., `com.example.Main.run;com.example.Service.process`) |
| `power_watts` | double | Estimated power consumed by this branch during the cycle (W) |
| `energy_joules` | double | Energy = power_watts × interval_seconds (J) |
| `interval_seconds` | double | Duration of the monitoring cycle (s), typically ~1.0 |
| `coverage` | double | The share of the JVM's CPU time during the cycle that belonged to threads the agent actually sampled, from 0.0 to 1.0 |

#### What `coverage` means

Power is split between threads by the CPU time each one used, and the denominator is every thread that used CPU, not only the ones that were caught in a sample.
Power drawn by a thread the agent never sampled is therefore left unattributed rather than shared out over the threads it did see.
`coverage` says how much of the JVM's CPU time is represented: at `1.0` everything was accounted for, and at `0.6` only 60% of what the JVM consumed are attributed to the observed threads at that timestamp.
Threads that end during a cycle are invisible here, because the JVM stops reporting a thread's CPU time once it has ended: their power goes to the threads still alive, and `coverage` does not drop.

### Example output

```
timestamp,branch,power_watts,energy_joules,interval_seconds,coverage
1746000000000,com.example.Main.main;com.example.Worker.compute,2.341500000,2.341500000,1.000000000,1.0000
1746000001000,com.example.Main.main;com.example.Worker.compute,2.158300000,2.158300000,1.000000000,0.9974
```

With RAPL, a cycle lasts one second, and the energy is read at its very end, so the power and the stack samples cover the same time. In a virtual machine, a cycle lasts one second too, and gets the latest power the host wrote. With the ring buffer, a cycle ends when PowerJoular publishes a measurement, or after two seconds without one, in which case that cycle gets no rows.

## :information_source: Notes

- Joular Code for Java requires `com.sun.management.OperatingSystemMXBean` to measure process and system CPU load. This is available in all standard HotSpot JVMs (OpenJDK, Oracle JDK). Minimal or embedded JVMs that do not provide this class are not supported.
- Thread CPU time attribution requires `ThreadMXBean.isThreadCpuTimeSupported()` to return `true`. If it does not, Joular Code for Java does not start, and the application runs unmonitored.
- The agent never follows a symbolic link when it opens its results files or the ring buffer, since it may run as root and these often sit in directories every user can write to.
- When the application runs as root, give the configuration file with `-Djoularcodejava.properties` rather than leaving it in a working directory others can write to, since it chooses where the results are written.
- The agent's own monitoring thread is excluded from all energy measurements.
- Power values of `0.0` are suppressed in the output (rows with zero power are not written).
- The cycle running when the JVM stops is not written, so a program that ends within its first second leaves the files with only their header.

## :newspaper: License

Joular Code for Java is licensed under the GNU LGPL 3 license only (LGPL-3.0-only).

Copyright © 2026, Adel Noureddine.
All rights reserved. This program and the accompanying materials are made available under the terms of the [GNU Lesser General Public License v3.0 (LGPL-3.0-only)](https://www.gnu.org/licenses/lgpl-3.0.en.html) which accompanies this distribution.

Author: Prof. Adel Noureddine
