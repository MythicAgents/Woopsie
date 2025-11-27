package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * Print working directory task
 */
public class PwdTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing pwd command");
        String pwd = System.getProperty("user.dir");
        Config.debugLog(config, "Current directory: " + pwd);
        return pwd;
    }
    
    @Override
    public String getCommandName() {
        return "pwd";
    }
}
