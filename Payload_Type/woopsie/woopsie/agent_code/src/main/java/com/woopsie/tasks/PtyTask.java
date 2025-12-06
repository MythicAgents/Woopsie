package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * PTY task - spawns a pseudo-terminal for interactive shell access
 * This task should never be executed - PTY is handled entirely by PtyBackgroundTask
 * The Agent detects "pty" command and starts the background task directly
 */
public class PtyTask implements Task {
    
    @Override
    public String getCommandName() {
        return "pty";
    }
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "PtyTask.execute() called - this should not happen!");
        
        // This should never actually be called - pty is handled as a background task
        // The Agent detects pty commands and starts the background task directly
        throw new Exception("PTY must be run as background task");
    }
}
