package com.woopsie;

import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration for Woopsie agent - uses compile-time baked values
 */
public class Config {
    private String callbackHost;
    private int callbackPort;
    private String getUri;
    private String postUri;
    private int sleepInterval;
    private int jitter;
    private String uuid;
    private String userAgent;
    private boolean debug;
    private boolean encryptedExchangeCheck;
    private String killdate;
    private String proxyHost;
    private int proxyPort;
    private String proxyUser;
    private String proxyPass;
    private String aespsk;
    
    public Config() {
    }
    
    public static Config fromResource() {
        Config config = new Config();
        
        // Read from config.properties that was filtered at build time
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            Properties props = new Properties();
            props.load(input);
            
            config.uuid = props.getProperty("uuid");
            config.callbackHost = props.getProperty("callback_host");
            config.callbackPort = Integer.parseInt(props.getProperty("callback_port"));
            config.getUri = props.getProperty("get_uri");
            config.postUri = props.getProperty("post_uri");
            config.sleepInterval = Integer.parseInt(props.getProperty("callback_interval")) * 1000; // Convert to ms
            config.jitter = Integer.parseInt(props.getProperty("callback_jitter"));
            config.userAgent = props.getProperty("user_agent");
            config.debug = Boolean.parseBoolean(props.getProperty("debug", "false"));
            config.encryptedExchangeCheck = Boolean.parseBoolean(props.getProperty("encrypted_exchange_check", "false"));
            config.killdate = props.getProperty("killdate", "");
            config.proxyHost = props.getProperty("proxy_host", "");
            String proxyPortStr = props.getProperty("proxy_port", "0");
            config.proxyPort = proxyPortStr.isEmpty() ? 0 : Integer.parseInt(proxyPortStr);
            config.proxyUser = props.getProperty("proxy_user", "");
            config.proxyPass = props.getProperty("proxy_pass", "");
            config.aespsk = props.getProperty("aespsk", "");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
        
        return config;
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
        return userAgent;
    }
    
    public String getCallbackUrl() {
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
    
    public static void debugLog(Config config, String message) {
        if (config != null && config.isDebug()) {
            System.err.println("[DEBUG] " + message);
        }
    }
}
