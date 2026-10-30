package com.genersoft.iot.vmp.mqtt;

/** 仅在有效 OSD 心跳写入在线状态后发布。 */
public class UavHeartbeatEvent {
    private final String sn;

    public UavHeartbeatEvent(String sn) {
        this.sn = sn;
    }

    public String getSn() {
        return sn;
    }
}
