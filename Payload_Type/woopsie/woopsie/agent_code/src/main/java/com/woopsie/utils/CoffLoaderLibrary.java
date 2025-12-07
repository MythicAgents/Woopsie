package com.woopsie.utils;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;

public interface CoffLoaderLibrary extends Library {
    
    /**
     * Load DLL from specific path
     */
    static CoffLoaderLibrary loadFrom(String dllPath) {
        return Native.load(dllPath, CoffLoaderLibrary.class);
    }
    
    /**
     * Maps to: int run_bof(const unsigned char* bof_data, size_t bof_len, 
     *                       const unsigned char* args_data, size_t args_len, 
     *                       unsigned char** output, size_t* output_len);
     */
    int run_bof(Pointer bofData, NativeLong bofLen,
                Pointer argsData, NativeLong argsLen,
                PointerByReference output, NativeLongByReference outputLen);
}
