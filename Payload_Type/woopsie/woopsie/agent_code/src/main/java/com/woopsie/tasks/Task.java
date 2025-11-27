package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

/**
 * Base interface for all task handlers
 */
public interface Task {
    /**
     * Execute the task with given parameters
     * @param config Agent configuration
     * @param parameters Task parameters as JSON
     * @return Task output as string
     * @throws Exception if task execution fails
     */
    String execute(Config config, JsonNode parameters) throws Exception;
    
    /**
     * Get the command name this task handles
     * @return Command name
     */
    String getCommandName();
}
