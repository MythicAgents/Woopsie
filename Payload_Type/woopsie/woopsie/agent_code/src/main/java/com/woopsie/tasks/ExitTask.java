package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * Exit agent task
 */
public class ExitTask implements Task {
    
    private volatile boolean exitFlag = false;
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Exit command received - setting exit flag");
        exitFlag = true;
        return "Exiting agent...";
    }
    
    @Override
    public String getCommandName() {
        return "exit";
    }
    
    public boolean shouldExit() {
        return exitFlag;
    }
}
