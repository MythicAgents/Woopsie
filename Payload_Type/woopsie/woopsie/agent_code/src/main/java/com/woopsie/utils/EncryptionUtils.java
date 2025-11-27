package com.woopsie.utils;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encryption utilities for Mythic C2 communication
 * Implements AES-256-CBC with HMAC-SHA256 integrity checking
 */
public class EncryptionUtils {
    
    private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int IV_SIZE = 16;
    private static final int HMAC_SIZE = 32;
    
    /**
     * Encrypt data using AES-256-CBC with random IV and HMAC-SHA256
     * Format: IV (16 bytes) + Ciphertext + HMAC (32 bytes)
     */
    public static byte[] encryptAES256(byte[] data, byte[] key) throws Exception {
        // Generate random IV
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);
        
        // Encrypt with AES-256-CBC
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] ciphertext = cipher.doFinal(data);
        
        // Combine IV + ciphertext
        byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
        System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);
        
        // Calculate HMAC over IV + ciphertext
        Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec hmacKey = new SecretKeySpec(key, HMAC_ALGORITHM);
        hmac.init(hmacKey);
        byte[] hmacResult = hmac.doFinal(ivAndCiphertext);
        
        // Final format: IV + Ciphertext + HMAC
        byte[] result = new byte[ivAndCiphertext.length + hmacResult.length];
        System.arraycopy(ivAndCiphertext, 0, result, 0, ivAndCiphertext.length);
        System.arraycopy(hmacResult, 0, result, ivAndCiphertext.length, hmacResult.length);
        
        return result;
    }
    
    /**
     * Decrypt data using AES-256-CBC and verify HMAC-SHA256
     * Expected format: IV (16 bytes) + Ciphertext + HMAC (32 bytes)
     */
    public static byte[] decryptAES256(byte[] data, byte[] key) throws Exception {
        if (data.length < IV_SIZE + HMAC_SIZE) {
            throw new IllegalArgumentException("Invalid encrypted data length");
        }
        
        // Extract components
        int ciphertextLength = data.length - HMAC_SIZE;
        byte[] ivAndCiphertext = Arrays.copyOfRange(data, 0, ciphertextLength);
        byte[] receivedHmac = Arrays.copyOfRange(data, ciphertextLength, data.length);
        
        // Verify HMAC
        Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec hmacKey = new SecretKeySpec(key, HMAC_ALGORITHM);
        hmac.init(hmacKey);
        byte[] calculatedHmac = hmac.doFinal(ivAndCiphertext);
        
        if (!Arrays.equals(receivedHmac, calculatedHmac)) {
            throw new SecurityException("HMAC verification failed - data may be corrupted or tampered");
        }
        
        // Extract IV and ciphertext
        byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_SIZE);
        byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, IV_SIZE, ivAndCiphertext.length);
        
        // Decrypt
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        
        return cipher.doFinal(ciphertext);
    }
    
    /**
     * Encrypt payload with UUID prefix (for Mythic protocol)
     * Format: UUID (36 bytes) + IV + Ciphertext + HMAC
     */
    public static byte[] encryptPayload(byte[] message, byte[] key, String uuid) throws Exception {
        byte[] encrypted = encryptAES256(message, key);
        byte[] uuidBytes = uuid.getBytes();
        
        byte[] result = new byte[uuidBytes.length + encrypted.length];
        System.arraycopy(uuidBytes, 0, result, 0, uuidBytes.length);
        System.arraycopy(encrypted, 0, result, uuidBytes.length, encrypted.length);
        
        return result;
    }
    
    /**
     * Decrypt payload with UUID prefix (for Mythic protocol)
     * Expected format: UUID (36 bytes) + IV + Ciphertext + HMAC
     */
    public static byte[] decryptPayload(byte[] message, byte[] key) throws Exception {
        if (message.length <= 36) {
            throw new IllegalArgumentException("Invalid payload length");
        }
        
        // Skip first 36 bytes (UUID) and decrypt the rest
        byte[] encryptedPart = Arrays.copyOfRange(message, 36, message.length);
        return decryptAES256(encryptedPart, key);
    }
}
