package com.autocare.platform.file;

public interface VirusScanner {
    enum Result { CLEAN, INFECTED, UNAVAILABLE }
    /** Implementations must enforce network timeouts and bound scanner output. */
    Result scan(byte[] bytes);
}
