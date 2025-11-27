# Woopsie Java Agent

Java-based C2 agent for Mythic framework.

## Project Structure

```
agent_code/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/woopsie/
│   │   │       ├── Agent.java              # Main agent class
│   │   │       ├── Config.java             # Configuration management
│   │   │       ├── TaskManager.java        # Task execution handler
│   │   │       └── CommunicationHandler.java # C2 communication
│   │   └── resources/
│   │       └── logback.xml                 # Logging configuration
│   └── test/
│       └── java/
│           └── com/woopsie/
└── pom.xml                                 # Maven build configuration

mythic/                                     # Python Mythic integration (TODO)
```

## Building

```bash
cd agent_code
mvn clean package
```

This will create `target/woopsie.jar` - a fully self-contained JAR with all dependencies.

## Running

```bash
java -jar target/woopsie.jar callback_host=192.168.1.100 callback_port=80 sleep=5000
```

## Dependencies

- Java 17+
- Apache Maven 3.8+
- Jackson (JSON processing)
- Apache HttpClient 5 (HTTP communication)
- Logback (Logging)

## Development

The project uses:
- **Maven** for build management
- **Shade plugin** for creating uber-JAR with all dependencies
- **SLF4J/Logback** for logging
- **Jackson** for JSON serialization
- **HttpClient 5** for HTTP/HTTPS communication

## Next Steps

1. Implement Mythic Python integration in `mythic/` directory
2. Add more task handlers (file operations, process management, etc.)
3. Implement proper JSON protocol for Mythic communication
4. Add encryption/obfuscation
5. Create builder script for Mythic integration
