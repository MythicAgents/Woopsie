package com.woopsie.utils;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    
    // Token information classes
    public static final int TokenUser = 1;
    public static final int TokenIntegrityLevel = 25;
    
    /**
     * SID_AND_ATTRIBUTES structure
     */
    public static class SID_AND_ATTRIBUTES extends Structure {
        public Pointer Sid;  // PSID is a pointer type, not a structure
        public int Attributes;
        
        public SID_AND_ATTRIBUTES() {
            super();
        }
        
        public SID_AND_ATTRIBUTES(Pointer p) {
            super(p);
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("Sid", "Attributes");
        }
    }
    
    /**
     * TOKEN_USER structure
     */
    public static class TOKEN_USER extends Structure {
        public SID_AND_ATTRIBUTES User = new SID_AND_ATTRIBUTES();
        
        public TOKEN_USER() {
            super();
        }
        
        public TOKEN_USER(Pointer p) {
            super(p);
            read();
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("User");
        }
    }
    
    /**
     * TOKEN_MANDATORY_LABEL structure for integrity level
     */
    public static class TOKEN_MANDATORY_LABEL extends Structure {
        public SID_AND_ATTRIBUTES Label = new SID_AND_ATTRIBUTES();
        
        public TOKEN_MANDATORY_LABEL() {
            super();
        }
        
        public TOKEN_MANDATORY_LABEL(Pointer p) {
            super(p);
            read();
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("Label");
        }
    }
    
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
        
        boolean OpenThreadToken(
            WinNT.HANDLE threadHandle,
            int desiredAccess,
            boolean openAsSelf,
            WinNT.HANDLEByReference tokenHandle
        );
        
        boolean GetTokenInformation(
            WinNT.HANDLE tokenHandle,
            int tokenInformationClass,
            Pointer tokenInformation,
            int tokenInformationLength,
            Pointer returnLength
        );
        
        boolean LookupAccountSidW(
            String systemName,
            Pointer sid,
            char[] name,
            Pointer nameLen,
            char[] referencedDomainName,
            Pointer referencedDomainNameLen,
            Pointer sidNameUse
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
        
        Pointer GetSidSubAuthorityCount(Pointer pSid);
        
        Pointer GetSidSubAuthority(Pointer pSid, int nSubAuthority);
    }
    
    public static class PROCESSENTRY32 extends Structure {
        public int dwSize;
        public int cntUsage;
        public int th32ProcessID;
        public Pointer th32DefaultHeapID;
        public int th32ModuleID;
        public int cntThreads;
        public int th32ParentProcessID;
        public int pcPriClassBase;
        public int dwFlags;
        public byte[] szExeFile = new byte[260];
        
        public PROCESSENTRY32() {
            super(ALIGN_DEFAULT);
            dwSize = size();
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID",
                "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase",
                "dwFlags", "szExeFile");
        }
    }
    
    public interface Kernel32Ext extends StdCallLibrary {
        Kernel32Ext INSTANCE = loadKernel32();
        
        int TH32CS_SNAPPROCESS = 0x00000002;
        
        WinNT.HANDLE CreateToolhelp32Snapshot(int dwFlags, int th32ProcessID);
        
        boolean Process32First(WinNT.HANDLE hSnapshot, PROCESSENTRY32 lppe);
        
        boolean Process32Next(WinNT.HANDLE hSnapshot, PROCESSENTRY32 lppe);
        
        WinNT.HANDLE OpenProcess(
            int dwDesiredAccess,
            boolean bInheritHandle,
            int dwProcessId
        );
        
        boolean CloseHandle(WinNT.HANDLE handle);
        
        WinNT.HANDLE GetCurrentThread();
        
        WinNT.HANDLE GetCurrentProcess();
        
        int GetLastError();
    }
    
    /**
     * Steal a token from a process (returns token handle)
     * @param pid Process ID to steal token from
     * @return TokenResult containing the token handle and success message
     * @throws Exception if token stealing fails
     */
    public static TokenResult stealTokenWithHandle(int pid) throws Exception {
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
            
            // Clean up process token (keep impersonation token active - we'll return it)
            Kernel32Ext.INSTANCE.CloseHandle(processToken);
            
            // Get the new user context by checking thread token
            String currentUser = getImpersonatedUsername();
            
            return new TokenResult(impersonationToken, String.format("Successfully impersonated %s from PID %d", currentUser, pid));
            
        } finally {
            // Clean up process handle
            if (processHandle != null && !WinBase.INVALID_HANDLE_VALUE.equals(processHandle)) {
                Kernel32Ext.INSTANCE.CloseHandle(processHandle);
            }
        }
    }
    
    /**
     * Result of makeToken operation containing both the token and message
     */
    public static class TokenResult {
        public final WinNT.HANDLE token;
        public final String message;
        
        public TokenResult(WinNT.HANDLE token, String message) {
            this.token = token;
            this.message = message;
        }
    }
    
    /**
     * Create a new logon session and impersonate the user (returns token handle)
     * @param username Username
     * @param password Password
     * @param domain Domain (use "." for local)
     * @param netOnly If true, uses LOGON32_LOGON_NEW_CREDENTIALS (network only), otherwise LOGON32_LOGON_INTERACTIVE
     * @return TokenResult containing the token handle and success message
     * @throws Exception if token creation fails
     */
    public static TokenResult makeTokenWithHandle(String username, String password, String domain, boolean netOnly) throws Exception {
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
        
        System.err.println("[DEBUG] makeToken: LogonUserW parameters:");
        System.err.println("[DEBUG]   username: " + username);
        System.err.println("[DEBUG]   domain: " + domain);
        System.err.println("[DEBUG]   logonType: " + logonType + (netOnly ? " (NEW_CREDENTIALS)" : " (INTERACTIVE)"));
        System.err.println("[DEBUG]   logonProvider: " + logonProvider);
        
        // Log on user
        if (!Advapi32Ext.INSTANCE.LogonUserW(
                usernameW,
                domainW,
                passwordW,
                logonType,
                logonProvider,
                tokenRef)) {
            int error = Kernel32Ext.INSTANCE.GetLastError();
            System.err.println("[DEBUG] makeToken: LogonUserW FAILED with error: " + error);
            throw new Exception("Failed to log on user. Error: " + error);
        }
        System.err.println("[DEBUG] makeToken: LogonUserW SUCCEEDED");
        
        WinNT.HANDLE token = tokenRef.getValue();
        System.err.println("[DEBUG] makeToken: Token handle: " + token);
        
        try {
            // Revert any existing impersonation before applying the new one
            System.err.println("[DEBUG] makeToken: Calling RevertToSelf()");
            if (!Advapi32Ext.INSTANCE.RevertToSelf()) {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                System.err.println("[DEBUG] makeToken: RevertToSelf() failed with error: " + error);
                Kernel32Ext.INSTANCE.CloseHandle(token);
                throw new Exception("Failed to revert to self. Error: " + error);
            }
            System.err.println("[DEBUG] makeToken: RevertToSelf() succeeded");
            
            // Impersonate the new user
            System.err.println("[DEBUG] makeToken: Calling ImpersonateLoggedOnUser() with token: " + token);
            if (!Advapi32Ext.INSTANCE.ImpersonateLoggedOnUser(token)) {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                System.err.println("[DEBUG] makeToken: ImpersonateLoggedOnUser() failed with error: " + error);
                Kernel32Ext.INSTANCE.CloseHandle(token);
                throw new Exception("Failed to impersonate user. Error: " + error);
            }
            System.err.println("[DEBUG] makeToken: ImpersonateLoggedOnUser() succeeded");
            
            System.err.println("[DEBUG] makeToken: About to call getImpersonatedUsername()");
            String currentUser = getImpersonatedUsername();
            System.err.println("[DEBUG] makeToken: getImpersonatedUsername() returned: " + currentUser);
            
            String logonTypeStr = netOnly ? "NetOnly" : "Interactive";
            return new TokenResult(token, String.format("Successfully impersonated %s (%s logon)", currentUser, logonTypeStr));
            
        } catch (Exception e) {
            Kernel32Ext.INSTANCE.CloseHandle(token);
            throw e;
        }
    }
    
    /**
     * Query the username from a specific token handle
     * @param token The token to query
     * @return Domain\Username or null if unable to determine
     */
    private static String queryTokenUsername(WinNT.HANDLE token) {
        if (!isWindows() || Advapi32Ext.INSTANCE == null || Kernel32Ext.INSTANCE == null) {
            return "N/A";
        }
        
        try {
            // Use Memory for length to avoid IntByReference reflection
            com.sun.jna.Memory returnLengthMem = new com.sun.jna.Memory(4);
            returnLengthMem.setInt(0, 0);
            
            // First call to get required buffer size
            Advapi32Ext.INSTANCE.GetTokenInformation(
                token,
                TokenUser,
                Pointer.NULL,
                0,
                returnLengthMem
            );
            
            int bufferSize = returnLengthMem.getInt(0);
            
            // Allocate buffer and get token info
            Pointer buffer = new com.sun.jna.Memory(bufferSize);
            
            if (!Advapi32Ext.INSTANCE.GetTokenInformation(
                token,
                TokenUser,
                buffer,
                bufferSize,
                returnLengthMem)) {
                return "GetTokenInformation failed";
            }
            
            Pointer sidPointer = buffer.getPointer(0);
            if (sidPointer == null) {
                return "NULL SID pointer";
            }
            
            // Lookup account name from SID (pass pointer directly)
            char[] name = new char[256];
            char[] domain = new char[256];
            com.sun.jna.Memory nameLenMem = new com.sun.jna.Memory(4);
            com.sun.jna.Memory domainLenMem = new com.sun.jna.Memory(4);
            com.sun.jna.Memory sidNameUseMem = new com.sun.jna.Memory(4);
            nameLenMem.setInt(0, 256);
            domainLenMem.setInt(0, 256);
            
            if (Advapi32Ext.INSTANCE.LookupAccountSidW(
                null,
                sidPointer,
                name,
                nameLenMem,
                domain,
                domainLenMem,
                sidNameUseMem)) {
                
                String domainStr = new String(domain, 0, domainLenMem.getInt(0));
                String nameStr = new String(name, 0, nameLenMem.getInt(0));
                return domainStr + "\\" + nameStr;
            }
            
            return "LookupAccountSidW failed";
            
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }
    
    /**
     * Get the current username, checking for impersonated token first
     * @return Domain\Username or null if unable to determine
     */
    public static String getImpersonatedUsername() {
        if (!isWindows() || Advapi32Ext.INSTANCE == null || Kernel32Ext.INSTANCE == null) {
            return SystemInfo.getUsername(); // Fall back to process owner
        }
        
        WinNT.HANDLEByReference tokenHandle = new WinNT.HANDLEByReference();
        boolean hasToken = false;
        boolean isThreadToken = false;
        
        try {
            // First try to get the thread token (impersonation token)
            if (Advapi32Ext.INSTANCE.OpenThreadToken(
                Kernel32Ext.INSTANCE.GetCurrentThread(), 
                TOKEN_QUERY, 
                true, // OpenAsSelf
                tokenHandle)) {
                hasToken = true;
                isThreadToken = true;
                System.err.println("[DEBUG] OpenThreadToken succeeded - using thread token");
            } else {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                System.err.println("[DEBUG] OpenThreadToken failed with error: " + error + " - falling back to process token");
                // No thread token, fall back to process token
                if (Advapi32Ext.INSTANCE.OpenProcessToken(
                    Kernel32Ext.INSTANCE.GetCurrentProcess(),
                    TOKEN_QUERY,
                    tokenHandle)) {
                    hasToken = true;
                    System.err.println("[DEBUG] OpenProcessToken succeeded - using process token");
                } else {
                    System.err.println("[DEBUG] OpenProcessToken failed with error: " + Kernel32Ext.INSTANCE.GetLastError());
                }
            }
            
            if (!hasToken) {
                System.err.println("[DEBUG] No token available, falling back to SystemInfo.getUsername()");
                return SystemInfo.getUsername(); // Can't get token, fall back
            }
            
            // Get token user information - use Memory for length to avoid IntByReference reflection
            com.sun.jna.Memory returnLengthMem = new com.sun.jna.Memory(4); // int is 4 bytes
            returnLengthMem.setInt(0, 0);
            
            // First call to get required buffer size
            Advapi32Ext.INSTANCE.GetTokenInformation(
                tokenHandle.getValue(),
                TokenUser,
                Pointer.NULL,
                0,
                returnLengthMem
            );
            
            int bufferSize = returnLengthMem.getInt(0);
            
            // Allocate buffer and get token info
            Pointer buffer = new com.sun.jna.Memory(bufferSize);
            
            System.err.println("[DEBUG] Getting token information, buffer size: " + bufferSize);
            
            if (!Advapi32Ext.INSTANCE.GetTokenInformation(
                tokenHandle.getValue(),
                TokenUser,
                buffer,
                bufferSize,
                returnLengthMem)) {
                System.err.println("[DEBUG] GetTokenInformation failed with error: " + Kernel32Ext.INSTANCE.GetLastError());
                return SystemInfo.getUsername();
            }
            
            Pointer sidPointer = buffer.getPointer(0);
            if (sidPointer == null) {
                System.err.println("[DEBUG] NULL SID pointer in token");
                return SystemInfo.getUsername();
            }
            
            System.err.println("[DEBUG] Token user SID pointer: " + sidPointer);
            
            char[] name = new char[256];
            char[] domain = new char[256];
            com.sun.jna.Memory nameLenMem = new com.sun.jna.Memory(4);
            com.sun.jna.Memory domainLenMem = new com.sun.jna.Memory(4);
            com.sun.jna.Memory sidNameUseMem = new com.sun.jna.Memory(4);
            nameLenMem.setInt(0, 256);
            domainLenMem.setInt(0, 256);
            
            if (Advapi32Ext.INSTANCE.LookupAccountSidW(
                null,
                sidPointer,
                name,
                nameLenMem,
                domain,
                domainLenMem,
                sidNameUseMem)) {
                
                String domainStr = new String(domain, 0, domainLenMem.getInt(0));
                String nameStr = new String(name, 0, nameLenMem.getInt(0));
                String result = domainStr + "\\" + nameStr;
                System.err.println("[DEBUG] LookupAccountSidW succeeded: " + result + " (using " + (isThreadToken ? "thread" : "process") + " token)");
                return result;
            } else {
                System.err.println("[DEBUG] LookupAccountSidW failed with error: " + Kernel32Ext.INSTANCE.GetLastError());
            }
            
            return SystemInfo.getUsername();
            
        } catch (Exception e) {
            System.err.println("[DEBUG] getImpersonatedUsername: Exception caught: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return SystemInfo.getUsername();
        } finally {
            if (hasToken && tokenHandle.getValue() != null) {
                Kernel32Ext.INSTANCE.CloseHandle(tokenHandle.getValue());
            }
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
            String username = getImpersonatedUsername();
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
            return null;
        }
    }
    
    /**
     * Re-apply an impersonation token to the current thread
     * @param token The token handle to apply
     */
    public static void reApplyToken(WinNT.HANDLE token) throws Exception {
        if (!isWindows() || Advapi32Ext.INSTANCE == null) {
            throw new Exception("Token operations are only supported on Windows");
        }
        
        if (token == null || WinBase.INVALID_HANDLE_VALUE.equals(token)) {
            throw new Exception("Invalid token handle");
        }
        
        System.err.println("[DEBUG] reApplyToken: Re-applying impersonation token: " + token);
        
        if (!Advapi32Ext.INSTANCE.ImpersonateLoggedOnUser(token)) {
            int error = Kernel32Ext.INSTANCE.GetLastError();
            System.err.println("[DEBUG] reApplyToken: ImpersonateLoggedOnUser FAILED with error: " + error);
            throw new Exception("Failed to re-apply impersonation token. Error: " + error);
        }
        
        System.err.println("[DEBUG] reApplyToken: ImpersonateLoggedOnUser SUCCEEDED");
        String currentUser = getImpersonatedUsername();
        System.err.println("[DEBUG] reApplyToken: Current thread user is now: " + currentUser);
    }
    
    /**
     * Close a handle
     * @param handle The handle to close
     */
    public static void closeHandle(WinNT.HANDLE handle) {
        if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            Kernel32Ext.INSTANCE.CloseHandle(handle);
        }
    }
    
    /**
     * Get the username of a process owner
     * @param pid Process ID
     * @return Username in DOMAIN\\user format, or null if failed
     */
    public static String getProcessOwner(int pid) {
        if (!isWindows() || Kernel32Ext.INSTANCE == null || Advapi32Ext.INSTANCE == null) {
            return null;
        }
        
        WinNT.HANDLE processHandle = null;
        WinNT.HANDLEByReference tokenHandle = new WinNT.HANDLEByReference();
        
        try {
            // Open process with QUERY_INFORMATION access
            processHandle = Kernel32Ext.INSTANCE.OpenProcess(
                PROCESS_QUERY_INFORMATION,
                false,
                pid
            );
            
            if (processHandle == null || WinBase.INVALID_HANDLE_VALUE.equals(processHandle)) {
                return null;
            }
            
            // Open process token
            if (!Advapi32Ext.INSTANCE.OpenProcessToken(
                processHandle,
                TOKEN_QUERY,
                tokenHandle)) {
                return null;
            }
            
            // Get token user information
            com.sun.jna.Memory returnLengthMem = new com.sun.jna.Memory(4);
            Advapi32Ext.INSTANCE.GetTokenInformation(
                tokenHandle.getValue(),
                TokenUser,
                null,
                0,
                returnLengthMem
            );
            
            int bufferSize = returnLengthMem.getInt(0);
            if (bufferSize <= 0) {
                return null;
            }
            
            com.sun.jna.Memory buffer = new com.sun.jna.Memory(bufferSize);
            
            if (!Advapi32Ext.INSTANCE.GetTokenInformation(
                tokenHandle.getValue(),
                TokenUser,
                buffer,
                bufferSize,
                returnLengthMem)) {
                return null;
            }
            
            // TOKEN_USER structure: PSID at offset 0
            Pointer sidPointer = buffer.getPointer(0);
            if (sidPointer == null) {
                return null;
            }
            
            // Lookup account name from SID
            char[] name = new char[256];
            char[] domain = new char[256];
            com.sun.jna.Memory nameLenMem = new com.sun.jna.Memory(4);
            com.sun.jna.Memory domainLenMem = new com.sun.jna.Memory(4);
            com.sun.jna.Memory sidNameUseMem = new com.sun.jna.Memory(4);
            nameLenMem.setInt(0, 256);
            domainLenMem.setInt(0, 256);
            
            if (Advapi32Ext.INSTANCE.LookupAccountSidW(
                null,
                sidPointer,
                name,
                nameLenMem,
                domain,
                domainLenMem,
                sidNameUseMem)) {
                
                String domainStr = new String(domain, 0, domainLenMem.getInt(0));
                String nameStr = new String(name, 0, nameLenMem.getInt(0));
                return domainStr + "\\" + nameStr;
            }
            
            return null;
            
        } catch (Exception e) {
            return null;
        } finally {
            if (tokenHandle.getValue() != null && !WinBase.INVALID_HANDLE_VALUE.equals(tokenHandle.getValue())) {
                Kernel32Ext.INSTANCE.CloseHandle(tokenHandle.getValue());
            }
            if (processHandle != null && !WinBase.INVALID_HANDLE_VALUE.equals(processHandle)) {
                Kernel32Ext.INSTANCE.CloseHandle(processHandle);
            }
        }
    }
    
    /**
     * Enumerate all processes using Windows API
     * @return List of maps containing process information
     */
    public static List<Map<String, Object>> enumerateProcesses() {
        List<Map<String, Object>> processes = new ArrayList<>();
        
        if (!isWindows() || Kernel32Ext.INSTANCE == null) {
            return processes;
        }
        
        WinNT.HANDLE snapshot = null;
        
        try {
            // Create snapshot of all processes
            snapshot = Kernel32Ext.INSTANCE.CreateToolhelp32Snapshot(
                Kernel32Ext.TH32CS_SNAPPROCESS, 
                0
            );
            
            System.err.println("[DEBUG] CreateToolhelp32Snapshot returned: " + snapshot);
            if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
                System.err.println("[DEBUG] Snapshot is null or invalid, returning empty list");
                return processes;
            }
            
            PROCESSENTRY32 pe32 = new PROCESSENTRY32();
            System.err.println("[DEBUG] PROCESSENTRY32 created, dwSize=" + pe32.dwSize + ", size()=" + pe32.size());
            
            boolean firstResult = Kernel32Ext.INSTANCE.Process32First(snapshot, pe32);
            System.err.println("[DEBUG] Process32First returned: " + firstResult);
            if (firstResult) {
                do {
                    // Read the structure from native memory
                    pe32.read();
                    
                    Map<String, Object> processInfo = new HashMap<>();
                    
                    // Get process ID
                    int pid = pe32.th32ProcessID;
                    processInfo.put("process_id", pid);
                    
                    // Get parent process ID
                    int ppid = pe32.th32ParentProcessID;
                    processInfo.put("parent_process_id", ppid);
                    
                    int nameLen = 0;
                    while (nameLen < pe32.szExeFile.length && pe32.szExeFile[nameLen] != 0) {
                        nameLen++;
                    }
                    String name = "";
                    if (nameLen > 0) {
                        try {
                            name = new String(pe32.szExeFile, 0, nameLen, "US-ASCII");
                        } catch (Exception e) {
                            name = "";
                        }
                    }
                    
                    if (!name.isEmpty()) {
                        processInfo.put("name", name);
                    }
                    
                    // Try to get user
                    String user = getProcessOwner(pid);
                    if (user != null) {
                        processInfo.put("user", user);
                    }
                    
                    processes.add(processInfo);
                    
                    // Reset dwSize before next call
                    pe32.dwSize = pe32.size();
                } while (Kernel32Ext.INSTANCE.Process32Next(snapshot, pe32));
            } else {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                System.err.println("[DEBUG] Process32First failed with error: " + error);
            }
            
            // Debug: print last 10 processes (more likely to be accessible user processes)
            System.err.println("[DEBUG] Total processes enumerated: " + processes.size());
            int startDebug = Math.max(0, processes.size() - 10);
            System.err.println("[DEBUG] Showing last 10 of " + processes.size() + " processes:");
            for (int i = startDebug; i < processes.size(); i++) {
                Map<String, Object> proc = processes.get(i);
                int pid = (int) proc.get("process_id");
                int ppid = (int) proc.get("parent_process_id");
                String name = (String) proc.getOrDefault("name", "");
                String user = (String) proc.getOrDefault("user", "");
                System.err.println("[DEBUG] #" + i + ": PID=" + pid + " PPID=" + ppid + 
                                 " name='" + name + "' user='" + user + "'");
            }
            
        } catch (Exception e) {
            // Return what we have so far
        } finally {
            if (snapshot != null && !WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
                Kernel32Ext.INSTANCE.CloseHandle(snapshot);
            }
        }
        
        return processes;
    }
    
    public static Integer getCurrentProcessIntegrityLevel() {
        System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: Starting");
        
        if (!isWindows()) {
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: Not Windows");
            return null;
        }
        
        if (Kernel32Ext.INSTANCE == null) {
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: Kernel32Ext.INSTANCE is null");
            return null;
        }
        
        if (Advapi32Ext.INSTANCE == null) {
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: Advapi32Ext.INSTANCE is null");
            return null;
        }
        
        WinNT.HANDLE processHandle = null;
        WinNT.HANDLEByReference tokenHandle = new WinNT.HANDLEByReference();
        
        try {
            // Get current process handle
            processHandle = Kernel32Ext.INSTANCE.GetCurrentProcess();
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: GetCurrentProcess returned: " + processHandle);
            if (processHandle == null) {
                System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: processHandle is null");
                return null;
            }
            
            // Open process token
            boolean openResult = Advapi32Ext.INSTANCE.OpenProcessToken(processHandle, TOKEN_QUERY, tokenHandle);
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: OpenProcessToken returned: " + openResult);
            if (!openResult) {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: OpenProcessToken failed with error: " + error);
                return null;
            }
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: tokenHandle = " + tokenHandle.getValue());
            
            // Get size needed for token information
            com.sun.jna.Memory returnLengthMem = new com.sun.jna.Memory(4);
            boolean sizeResult = Advapi32Ext.INSTANCE.GetTokenInformation(
                tokenHandle.getValue(),
                TokenIntegrityLevel,
                null,
                0,
                returnLengthMem
            );
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: GetTokenInformation (size query) returned: " + sizeResult);
            
            int bufferSize = returnLengthMem.getInt(0);
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: bufferSize = " + bufferSize);
            if (bufferSize <= 0) {
                System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: bufferSize <= 0, returning null");
                return null;
            }
            
            // Get token information
            com.sun.jna.Memory buffer = new com.sun.jna.Memory(bufferSize);
            boolean getResult = Advapi32Ext.INSTANCE.GetTokenInformation(
                tokenHandle.getValue(),
                TokenIntegrityLevel,
                buffer,
                bufferSize,
                returnLengthMem);
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: GetTokenInformation (actual) returned: " + getResult);
            if (!getResult) {
                int error = Kernel32Ext.INSTANCE.GetLastError();
                System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: GetTokenInformation failed with error: " + error);
                return null;
            }
            
            // Parse TOKEN_MANDATORY_LABEL structure
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: Creating TOKEN_MANDATORY_LABEL from buffer");
            TOKEN_MANDATORY_LABEL tml = new TOKEN_MANDATORY_LABEL(buffer);
            Pointer sidPointer = tml.Label.Sid;
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: SID pointer = " + sidPointer);
            
            if (sidPointer == null) {
                System.err.println("[DEBUG] Integrity level: SID pointer is null");
                return null;
            }
            
            Pointer countPtr = Advapi32Ext.INSTANCE.GetSidSubAuthorityCount(sidPointer);
            if (countPtr == null) {
                System.err.println("[DEBUG] Integrity level: GetSidSubAuthorityCount returned null");
                return null;
            }
            
            // Read the count as unsigned byte
            int count = countPtr.getByte(0) & 0xFF;
            System.err.println("[DEBUG] Integrity level: Sub-authority count = " + count);
            if (count <= 0) {
                return null;
            }
            
            Pointer integrityPtr = Advapi32Ext.INSTANCE.GetSidSubAuthority(sidPointer, count - 1);
            if (integrityPtr == null) {
                System.err.println("[DEBUG] Integrity level: GetSidSubAuthority returned null");
                return null;
            }
            
            long integrityLevelSid = integrityPtr.getInt(0) & 0xFFFFFFFFL;
            System.err.println("[DEBUG] Integrity level: Raw SID value = 0x" + Long.toHexString(integrityLevelSid));
            
            int rid = (int)(integrityLevelSid >>> 12);
            System.err.println("[DEBUG] Integrity level: RID (after >>> 12) = " + rid);
            
            return rid;
            
        } catch (Exception e) {
            System.err.println("[DEBUG] getCurrentProcessIntegrityLevel: Exception caught: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (tokenHandle.getValue() != null && !WinBase.INVALID_HANDLE_VALUE.equals(tokenHandle.getValue())) {
                Kernel32Ext.INSTANCE.CloseHandle(tokenHandle.getValue());
            }
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
            Map<String, Object> options = new HashMap<>();
            options.put(com.sun.jna.Library.OPTION_TYPE_MAPPER, W32APIOptions.DEFAULT_OPTIONS.get(com.sun.jna.Library.OPTION_TYPE_MAPPER));
            options.put(com.sun.jna.Library.OPTION_FUNCTION_MAPPER, W32APIOptions.ASCII_OPTIONS.get(com.sun.jna.Library.OPTION_FUNCTION_MAPPER));
            return Native.load("kernel32", Kernel32Ext.class, options);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }
}
