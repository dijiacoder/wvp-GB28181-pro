package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService;
import com.genersoft.iot.vmp.utils.SikongApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 清理司空转换器中已不存在的 drone 拉流代理。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "drone-proxy-cleanup", name = "enabled", havingValue = "true")
public class DroneProxyCleanupTask {
    private final StreamProxyMapper proxyMapper;
    private final IStreamProxyService proxyService;

    @Value("${sikong.base-url}") private String baseUrl;
    @Value("${sikong.username}") private String username;
    @Value("${sikong.password}") private String password;
    @Value("${sikong.organization-key}") private String organizationKey;

    public DroneProxyCleanupTask(StreamProxyMapper proxyMapper, IStreamProxyService proxyService) {
        this.proxyMapper = proxyMapper;
        this.proxyService = proxyService;
    }

    @Scheduled(initialDelayString = "${drone-proxy-cleanup.initial-delay-ms}",
            fixedDelayString = "${drone-proxy-cleanup.interval-ms}")
    public void cleanup() {
        try {
            // 先读取本地快照，避免删除获取司空列表期间新建的代理。
            List<StreamProxy> proxies = proxyMapper.selectAll(null, null, null);
            SikongApiUtils api = new SikongApiUtils(baseUrl, username, password, organizationKey);
            for (StreamProxy proxy : proxies) {
                if (!"drone".equals(proxy.getApp()) || proxy.getStream() == null
                        || proxy.getStream().trim().isEmpty()) {
                    continue;
                }
                try {
                    // 与司空中该 SN 对应的 RTSP 转换器比对，停用的转换器仍视为存在。
                    if (api.getLiveStreamConverter(proxy.getStream()) != null) {
                        continue;
                    }
                    StreamProxy current = proxyMapper.select(proxy.getId());
                    if (current == null || !"drone".equals(current.getApp())
                            || !proxy.getStream().equals(current.getStream())) {
                        continue;
                    }
                    proxyService.delete(current.getId());
                    log.info("[无人机代理清理] 已请求删除司空中不存在的代理，stream={}", current.getStream());
                } catch (Exception ex) {
                    log.warn("[无人机代理清理] 查询或删除失败，stream={}", proxy.getStream(), ex);
                }
            }
        } catch (Exception ex) {
            log.warn("[无人机代理清理] 巡检失败，等待下次执行", ex);
        }
    }
}
