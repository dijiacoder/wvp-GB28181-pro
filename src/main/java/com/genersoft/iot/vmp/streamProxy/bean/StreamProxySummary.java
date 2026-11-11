package com.genersoft.iot.vmp.streamProxy.bean;

import lombok.Data;

/** 查询接口返回的代理信息。 */
@Data
public class StreamProxySummary {
    private String app;
    private String stream;
    private String streamName;
    private String rtmpUrl;
    private String flvUrl;
    private int pulling;
    private int enable;
}
