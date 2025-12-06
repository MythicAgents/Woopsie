package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;
import com.woopsie.utils.WindowsAPI;

/**
 * Rev2self task - revert to original security context
 * Windows only
 */
public class Rev2SelfTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing rev2self command");
        
        // Check if we're on Windows
        if (!WindowsAPI.isWindows()) {
            throw new Exception("rev2self is only supported on Windows");
        }
        
        // Revert to original token
        String result = WindowsAPI.revertToSelf();
        
        Config.debugLog(config, "Reverted to original token");
        
        // Get current user context after reverting
        String username = com.woopsie.utils.SystemInfo.getUsername();
        
        // Return JSON with callback field containing empty impersonation_context (matches oopsie format)
        return String.format(
            "{\"user_output\": \"%s\", \"callback\": {\"impersonation_context\": \"\"}}",
            result.replace("\\", "\\\\").replace("\"", "\\\"")
        );
    }
    
    @Override
    public String getCommandName() {
        return "rev2self";
    }
}
