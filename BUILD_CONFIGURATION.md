# Woopsie Build Configuration

The Woopsie agent supports multiple build formats across different platforms using two separate Docker configurations.

## Build Formats

| Format | OS | Output | Size | Notes |
|--------|-----|--------|------|-------|
| **JAR** | Any | `woopsie.jar` | ~5MB | Requires Java 17+ on target |
| **Native (Linux)** | Linux | `woopsie` | ~45MB | Standalone binary, no JVM needed |
| **Native (Windows)** | Windows | `woopsie.exe` | ~45MB | Standalone .exe, no Java needed |

## Docker Configurations

### 1. Dockerfile (Linux/Debian)
**Purpose**: Main Mythic builder for Linux and JAR builds

**Capabilities**:
- ✅ Linux native builds (GraalVM Native Image)
- ✅ JAR builds (any OS)
- ❌ Windows native builds (not supported)

**Build Tools**:
- GraalVM 21 (`/opt/graalvm`)
- Maven 3.9.9 (`/opt/maven`)
- Python 3.11 + mythic_container

**Usage**:
```bash
# Build for Mythic (automatic)
docker build -t woopsie-builder .

# In Mythic UI:
# - Select "native" format → Linux native binary
# - Select "jar" format → Universal JAR
```

### 2. Dockerfile.windows (Windows Server 2022)
**Purpose**: Windows-specific builder for native .exe

**Capabilities**:
- ✅ Windows native builds (GraalVM Native Image)
- ✅ JAR builds
- ❌ Linux builds (wrong platform)

**Build Tools**:
- GraalVM 21 for Windows (`C:\graalvm`)
- Maven 3.9.9 (`C:\maven`)
- Visual Studio Build Tools 2022 (MSVC, Windows SDK)

**Requirements**:
- Windows host (Windows 10/11 or Windows Server)
- Docker Desktop in Windows containers mode

**Usage**:
```powershell
# On Windows host
docker build -f Dockerfile.windows -t woopsie-builder-windows .

# Manual build inside container
docker run -it -v ${PWD}:C:\workspace woopsie-builder-windows
cd C:\workspace\woopsie\agent_code
mvn clean package -Pnative
# Output: target\woopsie.exe
```

## Build Strategy by Platform

### Linux Host (Debian/Ubuntu/Kali)
**What you can build**:
- ✅ Linux native binaries (Dockerfile)
- ✅ JAR files for any OS (Dockerfile)
- ❌ Windows native .exe (need Windows host)

**Recommended approach**:
- Use main `Dockerfile` for all builds
- For Windows targets: deploy JAR (requires Java on target)
- For Linux targets: deploy native binary (no Java needed)

### Windows Host
**What you can build**:
- ✅ Windows native .exe (Dockerfile.windows)
- ✅ JAR files (Dockerfile.windows)
- ❌ Linux native binaries (need Linux host)

**Recommended approach**:
- Use `Dockerfile.windows` for Windows native builds
- Deploy standalone .exe to Windows targets

### Hybrid Setup (CI/CD)
**Best practice for complete coverage**:
1. **Linux CI runner**: Build Linux native + JAR
2. **Windows CI runner**: Build Windows native .exe
3. Store all artifacts in release repository

## GraalVM Native Image

### Why Native Image?
- ✅ **No JVM required** on target system
- ✅ **Fast startup** (<100ms vs seconds)
- ✅ **Lower memory** footprint
- ✅ **Single executable** file
- ✅ **Harder to decompile** than JAR

### Build Requirements

#### Linux
- GraalVM JDK 21
- `gcc`, `glibc-dev`, `zlib-dev`
- Maven with native-maven-plugin

#### Windows
- GraalVM JDK 21 for Windows
- Visual Studio Build Tools 2022
  - MSVC v143 compiler
  - Windows 10/11 SDK
  - C++ build tools
- Maven 3.9+

### Build Process
```bash
# Linux
mvn clean package -Pnative

# Windows (in PowerShell)
mvn clean package -Pnative

# Both create standalone executables:
# - Linux: target/woopsie
# - Windows: target\woopsie.exe
```

## Configuration Files

### pom.xml
Maven build configuration with:
- Dependencies (Jackson, HttpClient, Logback)
- `maven-shade-plugin` for JAR builds
- `native-maven-plugin` for GraalVM builds
- Profile `native` for Native Image compilation

### Native Image Configuration
Located in: `agent_code/src/main/resources/META-INF/native-image/`

- **reflect-config.json**: Reflection configuration
- **resource-config.json**: Resource bundle inclusion
- **serialization-config.json**: Serialization support

These files tell GraalVM which classes need reflection/serialization at runtime.

## Testing Builds Locally

### Linux Native Build
```bash
cd woopsie/Payload_Type/woopsie/woopsie/agent_code
mvn clean package -Pnative
./target/woopsie
```

### JAR Build
```bash
cd woopsie/Payload_Type/woopsie/woopsie/agent_code
mvn clean package
java -jar target/woopsie.jar
```

### Windows Native Build (Windows host only)
```powershell
cd woopsie\Payload_Type\woopsie\woopsie\agent_code
mvn clean package -Pnative
.\target\woopsie.exe
```

## Mythic Integration

### builder.py Logic
```python
if output_format == "native":
    if selected_os == "Linux":
        # Use GraalVM Native Image (this container)
        build_result = run_native_build()
    elif selected_os == "Windows":
        # Fallback to JAR (Windows native requires Windows container)
        build_result = run_maven_build()  # Creates JAR
```

### To Support Windows Native in Mythic
You would need to:
1. Deploy Mythic on Windows Server
2. Use `Dockerfile.windows` instead of `Dockerfile`
3. Ensure Visual Studio Build Tools are installed
4. Builder will then produce native Windows .exe files

## Size Comparison

```
Format          OS        Size    Startup Time    Memory
------          --        ----    ------------    ------
JAR             Any       5 MB    ~2-3 seconds    ~100 MB
Linux Native    Linux    45 MB    ~50 ms          ~20 MB
Windows Native  Windows  45 MB    ~50 ms          ~20 MB
```

## Current Limitations

### Linux Dockerfile (Current)
- ❌ Cannot cross-compile to Windows native
- ❌ MinGW can't help (GraalVM needs real Windows SDK)
- ✅ Can build JAR for Windows (requires Java on target)

### Solution
- Maintain two Dockerfiles
- Linux host: Use `Dockerfile` (Linux native + JAR)
- Windows host: Use `Dockerfile.windows` (Windows native .exe)

## Future Improvements

1. **CI/CD Pipeline**: Auto-build both Linux and Windows binaries
2. **Multi-arch**: Add ARM64 support (Linux ARM, Windows ARM)
3. **Obfuscation**: Apply ProGuard before native compilation
4. **Size Optimization**: Use UPX to compress native binaries
5. **Self-extracting**: Create single-file deployment with embedded config
