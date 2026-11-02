package com.genersoft.iot.vmp.streamProxy.bean;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 查询接口返回的代理信息。 */
@Data
public class StreamProxySummary {
    private String app;
    private String stream;
    @JsonProperty("out_url")
    private String outUrl;
    private int pulling;
    private int enable;
}
