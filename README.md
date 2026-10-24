# <a href="https://www.noureddine.org/research/joular/"><img src="https://raw.githubusercontent.com/joular/.github/main/profile/joular.png" alt="Joular Project" width="64" /></a> Joular Code for Java

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.java.net)

![Joular Code for Java Logo](joularcode-java.png)

Joular Code for Java is a lightweight and efficient Java agent for monitoring the energy consumption of methods and execution branches at the source code level.

This project is part of [Joular Code](https://github.com/joular/joularcode), and is the successor of [JoularJX](https://github.com/joular/joularjx).

Detailed documentation (including user and reference guides) is available at: [https://joular.github.io/joularcode-java/](https://joular.github.io/joularcode-java/).

## :rocket: Features

- Monitor power consumption and energy of each method and execution branch at runtime
- Works as a Java agent, with no source code instrumentation or modification needed
- Samples the JVM stack at high frequency (default: every 10 ms) and attributes energy every second
- No runtime dependencies: the agent runs on the JDK alone
- Measures CPU power in three ways:
  - Linux RAPL, read directly from powercap: nothing else to install
  - [PowerJoular](https://github.com/joular/powerjoular)'s shared memory ring buffer: Windows, macOS, FreeBSD, Raspberry Pi, with the measuring in a separate process
  - Inside a virtual machine, using a shared power file between the host and the guest
- Generates CSV files with per-method and per-branch power (Watts) and energy (Joules)
- Produces two output sets: one for all methods (including JDK methods), and one filtered and calculated to your application's methods
- Configurable method filtering by package/class prefix to focus energy data on your code
- Cross-platform: Windows, macOS, Linux, FreeBSD, and Raspberry Pi

## :bulb: How It Works

Joular Code for Java runs as a Java agent alongside your application. Every monitoring cycle (about 1 second), it:

1. **Samples the JVM stack**: every `stack-monitoring-sample-rate` milliseconds, it captures the stack trace of every `RUNNABLE` thread, and charges the CPU time the thread used since its previous sample to the call branch it is on.
2. **Measures CPU time**: at the start and end of each cycle, it reads the CPU time of each thread and of the whole JVM.
3. **Reads the CPU power** of the machine over the cycle, from RAPL, PowerJoular, or the host of a virtual machine.
4. **Scales to process power**: the JVM's share of the CPU power is its own CPU load over the machine's: `cpuPower * processLoad / systemLoad`. On Linux, the machine's load and its number of CPUs are read from `/proc/stat` over the cycle, so inside a container the share is of the host's CPUs, not of the few the cgroup lets the JVM see.
5. **Attributes energy to methods and execution branches**: each call branch receives a fraction of process power proportional to the CPU time charged to it, over the CPU time of all of the JVM's threads. A thread blocked in native I/O, which Java still reports as `RUNNABLE`, uses no CPU, so the branch it waits on is not charged for waiting.
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

Joular Code for Java attaches to your Java application as a Java agent, either on the command line with `-javaagent:` or to an already running JVM. On Linux it reads the CPU power from RAPL directly when it may, and otherwise (elsewhere, on Linux without read access to RAPL, or with `power-source-type=ringbuffer`), start [PowerJoular](https://github.com/joular/powerjoular) with `-r` option (to export power data to a ring buffer), see [Power sources](#power-sources).

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

Configuration is read when the agent attaches. Give its path as the agent's argument, `vm.loadAgent("/path/to/joularcodejava-<version>.jar", "/path/to/joularcodejava.properties")`, or with `jcmd <pid> JVMTI.agent_load /path/to/joularcodejava-<version>.jar /path/to/joularcodejava.properties`. Without it, the target JVM's own `-Djoularcodejava.properties=...`, or its working directory, is used. When trying to attach a second time, it is ignored, with a warning.

### Specifying a custom configuration file

By default, Joular Code for Java looks for `joularcodejava.properties` in the current working directory. To specify another path:

```bash
java -Djoularcodejava.properties=/path/to/joularcodejava.properties -javaagent:joularcodejava-<version>.jar -jar yourApplication.jar
```

The agent's argument does the same, and comes first: `-javaagent:joularcodejava-<version>.jar=/path/to/joularcodejava.properties`.

## :gear: Configuration

All configuration is done via a `joularcodejava.properties` file, read from:

1. The path given as the agent's argument, `-javaagent:joularcodejava-<version>.jar=<path>` (or the argument of `loadAgent`)
2. or the path given with the `-Djoularcodejava.properties=<path>` JVM property
3. or `joularcodejava.properties` in the current working directory

A missing file, or a property left empty, means the default value will be used; a file that was named and is not there is reported with a warning, as is a value that is not valid. [joularcodejava.properties.example](joularcodejava.properties.example) is a commented example template you can use.

### Configuration properties

| Property | Default | Description |
|---|---|---|
| `power-source-type` | `auto` | Where the CPU power comes from: `auto`, `rapl`, `ringbuffer` or `vm` |
| `powerjoular-ringbuffer-path` | OS-dependent | Path to the PowerJoular ring buffer (see below) |
| `vm-power-file` | *(empty)* | In a virtual machine, the file the host writes the power of the guest virtual machine (see below) |
| `vm-power-format` | `powerjoular` | Format of `vm-power-file`: `powerjoular` or `watts` |
| `stack-monitoring-sample-rate` | `10` | Stack sampling interval in milliseconds, from 1 to 1000. Lower = more accurate but higher overhead |
| `max-stack-depth` | `64` | How many frames of each thread's stack are kept, from the newest. A deeper stack loses its oldest frames, so its branch starts lower; a smaller value makes sampling cheaper |
| `results-path` | `joular-code-java-results` | Directory where CSV result files are written, in a sub-folder per run (see [Generated files](#bar_chart-generated-files)) |
| `methods-filtering-prefix` | *(empty)* | Comma-separated list of package/class prefixes to filter app-specific methods (e.g., `com.example,org.myapp`) |
| `lines-monitoring` | `false` | `true` also writes the power per source line, to `lines-power-all.csv`, `lines-power-app.csv` and `lines-power-app-self.csv` (see [Line monitoring](#line-monitoring)) |
| `aggregate` | `false` | `true` writes one row per branch when the JVM stops, with its energy and average power over the whole run, instead of a row per cycle |

### Power sources

With `power-source-type=auto`, the default, the agent reads the virtual machine file when `vm-power-file` is set, else RAPL where there is one (Linux, with read access to it), else the PowerJoular ring buffer. On a Linux machine where RAPL is only readable by root, this is reported with a warning and PowerJoular's ring buffer is read instead of monitoring ending. On Linux, `ringbuffer` keeps the application unprivileged: run `sudo powerjoular -r` and set it explicitly.

#### Linux RAPL (`rapl`)

Reads the energy counters of the CPU packages directly from `/sys/class/powercap/intel-rapl:N`, Intel and AMD alike, adding up every package of a machine with several sockets. Nothing else is needed, but `energy_uj` is only readable by root on most distributions: run the application as root, give its user read access to those files (for example with a udev rule), or run PowerJoular with `-r` as root and set `power-source-type=ringbuffer`. PowerJoular adds up every package too, so `rapl` and `ringbuffer` give the same figure on a machine with several sockets.

```properties
power-source-type=rapl
```

#### PowerJoular ring buffer (`ringbuffer`)

Reads the shared memory area [PowerJoular](https://github.com/joular/powerjoular) writes with `-r`. PowerJoular measures the hardware through [Joular Core](https://github.com/joular/joularcore) (the Energy Meter Interface (EMI) or the PawnIO driver on Windows, RAPL on FreeBSD with the `cpuctl` module and `sudo`, powermetrics on Macs, where PowerJoular has to run with `sudo`, power models on Raspberry Pi), and runs as a process of its own, so the privileges needed to measure the hardware stay out of the Java application. Each monitoring cycle ends as PowerJoular publishes a measurement, so the stack samples and the power describe the same second. PowerJoular may be started before or after the application, and restarted while it runs, except on Windows: a mapped file cannot be deleted there, so PowerJoular has to be started before the application and not restarted while it runs. When PowerJoular cannot read the CPU, it publishes 0 W and no rows are written, without a warning: `powerjoular -d` shows whether it can read the CPU.

Default paths by OS:
- **Linux**: `/dev/shm/powerjoular`
- **macOS**: `/tmp/powerjoular`
- **FreeBSD**: `/tmp/powerjoular`
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
- `watts`: the power alone, in watts, written by any tool. The value is used as it is, however long ago it was written; when the file cannot be read, the last value covers up to two more cycles, after which no energy is attributed until it can be read again.

Some shared folders cache files in the guest (9p with `cache=loose`, virtiofs with `cache=always`), which can hide the host's updates: mount the share without caching.

### Method filtering

The `methods-filtering-prefix` property controls which methods appear in the `methods-power-app.csv` output. Provide one or more comma-separated package or class name prefixes:

```properties
methods-filtering-prefix=com.example,org.myapp
```

- If left empty, all methods (except the agent's own thread) appear in both output files.
- When set, `methods-power-app.csv` only contains branches where at least one stack element matches a prefix. Energy from unmatched (e.g., JDK) frames that are called by a matched method is attributed upward to the matched method.
- `methods-power-all.csv` always contains every observed branch regardless of this setting.

### Line monitoring

When setting `lines-monitoring=true`, energy is also measured by line of code, and written to `lines-power-all.csv` and `lines-power-app.csv`, in the same format and with the same filtering as the methods files. Each call branch is written as the JVM stack trace writes it:

```
1746000000.000,com.example.Main.main(Main.java:12);com.example.Service.process(Service.java:120),2.341500000,2.341500000,1.000000000,1.0000
```

The line of the newest frame is the line being executed, and the line of every other frame is the line calling the next frame. The methods files stay the same.

A third file, `lines-power-app-self.csv`, has one row per line of the application per cycle, whatever it was called from: the newest frame of each branch that matches `methods-filtering-prefix` (the newest of all without a filter), so the line that was running, or that called into the JDK. It is the total of a line itself, where the two other files split it by call branch.

- Thread dumps are taken at safepoints, which the JVM places on loop back edges, calls and returns, so samples gather on those lines: the energy per line is accurate to the loop or the block of statements, not to the single line.
- Classes need line numbers in their debug information, which `javac` and Maven include by default. Without them a branch is written `(Service.java)`, and `(Unknown Source)` when the file is unknown too. Native methods are written `(Native Method)`.
- Lines give more distinct branches than methods, so the files grow faster.

## :bar_chart: Generated Files

Joular Code for Java writes results into the directory configured by `results-path`, in a folder per run, `<pid>-<yyyyMMdd-HHmmss>`, named after the JVM's process id and the time the agent started, so runs never mix. Two CSV files are produced there, five with `lines-monitoring=true`, and appended to during execution; with `aggregate=true`, they get their rows once, when the JVM stops:

| File | Contents |
|---|---|
| `methods-power-all.csv` | Power and energy for all observed call branches, including JDK ones |
| `methods-power-app.csv` | Power and energy for call branches filtered to your application's packages and methods |
| `lines-power-all.csv` | As `methods-power-all.csv`, with the line of each call branch (only with `lines-monitoring=true`) |
| `lines-power-app.csv` | As `methods-power-app.csv`, with the line of each call branch (only with `lines-monitoring=true`) |
| `lines-power-app-self.csv` | The power and energy of each line of your application itself, summed over the branches that reach it (only with `lines-monitoring=true`) |

### CSV format

All files share the same format:

```
Timestamp,branch,power_watts,energy_joules,interval_seconds,coverage
```

| Column | Type | Description |
|---|---|---|
| `Timestamp` | double (s) | Unix time in seconds, with milliseconds, at the end of the monitoring cycle, as PowerJoular writes it |
| `branch` | string | Semicolon-separated call chain from oldest to newest frame (e.g., `com.example.Main.run;com.example.Service.process`) |
| `power_watts` | double | Estimated power consumed by this branch during the cycle (W) |
| `energy_joules` | double | Energy = power_watts × interval_seconds (J) |
| `interval_seconds` | double | Duration of the monitoring cycle (s), typically ~1.0 |
| `coverage` | double | The share of the JVM's CPU time during the cycle that belonged to threads the agent actually sampled, from 0.0 to 1.0 |

With `aggregate=true`, each branch has one row, written when the JVM stops: `power_watts` is its average over the whole run, `energy_joules` its total, `interval_seconds` how long the run was monitored, and `coverage` the average over the run.

#### What `coverage` means

Power is split between threads by the CPU time each one used, and the denominator is every thread that used CPU, not only the ones that were caught in a sample.
Power drawn by a thread the agent never sampled is therefore left unattributed rather than shared out over the threads it did see.
`coverage` says how much of the JVM's CPU time is represented: at `1.0` everything was accounted for, and at `0.6` only 60% of what the JVM consumed are attributed to the observed threads at that timestamp.
A sampled thread that ends during a cycle is charged up to the sample that last saw it, since the JVM stops reporting a thread's CPU time once it has ended. A thread that ends without ever being sampled is invisible here: its power goes to the threads still alive, and `coverage` does not drop.

### Example output

```
Timestamp,branch,power_watts,energy_joules,interval_seconds,coverage
1746000000.000,com.example.Main.main;com.example.Worker.compute,2.341500000,2.341500000,1.000000000,1.0000
1746000001.000,com.example.Main.main;com.example.Worker.compute,2.158300000,2.158300000,1.000000000,0.9974
```

With RAPL, a cycle lasts one second, and the energy is read at its very end, so the power and the stack samples cover the same time. In a virtual machine, a cycle lasts one second too, and gets the latest power the host wrote. With the ring buffer, a cycle ends when PowerJoular publishes a measurement, or after two seconds without one, in which case that cycle gets no rows.

## :information_source: Notes

- Joular Code for Java requires `com.sun.management.OperatingSystemMXBean` to measure process and system CPU load. This is available in all standard HotSpot JVMs (OpenJDK, Oracle JDK). Minimal or embedded JVMs that do not provide this class are not supported.
- Thread CPU time attribution requires `ThreadMXBean.isThreadCpuTimeSupported()` to return `true`. If it does not, Joular Code for Java does not start, and the application runs unmonitored.
- The agent never follows a symbolic link when it opens its results files or the ring buffer, since it may run as root and these often sit in directories every user can write to.
- When the application runs as root, give the configuration file with `-Djoularcodejava.properties` rather than leaving it in a working directory others can write to, since it chooses where the results are written.
- The agent's own monitoring thread is excluded from all energy measurements.
- Power values of `0.0` are suppressed in the output (rows with zero power are not written).
- With RAPL, the cycle running when the JVM stops is written too, over the time it lasted. The other sources publish their power once a second, so that cycle is not written with them, and a program that ends within its first second leaves the files with only their header.
- Virtual threads are not in thread dumps, and the JVM does not report their CPU time: what they run is spent by their carrier threads, and attributed to the carriers' own frames, the `ForkJoinPool` and `VirtualThread` branches of the JDK, not to the application's methods. `coverage` says how much is left unattributed.

## :newspaper: License

Joular Code for Java is licensed under the GNU LGPL 3 license only (LGPL-3.0-only).

Copyright © 2026, Adel Noureddine.
All rights reserved. This program and the accompanying materials are made available under the terms of the [GNU Lesser General Public License v3.0 (LGPL-3.0-only)](https://www.gnu.org/licenses/lgpl-3.0.en.html) which accompanies this distribution.

Author: Prof. Adel Noureddine
