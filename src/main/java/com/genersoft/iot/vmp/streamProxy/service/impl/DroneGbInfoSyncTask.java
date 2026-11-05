package com.genersoft.iot.vmp.streamProxy.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;

/** 独立同步 drone 拉流代理的国标通道信息。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "drone-gb-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DroneGbInfoSyncTask {
    private final StreamProxyMapper proxyMapper;
    private final IGbChannelService channelService;

    @Value("${drone-gb-sync.device-url}")
    private String deviceUrl;

    @Value("${drone-gb-sync.timeout-ms}")
    private int timeoutMs;

    public DroneGbInfoSyncTask(StreamProxyMapper proxyMapper, IGbChannelService channelService) {
        this.proxyMapper = proxyMapper;
        this.channelService = channelService;
    }

    @Scheduled(initialDelayString = "${drone-gb-sync.initial-delay-ms}",
            fixedDelayString = "${drone-gb-sync.interval-ms}")
    public void sync() {
        try {
            for (StreamProxy proxy : proxyMapper.selectAll(null, null, null)) {
                if (!"drone".equals(proxy.getApp())) {
                    continue;
                }
                try {
                    syncProxy(proxy);
                } catch (Exception ex) {
                    log.warn("[无人机国标同步] 同步失败，stream={}", proxy.getStream(), ex);
                }
            }
        } catch (Exception ex) {
            log.warn("[无人机国标同步] 查询代理失败，等待下次同步", ex);
        }
    }

    private void syncProxy(StreamProxy proxy) throws Exception {
        if (proxy.getStream() == null || proxy.getStream().trim().isEmpty()) {
            return;
        }
        String url = deviceUrl + (deviceUrl.contains("?") ? "&" : "?")
                + "sn=" + URLEncoder.encode(proxy.getStream(), "UTF-8");
        JSONObject result;
        try (HttpResponse response = HttpRequest.get(url).timeout(timeoutMs).execute()) {
            if (!response.isOk()) {
                throw new IllegalStateException("设备接口 HTTP 状态异常: " + response.getStatus());
            }
            result = JSON.parseObject(response.body());
        }
        JSONObject data = result == null ? null : result.getJSONObject("data");
        if (result == null || result.getIntValue("code") != 200 || data == null) {
            throw new IllegalStateException("设备接口返回失败或缺少 data");
        }
        String code = data.getString("nsCode");
        String prefix = data.getString("prefixNo");
        String callsign = data.getString("callsign");
        if (code == null || code.trim().isEmpty() || prefix == null || prefix.trim().isEmpty()
                || callsign == null || callsign.trim().isEmpty()) {
            throw new IllegalStateException("设备接口缺少 nsCode、prefixNo 或 callsign");
        }
        // 重新读取关联信息，保留其他国标配置并避免使用请求前的旧数据。
        StreamProxy current = proxyMapper.select(proxy.getId());
        if (current == null || !"drone".equals(current.getApp())
                || !proxy.getStream().equals(current.getStream())) {
            return;
        }
        String name = prefix + callsign;
        if (current.getGbId() > 0 && code.equals(current.getGbDeviceId()) && name.equals(current.getGbName())) {
            return;
        }
        current.setGbDeviceId(code);
        current.setGbName(name);
        if (current.getGbId() > 0) {
            channelService.update(current.buildCommonGBChannel());
        } else {
            channelService.add(current.buildCommonGBChannel());
        }
    }
}
