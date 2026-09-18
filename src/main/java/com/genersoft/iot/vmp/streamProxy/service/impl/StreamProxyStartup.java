package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每次应用启动完成后，为已启用的代理尝试一次拉流，不定时重试。
 */
@Slf4j
@Component
public class StreamProxyStartup {

    private final StreamProxyMapper mapper;
    private final IStreamProxyPlayService playService;
    private final UserSetting userSetting;
    private final AtomicBoolean started = new AtomicBoolean();

    public StreamProxyStartup(StreamProxyMapper mapper, IStreamProxyPlayService playService,
                              UserSetting userSetting) {
        this.mapper = mapper;
        this.playService = playService;
        this.userSetting = userSetting;
    }

    @Async("taskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void startOnApplicationReady() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        List<StreamProxy> proxies = mapper.selectAll(null, null, null);
        log.info("[启动自动拉流] 开始扫描代理，共 {} 条", proxies.size());
        for (int i = 0; i < proxies.size(); i++) {
            StreamProxy candidate = proxies.get(i);
            if (!shouldStart(candidate)) {
                continue;
            }
            int id = candidate.getId();
            try {
                // 批量拉流期间配置可能发生变化，执行前再次确认。
                StreamProxy proxy = mapper.select(id);
                if (proxy == null || !shouldStart(proxy)) {
                    continue;
                }
                log.info("[启动自动拉流] 开始，No: {}/{}，id：{}，app：{}，stream：{}", i, proxies.size(), id, proxy.getApp(), proxy.getStream());
                playService.startProxy(proxy, (code, msg, data) -> {
                    if (code == ErrorCode.SUCCESS.getCode()) {
                        log.info("[启动自动拉流] 成功，id：{}", id);
                    } else {
                        log.warn("[启动自动拉流] 失败，id：{}，原因：{}", id, msg);
                    }
                });
            } catch (Exception ex) {
                log.warn("[启动自动拉流] 异常，No: {}/{}，id：{}，原因：{}", i, proxies.size(), id, ex.getMessage());
            }
        }
        log.info("[启动自动拉流] 完成扫描代理，共 {} 条", proxies.size());
    }

    private boolean shouldStart(StreamProxy proxy) {
        return proxy.isEnable() && (proxy.getServerId() == null
                || Objects.equals(userSetting.getServerId(), proxy.getServerId()));
    }
}
