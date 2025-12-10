<p align="center">
  <img alt="Woopsie Logo" src="agent_icons/woopsie.svg" height="25%" width="25%">
</p>

# Woopsie

Woopsie is a cross-platform C2 agent for the Mythic framework, written in Java. It supports both JAR and native executable compilation using GraalVM Native Image.

## Features

- **Cross-Platform**: Works on Windows, Linux, and macOS
- **Dual Build Modes**:
  - **JAR**: Traditional Java bytecode requiring JVM (portable, all platforms)
  - **Native**: GraalVM Native Image compilation (no JVM required, experimental Windows support via remote build)
- **Multiple C2 Profiles**:
  - HTTP
  - HTTPX
  - WebSocket
- **Mythic Integration**: Full integration with Mythic C2 framework
- **Task Execution**: Extensible task framework with built-in commands

## Build Modes

### JAR Build (Recommended for Windows/macOS)
- Output: `woopsie.jar` (~5.2MB with all dependencies)
- Requires: Java 17+ JVM on target system
- Benefits: Works on all platforms, smaller build times, easier debugging
- Drawbacks: Requires JVM installation on target

### Native Build
- Output: `woopsie.bin` native executable (Linux) or `woopsie.exe` (Windows via remote build)
- Requires: GraalVM Native Image toolchain
- Benefits: No JVM required, smaller runtime footprint, better OPSEC
- Drawbacks: Longer build times (~2-5 minutes), Windows requires remote build VM

### Native Build, remote Windows build, pre-requisites
Install OpenSSH Server on Windows (for remote builds):
```
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service -Name sshd -StartupType 'Automatic'
if (!(Get-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
}
```

Install GraalVM:
```
# Download GraalVM 25 from: https://www.graalvm.org/downloads/
# Select: Java 25, Windows, x64

# After downloading, extract to C:\Program Files\
# Then set environment variables:

setx /M JAVA_HOME "C:\Progra~1\graalvm-jdk-25.0.1+8.1"

# Add to PATH:
C:\Program Files\graalvm-jdk-25.0.1+8.1\bin
```

Install Maven:
```
# Download latest Maven from: https://maven.apache.org/download.cgi
# Example: apache-maven-3.9.11-bin.zip

# Extract to C:\Program Files\
# Then configure environment variables:

setx /M M2_HOME "C:\Progra~1\apache-maven-3.9.11"

# Add to PATH:
C:\Program Files\apache-maven-3.9.11\bin
```

Install Visual Studio Build Tools (required for Native Image):
```
# Download Visual Studio Build Tools 2022 from visualstudio.microsoft.com
# Select Desktop development with C++
# Ensure Windows 11 SDK and MSVC C++ x64/x86 build tools are selected
# Click Install
```

## Quick Start

### Building Locally

**JAR Build:**
```bash
cd Payload_Type/woopsie/agent_code
mvn clean package
```

**Native Build (Linux only):**
```bash
# Requires GraalVM with native-image installed
cd Payload_Type/woopsie/agent_code
mvn clean package -Pnative
```

### Building with Mythic

1. Install woopsie in Mythic:
```bash
cd /path/to/Mythic
sudo ./mythic-cli install github https://github.com/haha150/woopsie
```

2. Create payload via Mythic UI:
   - Select "woopsie" payload type
   - Choose output format: "jar" or "native"
   - Configure C2 profile:
     - HTTP
     - HTTPX
   - Set callback parameters (host, port, sleep interval, jitter)
   - For native Windows builds, configure remote build VM (host, username, password)

3. Download and deploy payload

## Commands

Woopsie supports the following built-in commands:

| Command | Description | Platforms |
|---------|-------------|-----------|
| `cat <file>` | Display file contents | All |
| `cd <path>` | Change working directory | All |
| `coff_loader` | Execute Beacon Object Files (BOF) | Windows |
| `cp <source> <dest>` | Copy file or directory | All |
| `download <path>` | Download file from target | All |
| `exit` | Terminate agent | All |
| `ls [path]` | List directory contents | All |
| `make_token <domain\user> <password>` | Create token with credentials | Windows |
| `mkdir <path>` | Create directory | All |
| `ps` | List running processes | All |
| `pty` | Interactive pseudo-terminal | All |
| `pwd` | Print working directory | All |
| `rev2self` | Revert to original token | Windows |
| `rm <path>` | Remove file or directory | All |
| `run <command>` | Execute shell command | All |
| `screenshot` | Capture screenshot | All |
| `sleep <interval> <jitter>` | Configure agent sleep/jitter | All |
| `socks` | Start SOCKS proxy | All |
| `steal_token <pid>` | Steal token from process | Windows |
| `upload` | Upload file to target | All |
| `whoami` | Display current user info | All |

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
│   │       ├── reflect-config.json # Reflection metadata
│   │       └── proxy-config.json   # Dynamic proxy config
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
### C2 Profiles
- **HttpProfile.java**: Basic HTTP/HTTPS communication
- **HttpxProfile.java**: Advanced HTTP profile supporting:
  - Transform chains (XOR, Base64/Base64URL, prepend/append)
  - Domain rotation (round-robin, random, fail-over)
  - Multiple URIs for endpoint randomization
  - Smart JavaScript obfuscation handling
- **WebSocketProfile.java**: Persistent WebSocket communication:
  - Bi-directional persistent connection (ws:// or wss://)
  - JSON message format: `{"data":"<base64>"}`
  - Automatic reconnection on connection loss
  - Ping/pong handling for connection keepalive

### builder.py
Mythic payload builder that:
- Generates `PayloadVars.java` with C2 configuration
### Runtime
- Jackson 2.20.1: JSON processing
- Apache HttpClient 5.4.1: HTTP/HTTPS communication
- Java-WebSocket 1.6.0: WebSocket client
- Logback 1.5.16 + SLF4J 2.0.16: Logging
- JNA 5.15.0: Native library access (for BOF execution)
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
- GraalVM 25 (for native builds)
- Docker (for Mythic integration)

## Configuration

### Build-time (via Mythic)

**Common Parameters:**
- `UUID`: Unique payload identifier
- `CALLBACK_INTERVAL`: Sleep interval in seconds
- `CALLBACK_JITTER`: Jitter percentage (0-100)
- `KILLDATE`: Payload expiration date
- `DEBUG`: Enable verbose logging
- `ENCRYPTED_EXCHANGE_CHECK`: Validate encrypted key exchange
- `AESPSK`: AES encryption keys (generated by Mythic)

**HTTP Profile:**
- `CALLBACK_HOST`: C2 server URL
- `CALLBACK_PORT`: C2 server port
- `POST_URI`: Endpoint URI
- `HEADERS`: HTTP headers (JSON)

**HTTPX Profile:**
- `CALLBACK_DOMAINS`: List of C2 domains (JSON array)
- `DOMAIN_ROTATION`: Strategy (round-robin, random, fail-over)
- `FAILOVER_THRESHOLD`: Retry attempts before switching domains

**WebSocket Profile:**
- `CALLBACK_HOST`: WebSocket server URL (supports ws:// and wss://)
- `CALLBACK_PORT`: WebSocket server port
- `POST_URI`: WebSocket endpoint path
- `HEADERS`: HTTP headers for WebSocket handshake (JSON)

### Windows Native Builds
Native Windows builds are supported via SSH remote build to a Windows VM with Maven and GraalVM installed. Configure in Mythic:
- `windows_build_host`: Windows VM IP/hostname
- `windows_build_user`: SSH username
- `windows_build_pass`: SSH password

The builder uploads source code, executes Maven native build remotely, and retrieves the `.exe` artifact.
**Development fallback (command-line):**
```bash
java -jar woopsie.jar
```

### Native Build Fails
- Verify GraalVM installation: `native-image --version`
- Check reflection config includes all dynamic classes
- Try verbose build: `mvn -Pnative package -X`
- Ensure GraalVM experimental options are unlocked (handled in pom.xml)

### ClassNotFoundException at Runtime (Native)
- Missing class in `reflect-config.json`
- Use GraalVM tracing agent to capture reflection usage:
```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar target/woopsie.jar
```

### Remote Windows Build Fails
- Verify SSH connectivity: `ssh user@windows_host`
- Ensure Maven and GraalVM are installed on Windows VM
- Check Windows VM has sufficient disk space (~2GB for build)
- Review Mythic build logs for detailed error messages

## Security Considerations

- **Native builds** have better OPSEC (no visible Java dependencies)
- **Payloads contain embedded C2 configuration** - protect build artifacts
- **HTTPX profile** supports transform chains for obfuscation (XOR, Base64, prepend/append)
- **Domain rotation** provides resilience against domain takedowns
- **Customize User-Agent** for target environment blending
- **Use appropriate sleep interval and jitter** to avoid detection patterns
## Development

### Requirements
- Java 17 or later
- Apache Maven 3.9.11
- Mythic framework 3.4.6+
- GraalVM 25 (for native builds)
- Python 3.11+ with asyncssh, aiofiles (for Mythic integration)

### Building/Testing Locally

**Compile and test:**
```bash
cd Payload_Type/woopsie/agent_code
mvn clean compile
```

**Update dependencies:**
```bash
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates
```

**Build with specific profile:**
```bash
# HTTP profile
PROFILE=http UUID=test-uuid CALLBACK_HOST=http://127.0.0.1 mvn clean package

# HTTPX profile with transforms
PROFILE=httpx RAW_C2_CONFIG='{"post": {...}}' mvn clean package
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
- Apache Maven 3.9.11
- Mythic framework 3.4.6+
- GraalVM 25 (for native builds)

## License

See LICENSE file for details.

## Credits

- Author: @haha150
- Framework: [Mythic C2](https://github.com/its-a-feature/Mythic)
- GraalVM: [Oracle GraalVM](https://www.graalvm.org/)
