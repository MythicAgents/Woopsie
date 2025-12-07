package com.woopsie.utils;

import com.sun.jna.NativeLong;
import com.sun.jna.ptr.ByReference;

/**
 * Helper class for NativeLong by reference (pointer to NativeLong)
 */
public class NativeLongByReference extends ByReference {
    
    public NativeLongByReference() {
        this(new NativeLong(0));
    }
    
    public NativeLongByReference(NativeLong value) {
        super(NativeLong.SIZE);
        setValue(value);
    }
    
    public void setValue(NativeLong value) {
        getPointer().setNativeLong(0, value);
    }
    
    public NativeLong getValue() {
        return getPointer().getNativeLong(0);
    }
}
