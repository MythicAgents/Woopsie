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
        
        // Clear the stored impersonation token
        com.woopsie.Agent.clearImpersonationToken();
        
        // Revert to original token
        String result = WindowsAPI.revertToSelf();
        
        Config.debugLog(config, "Reverted to original token");
        
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
