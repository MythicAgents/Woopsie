# Windows EXE Build with Bundled JRE

## Overview

The woopsie agent supports creating standalone Windows executables that include a bundled JRE, eliminating the need for Java to be installed on target systems.

## Build Strategy

### Linux Native Builds
- Uses **GraalVM 21 Native Image**
- Creates standalone 45MB binary
- No JVM required at runtime
- Build command: `mvn clean package -Pnative`

### Windows EXE Builds
- Uses **Launch4j 3.50** to wrap JAR into EXE
- Bundles **Adoptium JRE 17** for Windows x64
- Creates ~50-80MB standalone package
- No Java installation required on target
- Build approach:
  1. Compile JAR with Maven
  2. Copy bundled JRE to distribution directory
  3. Use Launch4j to create EXE that references `./jre`
  4. Package EXE + JRE directory together

## Docker Build Container

The single Debian-based Docker container includes:
- **GraalVM 21** (`/opt/graalvm`) - For Linux native builds
- **Maven 3.9.9** (`/opt/maven`) - Java build tool
- **Adoptium JRE 17 Windows x64** (`/opt/jre-windows/jre`) - Bundled JRE
- **Launch4j 3.50** (`/opt/launch4j`) - JAR to EXE wrapper
- **MinGW-w64** - Windows cross-compilation toolchain

## Build Process

### Through Mythic
1. Select payload format: `native`
2. Select OS: `Windows` or `Linux`
3. Mythic calls `builder.py` with `selected_os` parameter
4. Builder branches based on OS:
   - **Windows**: JAR build → Launch4j EXE creation with bundled JRE
   - **Linux**: GraalVM Native Image compilation

### Manual Testing (Host)

#### Build JAR
```bash
cd /home/debian/git/woopsie/Payload_Type/woopsie/woopsie/agent_code
mvn clean package
# Output: target/woopsie.jar (5.2MB)
```

#### Build Linux Native
```bash
cd /home/debian/git/woopsie/Payload_Type/woopsie/woopsie/agent_code
mvn clean package -Pnative
# Output: target/woopsie (45MB standalone binary)
```

#### Test Linux Native
```bash
./target/woopsie
# Should see: "Woopsie agent initialized..."
```

## Windows EXE Structure

After Windows build, the output directory structure will be:

```
target/woopsie-windows/
├── woopsie.exe          # Launch4j wrapped executable (small, ~1-2MB)
└── jre/                 # Bundled Adoptium JRE 17 for Windows
    ├── bin/
    │   ├── java.exe
    │   ├── javaw.exe
    │   └── ...
    ├── conf/
    ├── legal/
    └── lib/
```

**Important**: Both `woopsie.exe` and the `jre/` directory must be deployed together to the target system. The EXE will not run without the `./jre` directory in the same folder.

## Launch4j Configuration

The `create_windows_exe_bundled()` method generates this XML config:

```xml
<?xml version='1.0' encoding='utf-8'?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>console</headerType>
  <jar>/absolute/path/to/woopsie.jar</jar>
  <outfile>/absolute/path/to/woopsie.exe</outfile>
  <errTitle>Woopsie Agent Error</errTitle>
  <cmdLine></cmdLine>
  <chdir>.</chdir>
  <priority>normal</priority>
  <stayAlive>false</stayAlive>
  <restartOnCrash>false</restartOnCrash>
  
  <jre>
    <path>./jre</path>
    <bundledJre64Bit>true</bundledJre64Bit>
    <bundledJreAsFallback>false</bundledJreAsFallback>
    <minVersion>17</minVersion>
    <jdkPreference>preferJre</jdkPreference>
    <runtimeBits>64</runtimeBits>
  </jre>
</launch4jConfig>
```

Key settings:
- `<path>./jre</path>`: Relative path to bundled JRE
- `<bundledJre64Bit>true</bundledJre64Bit>`: Use bundled JRE
- `<bundledJreAsFallback>false</bundledJreAsFallback>`: Only use bundled JRE, don't search system

## File Sizes

| Output Type | Size | Description |
|-------------|------|-------------|
| JAR | ~5.2MB | Requires Java 17+ on target |
| Linux Native | ~45MB | Standalone, no JVM required |
| Windows EXE (launcher) | ~1-2MB | Launch4j wrapper only |
| Windows JRE (bundled) | ~50-80MB | Full JRE 17 for Windows |
| **Windows Total** | **~52-82MB** | EXE + bundled JRE directory |

## Builder Implementation

### Method: `create_windows_exe_bundled()`

Located in: `/home/debian/git/woopsie/Payload_Type/woopsie/woopsie/mythic/agent_functions/builder.py`

Steps:
1. Create `target/windows_build/` directory
2. Copy JAR to build directory
3. Copy `/opt/jre-windows/jre` to `target/windows_build/jre`
4. Generate Launch4j XML config with bundled JRE settings
5. Execute `/opt/launch4j/launch4j <config.xml>`
6. Create distribution directory: `target/woopsie-windows/`
7. Copy EXE and JRE directory to distribution directory
8. Return path to `woopsie-windows/woopsie.exe`

### Build Flow

```
Windows Native Build Request
    ↓
run_maven_build() → woopsie.jar
    ↓
create_windows_exe_bundled()
    ↓
    ├─ Copy JAR to build dir
    ├─ Copy /opt/jre-windows/jre to build dir
    ├─ Generate Launch4j XML config
    ├─ Execute Launch4j → woopsie.exe
    ├─ Create distribution directory
    └─ Package EXE + JRE together
    ↓
Return: target/woopsie-windows/woopsie.exe
```

## Testing Windows Build

### In Docker Container

```bash
# Build Docker image
cd /home/debian/git/woopsie/Payload_Type/woopsie
docker build -t woopsie-builder .

# Run container
docker run -it --rm -v $(pwd):/workspace woopsie-builder bash

# Inside container - test Windows build manually
cd /workspace/woopsie/agent_code
mvn clean package  # Build JAR first

# Run builder script (simulates Mythic build)
python3 /workspace/woopsie/mythic/agent_functions/builder.py
```

### On Windows Target

1. Copy entire `woopsie-windows/` directory to Windows machine
2. Navigate to directory containing `woopsie.exe` and `jre/` folder
3. Run: `woopsie.exe`
4. Agent should start and attempt C2 connection

## Advantages

✅ **No Java Installation Required**: Bundled JRE eliminates dependency  
✅ **Single Build Container**: One Dockerfile for both Linux and Windows  
✅ **Mythic Compatible**: Works with standard Mythic payload builder interface  
✅ **Portable**: Entire package can be deployed as-is  
✅ **Professional**: Looks like native Windows executable  

## Trade-offs

⚠️ **Size**: Windows package is larger (~52-82MB vs 45MB Linux native)  
⚠️ **JRE Dependency**: Must deploy `jre/` directory alongside EXE  
⚠️ **Performance**: JVM startup overhead vs native binary  
⚠️ **Detection**: JRE processes may be more visible than native binaries  

## Future Improvements

- Use `jlink` to create minimal JRE (reduce from ~50MB to ~20-30MB)
- Strip unused JRE modules (java.sql, java.xml, etc.)
- Implement single-file deployment (embed JRE in EXE with custom extractor)
- Add obfuscation to JAR before wrapping
- Support ARM64 Windows builds
