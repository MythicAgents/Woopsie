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
    
    public UploadBackgroundTask(BackgroundTask task, Config config, String fullPath, String fileId) {
        this.task = task;
        this.config = config;
        this.fullPath = fullPath;
        this.fileId = fileId;
    }
    
    @Override
    public void run() {
        try {
            Config.debugLog(config, "Upload background task started for: " + fullPath);
            
            Path filePath = Paths.get(fullPath);
            
            // Create FileOutputStream to write chunks
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                
                int chunkNum = 1;
                int totalChunks = -1; // Will be set from first message
                
                while (task.running) {
                    // Wait for chunk data from Mythic
                    JsonNode message = task.receiveFromTask();
                    
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
                    Config.debugLog(config, "Wrote chunk " + chunkNum + " (" + chunkData.length + " bytes)");
                    
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
                    ackResponse.put("user_output", "Uploading chunk " + chunkNum + (totalChunks > 0 ? "/" + totalChunks : "") + "\n");
                    
                    task.sendResponse(ackResponse);
                }
                
                fos.flush();
            }
            
            // Send completion message
            Map<String, Object> completionResponse = new HashMap<>();
            completionResponse.put("task_id", task.taskId);
            completionResponse.put("completed", true);
            completionResponse.put("status", "success");
            completionResponse.put("user_output", "Uploaded '" + fullPath + "'");
            
            task.sendResponse(completionResponse);
            Config.debugLog(config, "Upload background task completed successfully");
            
        } catch (Exception e) {
            Config.debugLog(config, "Upload background task error: " + e.getMessage());
            e.printStackTrace();
            
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
