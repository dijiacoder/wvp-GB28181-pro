package com.genersoft.iot.vmp.gb28181.task.streamPull;

import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.controller.StreamProxyController;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class StreamPullTaskRunner {

    @Autowired
    private StreamProxyController streamProxyController;

    private ScheduledExecutorService executorService;

    @PostConstruct
    public void init() {
        // 创建单线程调度器
        executorService = Executors.newSingleThreadScheduledExecutor();
        // 初始延迟30秒，之后每60秒执行一次
        executorService.scheduleWithFixedDelay(
                this::pullProxyStream,
                30, 60, TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    public void pullProxyStream() {
        try {
            PageInfo<StreamProxy> list = streamProxyController.list(1, 100, null, false, null);
            if (list != null && list.getList() != null && !list.getList().isEmpty()) {
                for (StreamProxy streamProxy : list.getList()) {
                    log.info("[{}] 尚未拉流, try start it.", streamProxy.getSrcUrl());
                    streamProxyController.start(null, streamProxy.getId());
                }
            }
        } catch (Exception e) {
            log.error("pullProxyStream error", e);
        }
    }
}