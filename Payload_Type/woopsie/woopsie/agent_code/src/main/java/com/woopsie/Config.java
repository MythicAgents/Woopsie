package com.woopsie;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration for Woopsie agent - uses compile-time baked values
 */
public class Config {
        @Override
        public String toString() {
            return "Config{" +
                    "uuid='" + uuid + '\'' +
                    ", callbackHost='" + callbackHost + '\'' +
                    ", callbackPort=" + callbackPort +
                    ", getUri='" + getUri + '\'' +
                    ", postUri='" + postUri + '\'' +
                    ", sleepInterval=" + sleepInterval +
                    ", jitter=" + jitter +
                    ", debug=" + debug +
                    ", encryptedExchangeCheck=" + encryptedExchangeCheck +
                    ", killdate='" + killdate + '\'' +
                    ", proxyHost='" + proxyHost + '\'' +
                    ", proxyPort=" + proxyPort +
                    ", proxyUser='" + proxyUser + '\'' +
                    ", proxyPass='" + proxyPass + '\'' +
                    ", aespsk='" + aespsk + '\'' +
                    ", profile='" + profile + '\'' +
                    ", callbackDomains='" + callbackDomains + '\'' +
                    ", domainRotation='" + domainRotation + '\'' +
                    ", failoverThreshold=" + failoverThreshold +
                    ", rawC2Config='" + rawC2Config + '\'' +
                    '}';
        }
    private String callbackHost;
    private int callbackPort;
    private String getUri;
    private String postUri;
    private int sleepInterval;
    private int jitter;
    private String uuid;
    private Map<String, String> headers;
    private boolean debug;
    private boolean encryptedExchangeCheck;
    private String killdate;
    private String proxyHost;
    private int proxyPort;
    private String proxyUser;
    private String proxyPass;
    private String aespsk;
    
    // Profile-specific fields
    private String profile;
    private String callbackDomains;
    private String domainRotation;
    private int failoverThreshold;
    private String rawC2Config;
    
    public Config() {
    }
    
    public static Config fromResource() {
        Config config = new Config();
        
        // Read from config.properties that was filtered at build time
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            Properties props = new Properties();
            props.load(input);

            config.uuid = trimOrNull(props.getProperty("uuid"));
            config.callbackHost = trimOrNull(props.getProperty("callback_host"));
            
            // Parse callback_port - may not be set for httpx profile
            String callbackPortStr = trimOrNull(props.getProperty("callback_port", "0"));
            config.callbackPort = (callbackPortStr == null) ? 0 : Integer.parseInt(callbackPortStr);
            
            config.getUri = trimOrNull(props.getProperty("get_uri"));
            config.postUri = trimOrNull(props.getProperty("post_uri"));
            config.sleepInterval = Integer.parseInt(props.getProperty("callback_interval").trim()) * 1000; // Convert to ms
            config.jitter = Integer.parseInt(props.getProperty("callback_jitter").trim());
            
            // Parse headers JSON if present
            String headersJson = trimOrNull(props.getProperty("headers"));
            if (headersJson != null && !headersJson.isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    config.headers = mapper.readValue(headersJson, new TypeReference<Map<String, String>>(){});
                } catch (Exception e) {
                    // If parsing fails, just use default empty map
                    config.headers = new HashMap<>();
                }
            } else {
                config.headers = new HashMap<>();
            }
            
            config.debug = Boolean.parseBoolean(props.getProperty("debug", "false").trim());
            config.encryptedExchangeCheck = Boolean.parseBoolean(props.getProperty("encrypted_exchange_check", "false").trim());
            config.killdate = trimOrNull(props.getProperty("killdate", ""));
            config.proxyHost = trimOrNull(props.getProperty("proxy_host", ""));
            
            // Parse proxy_port - may not be set for httpx profile
            String proxyPortStr = trimOrNull(props.getProperty("proxy_port", "0"));
            config.proxyPort = (proxyPortStr == null) ? 0 : Integer.parseInt(proxyPortStr);
            
            config.proxyUser = trimOrNull(props.getProperty("proxy_user", ""));
            config.proxyPass = trimOrNull(props.getProperty("proxy_pass", ""));
            config.aespsk = trimOrNull(props.getProperty("aespsk", ""));

            // Profile-specific properties
            config.profile = trimOrNull(props.getProperty("profile", "http"));
            config.callbackDomains = trimOrNull(props.getProperty("callback_domains", ""));
            config.domainRotation = trimOrNull(props.getProperty("domain_rotation", "fail-over"));
            
            // Parse failover_threshold - may not be set for http profile
            String failoverThresholdStr = trimOrNull(props.getProperty("failover_threshold", "1"));
            config.failoverThreshold = (failoverThresholdStr == null) ? 1 : Integer.parseInt(failoverThresholdStr);
            
            config.rawC2Config = trimOrNull(props.getProperty("raw_c2_config", ""));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration", e);
        }

        return config;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        s = s.trim();
        // If Maven didn't substitute the variable (starts with ${env.), treat as empty
        if (s.startsWith("${env.")) return null;
        return s.isEmpty() ? null : s;
    }
    
    public String getCallbackHost() {
        return callbackHost;
    }
    
    public int getCallbackPort() {
        return callbackPort;
    }
    
    public String getGetUri() {
        // Ensure URI starts with /
        if (getUri != null && !getUri.startsWith("/")) {
            return "/" + getUri;
        }
        return getUri;
    }
    
    public String getPostUri() {
        // Ensure URI starts with /
        if (postUri != null && !postUri.startsWith("/")) {
            return "/" + postUri;
        }
        return postUri;
    }
    
    public int getSleepInterval() {
        // Apply jitter to sleep interval
        int jitterAmount = (int) (sleepInterval * (jitter / 100.0));
        int variance = (int) (Math.random() * jitterAmount * 2) - jitterAmount;
        return sleepInterval + variance;
    }
    
    public void setSleepInterval(int sleepInterval) {
        this.sleepInterval = sleepInterval;
    }
    
    public void setJitter(int jitter) {
        this.jitter = jitter;
    }
    
    public String getUuid() {
        return uuid;
    }
    
    public String getUserAgent() {
        return headers != null ? headers.get("User-Agent") : null;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public String getCallbackUrl() {
        // For httpx profile, callback_host/port may not be set
        if (callbackHost == null || callbackHost.isEmpty()) {
            return null;
        }
        
        // callback_host from Mythic already includes the protocol (http:// or https://)
        // so we don't need to prepend it
        if (callbackHost.startsWith("http://") || callbackHost.startsWith("https://")) {
            return String.format("%s:%d", callbackHost, callbackPort);
        }
        // Fallback for cases where protocol is not included
        return String.format("http://%s:%d", callbackHost, callbackPort);
    }
    
    public boolean isDebug() {
        return debug;
    }
    
    public boolean isEncryptedExchangeCheck() {
        return encryptedExchangeCheck;
    }
    
    public String getKilldate() {
        return killdate;
    }
    
    public String getProxyHost() {
        return proxyHost;
    }
    
    public int getProxyPort() {
        return proxyPort;
    }
    
    public String getProxyUser() {
        return proxyUser;
    }
    
    public String getProxyPass() {
        return proxyPass;
    }
    
    public boolean hasProxy() {
        return proxyHost != null && !proxyHost.isEmpty() && proxyPort > 0;
    }
    
    public String getAespsk() {
        return aespsk;
    }
    
    public String getProfile() {
        return profile;
    }
    
    public String getCallbackDomains() {
        return callbackDomains;
    }
    
    public String getDomainRotation() {
        return domainRotation;
    }
    
    public int getFailoverThreshold() {
        return failoverThreshold;
    }
    
    public String getRawC2Config() {
        return rawC2Config;
    }
    
    public static void debugLog(Config config, String message) {
        if (config != null && config.isDebug()) {
            System.err.println("[DEBUG] " + message);
        }
    }
}
