package com.nexusapi.server.modules.devicekey.enums;

public enum DeviceActivationKeyStatus {
    ACTIVE("active"), DISABLED("disabled"), EXPIRED("expired");
    private final String value;
    DeviceActivationKeyStatus(String value) { this.value = value; }
    public String value() { return value; }
}
