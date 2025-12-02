package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Represents a background task that runs in its own thread
 * Similar to oopsie's BackgroundTask with channels
 */
public class BackgroundTask {
    public final String taskId;  // Make public for background task access
    public final String parameters;  // Make public for background task access
    private final String command;
    private final Thread thread;
    private final BlockingQueue<JsonNode> toTask;      // Main -> Task (like oopsie's job_rx)
    private final BlockingQueue<Map<String, Object>> fromTask;  // Task -> Main (like oopsie's job_tx)
    private final BlockingQueue<Map<String, Object>> interactiveMessages;  // Interactive messages (PTY, SOCKS)
    public volatile boolean running;  // Make public for background task access
    
    public BackgroundTask(String taskId, String command, String parameters, Runnable taskRunnable) {
        this.taskId = taskId;
        this.command = command;
        this.parameters = parameters;
        this.toTask = new LinkedBlockingQueue<>();
        this.fromTask = new LinkedBlockingQueue<>();
        this.interactiveMessages = new LinkedBlockingQueue<>();
        this.running = true;
        this.thread = new Thread(taskRunnable);
        this.thread.setDaemon(true);
        this.thread.setName("BackgroundTask-" + command + "-" + taskId);
    }
    
    public void start() {
        thread.start();
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public String getCommand() {
        return command;
    }
    
    public String getParameters() {
        return parameters;
    }
    
    /**
     * Send a message to the background task (Main -> Task)
     */
    public void sendToTask(JsonNode message) {
        try {
            toTask.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Receive a message from the background task (Task -> Main)
     * Blocks until a message is available
     */
    public JsonNode receiveFromTask() throws InterruptedException {
        return toTask.take();
    }
    
    /**
     * Send a response from the task to the main agent (Task -> Main)
     */
    public void sendResponse(Map<String, Object> response) {
        try {
            fromTask.put(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Send an interactive message (PTY, SOCKS)
     * Interactive messages go at the top level of post_response, not inside responses array
     */
    public void sendInteractive(Map<String, Object> interactive) {
        try {
            interactiveMessages.put(interactive);
            System.out.println("[BackgroundTask] Queued interactive message for task " + taskId + ": " + interactive);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Poll for responses from the task (non-blocking)
     */
    public Map<String, Object> pollResponse() {
        return fromTask.poll();
    }
    
    /**
     * Poll for interactive messages (non-blocking)
     */
    public Map<String, Object> pollInteractive() {
        Map<String, Object> msg = interactiveMessages.poll();
        if (msg != null) {
            System.out.println("[BackgroundTask] Polled interactive message from task " + taskId + ": " + msg);
        }
        return msg;
    }
    
    /**
     * Check if the task thread is still alive
     */
    public boolean isAlive() {
        return thread.isAlive() && running;
    }
    
    /**
     * Stop the background task
     */
    public void stop() {
        running = false;
        thread.interrupt();
    }
    
    public BlockingQueue<JsonNode> getToTaskQueue() {
        return toTask;
    }
    
    public BlockingQueue<Map<String, Object>> getFromTaskQueue() {
        return fromTask;
    }
}
