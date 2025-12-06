package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Make directory task
 */
public class MkdirTask implements Task {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract path parameter
        String path = parameters.has("path") ? parameters.get("path").asText() : "";
        
        if (path.isEmpty()) {
            throw new Exception("path parameter is required");
        }
        
        Config.debugLog(config, "Mkdir request - path: '" + path + "'");
        
        // Resolve path
        boolean isUncPath = path.startsWith("\\\\");
        
        // Fix malformed UNC paths from JSON parsing
        if (!isUncPath && path.startsWith("\\") && path.length() > 2 && path.charAt(1) != ':') {
            path = "\\" + path;
            isUncPath = true;
            Config.debugLog(config, "Fixed malformed UNC path: " + path);
        }
        
        Path dirPath = Paths.get(path);
        if (!dirPath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            dirPath = Paths.get(cwd).resolve(path);
            Config.debugLog(config, "Resolved relative to CWD: " + dirPath);
        }
        
        Config.debugLog(config, "Creating directory: " + dirPath);
        
        // Create directory (including parent directories if needed)
        Files.createDirectories(dirPath);
        
        // Get real path after creation
        Path realPath = dirPath.toRealPath();
        Config.debugLog(config, "Directory created: " + realPath);
        
        // Return success message
        return "Created directory '" + realPath.toString() + "'";
    }
    
    @Override
    public String getCommandName() {
        return "mkdir";
    }
}
