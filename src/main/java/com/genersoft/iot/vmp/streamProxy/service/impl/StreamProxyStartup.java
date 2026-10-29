package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 启动10秒后上报国标通道在线；实际拉流统一由 StreamProxyBackgroundTask 调度。 */
@Slf4j
@Component
public class StreamProxyStartup {
    private static final String TASK_KEY = "stream-proxy-startup-online";
    private final StreamProxyMapper mapper;
    private final UserSetting settings;
    private final IGbChannelService channels;
    private final DynamicTask tasks;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopping;

    public StreamProxyStartup(StreamProxyMapper mapper, UserSetting settings,
                              IGbChannelService channels, DynamicTask tasks) {
        this.mapper = mapper;
        this.settings = settings;
        this.channels = channels;
        this.tasks = tasks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startOnApplicationReady() {
        if (!stopping && started.compareAndSet(false, true)) {
            tasks.startDelay(TASK_KEY, this::reportOnline, 10000);
        }
    }

    private void reportOnline() {
        if (stopping) {
            return;
        }
        for (StreamProxy candidate : mapper.selectAll(null, null, null)) {
            if (stopping) {
                return;
            }
            StreamProxy proxy = mapper.select(candidate.getId());
            if (proxy == null || !proxy.isEnable() || proxy.getGbId() <= 0
                    || (proxy.getServerId() != null && !Objects.equals(settings.getServerId(), proxy.getServerId()))) {
                continue;
            }
            try {
                proxy.setGbStatus("ON");
                channels.online(proxy.buildCommonGBChannel());
            } catch (Exception ex) {
                log.warn("[启动自动拉流] 国标在线上报失败，id：{}，原因：{}", proxy.getId(), ex.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        tasks.stop(TASK_KEY);
    }
}
