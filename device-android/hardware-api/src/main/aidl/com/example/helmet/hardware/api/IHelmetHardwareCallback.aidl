package com.example.helmet.hardware.api;

oneway interface IHelmetHardwareCallback {
    void onFrame(int version, int flags, int type, int sequence, in byte[] payload);
    void onLinkStateChanged(int state, String detail);
}
