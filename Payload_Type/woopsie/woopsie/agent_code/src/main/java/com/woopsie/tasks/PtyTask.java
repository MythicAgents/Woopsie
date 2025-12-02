package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * PTY task - spawns a pseudo-terminal for interactive shell access
 * This is a background task that uses interactive messages
 */
public class PtyTask implements Task {
    
    @Override
    public String getCommandName() {
        return "pty";
    }
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "PTY task executing with parameters: " + parameters);
        
        // Return the JSON output for background task initialization
        // The output format must match Mythic's expectations for background tasks
        return "";
    }
}
