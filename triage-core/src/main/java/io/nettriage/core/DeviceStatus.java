package io.nettriage.core;

/** Whether EdgeFleet holds a control session with a router, and since when. */
public record DeviceStatus(String systemIp, boolean connected, long since) {
}
