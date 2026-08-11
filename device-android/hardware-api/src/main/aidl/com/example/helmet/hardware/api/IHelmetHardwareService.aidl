package com.example.helmet.hardware.api;

import android.os.Bundle;
import com.example.helmet.hardware.api.IHelmetHardwareCallback;

interface IHelmetHardwareService {
    void openPort(String devicePath, int baudRate);
    void closePort();
    void sendFrame(int type, int flags, int sequence, in byte[] payload);
    Bundle getDiagnostics();
    void registerCallback(IHelmetHardwareCallback callback);
    void unregisterCallback(IHelmetHardwareCallback callback);
}
