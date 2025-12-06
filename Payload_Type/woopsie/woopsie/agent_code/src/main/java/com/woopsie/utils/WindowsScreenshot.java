package com.woopsie.utils;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.ByteArrayOutputStream;

/**
 * Windows-specific screenshot capture using GDI32
 * No AWT dependencies required
 */
public class WindowsScreenshot {
    
    // GDI32 constants
    private static final int SRCCOPY = 0x00CC0020;
    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;
    private static final int SM_CYVIRTUALSCREEN = 79;
    private static final int BI_RGB = 0;
    private static final int DIB_RGB_COLORS = 0;
    
    private static User32Ext user32;
    private static Gdi32Ext gdi32;
    
    /**
     * Extended User32 interface
     */
    public interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = loadUser32Lib();
        
        int GetSystemMetrics(int nIndex);
        WinDef.HWND GetDesktopWindow();
        WinDef.HDC GetDC(WinDef.HWND hWnd);
        int ReleaseDC(WinDef.HWND hWnd, WinDef.HDC hDC);
    }
    
    /**
     * Extended GDI32 interface
     */
    public interface Gdi32Ext extends StdCallLibrary {
        Gdi32Ext INSTANCE = loadGdi32Lib();
        
        WinDef.HDC CreateCompatibleDC(WinDef.HDC hdc);
        WinDef.HBITMAP CreateCompatibleBitmap(WinDef.HDC hdc, int width, int height);
        Pointer SelectObject(WinDef.HDC hdc, Pointer hgdiobj);
        boolean BitBlt(WinDef.HDC hdcDest, int nXDest, int nYDest, int nWidth, int nHeight,
                      WinDef.HDC hdcSrc, int nXSrc, int nYSrc, int dwRop);
        int GetDIBits(WinDef.HDC hdc, WinDef.HBITMAP hbmp, int uStartScan, int cScanLines,
                     Pointer lpvBits, BITMAPINFO lpbi, int uUsage);
        boolean DeleteObject(Pointer hObject);
        boolean DeleteDC(WinDef.HDC hdc);
    }
    
    /**
     * BITMAPINFOHEADER structure
     */
    @Structure.FieldOrder({"biSize", "biWidth", "biHeight", "biPlanes", "biBitCount", 
                           "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
                           "biClrUsed", "biClrImportant"})
    public static class BITMAPINFOHEADER extends Structure {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;
    }
    
    /**
     * BITMAPINFO structure (without color table for BI_RGB)
     */
    @Structure.FieldOrder({"bmiHeader"})
    public static class BITMAPINFO extends Structure {
        public BITMAPINFOHEADER bmiHeader;
        
        public BITMAPINFO() {
            bmiHeader = new BITMAPINFOHEADER();
        }
    }
    
    /**
     * Check if we're running on Windows
     */
    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
    
    /**
     * Load User32 library (lazy loading for Linux compatibility)
     */
    private static User32Ext loadUser32Lib() {
        if (!isWindows()) {
            return null;
        }
        try {
            return Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }
    
    /**
     * Load GDI32 library (lazy loading for Linux compatibility)
     */
    private static Gdi32Ext loadGdi32Lib() {
        if (!isWindows()) {
            return null;
        }
        try {
            return Native.load("gdi32", Gdi32Ext.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }
    
    /**
     * Load User32 library (wrapper for backward compatibility)
     */
    private static User32Ext loadUser32() {
        if (user32 == null && isWindows()) {
            user32 = User32Ext.INSTANCE;
        }
        return user32;
    }
    
    /**
     * Load GDI32 library (wrapper for backward compatibility)
     */
    private static Gdi32Ext loadGdi32() {
        if (gdi32 == null && isWindows()) {
            gdi32 = Gdi32Ext.INSTANCE;
        }
        return gdi32;
    }
    
    /**
     * Capture screenshot using Windows GDI API
     * @return PNG image data
     */
    public static byte[] captureScreenshot() throws Exception {
        if (!isWindows()) {
            throw new UnsupportedOperationException("Screenshot is only supported on Windows");
        }
        
        User32Ext user32 = loadUser32();
        Gdi32Ext gdi32 = loadGdi32();
        
        if (user32 == null || gdi32 == null) {
            throw new UnsupportedOperationException("Failed to load Windows libraries");
        }
        
        // Get virtual screen metrics (all monitors)
        int xVirtual = user32.GetSystemMetrics(SM_XVIRTUALSCREEN);
        int yVirtual = user32.GetSystemMetrics(SM_YVIRTUALSCREEN);
        int width = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN);
        int height = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN);
        
        if (width <= 0 || height <= 0) {
            throw new Exception("Invalid screen dimensions: " + width + "x" + height);
        }
        
        // Get desktop window and DC
        WinDef.HWND desktopWindow = user32.GetDesktopWindow();
        WinDef.HDC hScreen = user32.GetDC(desktopWindow);
        WinDef.HDC hDC = gdi32.CreateCompatibleDC(hScreen);
        WinDef.HBITMAP hBitmap = gdi32.CreateCompatibleBitmap(hScreen, width, height);
        
        try {
            // Select bitmap into DC
            gdi32.SelectObject(hDC, hBitmap.getPointer());
            
            // Copy screen to bitmap
            gdi32.BitBlt(hDC, 0, 0, width, height, hScreen, xVirtual, yVirtual, SRCCOPY);
            
            // Setup bitmap info
            BITMAPINFO bitmapInfo = new BITMAPINFO();
            bitmapInfo.bmiHeader.biSize = bitmapInfo.bmiHeader.size();
            bitmapInfo.bmiHeader.biWidth = width;
            bitmapInfo.bmiHeader.biHeight = -height; // Top-down DIB
            bitmapInfo.bmiHeader.biPlanes = 1;
            bitmapInfo.bmiHeader.biBitCount = 32; // BGRA format
            bitmapInfo.bmiHeader.biCompression = BI_RGB;
            bitmapInfo.write();
            
            // Get bitmap bits
            int imageDataSize = width * height * 4; // 4 bytes per pixel (BGRA)
            Memory imageData = new Memory(imageDataSize);
            
            int result = gdi32.GetDIBits(hDC, hBitmap, 0, height, imageData, bitmapInfo, DIB_RGB_COLORS);
            if (result == 0) {
                throw new Exception("GetDIBits failed");
            }
            
            // Convert BGRA to RGBA
            byte[] pixels = imageData.getByteArray(0, imageDataSize);
            for (int i = 0; i < pixels.length; i += 4) {
                byte b = pixels[i];
                byte r = pixels[i + 2];
                pixels[i] = r;     // R
                pixels[i + 2] = b; // B
                // G and A stay the same
            }
            
            // Encode as PNG
            byte[] pngData = encodePNG(pixels, width, height);
            
            return pngData;
            
        } finally {
            // Cleanup
            gdi32.DeleteObject(hBitmap.getPointer());
            gdi32.DeleteDC(hDC);
            user32.ReleaseDC(desktopWindow, hScreen);
        }
    }
    
    /**
     * Encode raw RGBA image data as PNG
     * Simple PNG encoder implementation
     */
    private static byte[] encodePNG(byte[] imageData, int width, int height) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // PNG signature
        baos.write(new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        
        // IHDR chunk
        writeChunk(baos, "IHDR", createIHDR(width, height));
        
        // IDAT chunk (image data)
        writeChunk(baos, "IDAT", compressImageData(imageData, width, height));
        
        // IEND chunk
        writeChunk(baos, "IEND", new byte[0]);
        
        return baos.toByteArray();
    }
    
    /**
     * Create IHDR chunk data
     */
    private static byte[] createIHDR(int width, int height) {
        byte[] ihdr = new byte[13];
        writeInt(ihdr, 0, width);
        writeInt(ihdr, 4, height);
        ihdr[8] = 8;  // Bit depth
        ihdr[9] = 6;  // Color type (RGBA)
        ihdr[10] = 0; // Compression method
        ihdr[11] = 0; // Filter method
        ihdr[12] = 0; // Interlace method
        return ihdr;
    }
    
    /**
     * Compress image data for IDAT chunk
     * Uses simple DEFLATE compression
     */
    private static byte[] compressImageData(byte[] imageData, int width, int height) throws Exception {
        ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
        
        // Add filter byte (0 = none) before each scanline
        int bytesPerPixel = 4; // RGBA
        int bytesPerLine = width * bytesPerPixel;
        
        for (int y = 0; y < height; y++) {
            uncompressed.write(0); // Filter type: None
            uncompressed.write(imageData, y * bytesPerLine, bytesPerLine);
        }
        
        // Use Java's built-in DEFLATE compression
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(uncompressed.toByteArray());
        deflater.finish();
        
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            compressed.write(buffer, 0, count);
        }
        deflater.end();
        
        return compressed.toByteArray();
    }
    
    /**
     * Write a PNG chunk
     */
    private static void writeChunk(ByteArrayOutputStream baos, String type, byte[] data) throws Exception {
        // Length
        writeInt(baos, data.length);
        
        // Type
        byte[] typeBytes = type.getBytes("ASCII");
        baos.write(typeBytes);
        
        // Data
        baos.write(data);
        
        // CRC
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(baos, (int)crc.getValue());
    }
    
    /**
     * Write 4-byte integer in big-endian format
     */
    private static void writeInt(ByteArrayOutputStream baos, int value) {
        baos.write((value >> 24) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write(value & 0xFF);
    }
    
    /**
     * Write 4-byte integer to byte array in big-endian format
     */
    private static void writeInt(byte[] array, int offset, int value) {
        array[offset] = (byte)((value >> 24) & 0xFF);
        array[offset + 1] = (byte)((value >> 16) & 0xFF);
        array[offset + 2] = (byte)((value >> 8) & 0xFF);
        array[offset + 3] = (byte)(value & 0xFF);
    }
}
