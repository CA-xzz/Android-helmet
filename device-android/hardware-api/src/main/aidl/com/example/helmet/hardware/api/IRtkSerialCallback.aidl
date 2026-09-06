package com.example.helmet.hardware.api;

oneway interface IRtkSerialCallback {
    void onBytes(in byte[] bytes);
    void onLinkStateChanged(int state, String detail);
}
