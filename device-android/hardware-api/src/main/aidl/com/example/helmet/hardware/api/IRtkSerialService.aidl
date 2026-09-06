package com.example.helmet.hardware.api;

import android.os.Bundle;
import com.example.helmet.hardware.api.IRtkSerialCallback;

interface IRtkSerialService {
    void openPort(String devicePath, int baudRate);
    void closePort();
    void write(in byte[] bytes);
    Bundle getDiagnostics();
    void registerCallback(IRtkSerialCallback callback);
    void unregisterCallback(IRtkSerialCallback callback);
}
