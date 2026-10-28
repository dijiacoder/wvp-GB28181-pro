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

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
    private volatile boolean stopping;

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
        if (proxies.isEmpty() || stopping) {
            return;
        }
        int concurrency = Math.max(1, userSetting.getStreamProxyStartupConcurrency());
        log.info("[启动自动拉流] 并发上限：{}", concurrency);
        AtomicInteger next = new AtomicInteger();
        AtomicInteger threadNumber = new AtomicInteger();
        // 独立的小线程池，避免等待拉流结果时占满处理 hook 的公共线程池。
        ExecutorService workers = Executors.newFixedThreadPool(concurrency, task -> {
            Thread thread = new Thread(task, "stream-proxy-startup-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            // 只提交 concurrency 个 worker，不为每个代理创建一个排队任务。
            for (int worker = 0; worker < concurrency; worker++) {
                workers.execute(() -> {
                    int index;
                    while (!stopping && !Thread.currentThread().isInterrupted()
                            && (index = next.getAndIncrement()) < proxies.size()) {
                        startOne(proxies.get(index), index + 1, proxies.size());
                    }
                });
            }
            workers.shutdown();
            while (!workers.awaitTermination(1, TimeUnit.SECONDS)) {
                if (stopping) {
                    return;
                }
            }
            log.info("[启动自动拉流] 完成扫描代理，共 {} 条", proxies.size());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownNow();
        }
    }

    private void startOne(StreamProxy candidate, int number, int total) {
        if (!shouldStart(candidate)) {
            return;
        }
        int id = candidate.getId();
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            // 批量拉流期间配置可能发生变化，执行前再次确认。
            StreamProxy proxy = mapper.select(id);
            if (proxy == null || !shouldStart(proxy)) {
                return;
            }
            log.info("[启动自动拉流] 开始，No: {}/{}，id：{}，app：{}，stream：{}", number, total, id, proxy.getApp(), proxy.getStream());
            playService.startProxy(proxy, (code, msg, data) -> {
                if (!result.complete(null)) {
                    return;
                }
                if (code == ErrorCode.SUCCESS.getCode()) {
                    log.info("[启动自动拉流] 成功，id：{}", id);
                } else {
                    log.warn("[启动自动拉流] 失败，id：{}，原因：{}", id, msg);
                }
            });
            // 接口返回不一定代表收流完成，回调完成后才释放并发名额。
            result.get(Math.max(1, userSetting.getPlayTimeout()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            if (result.complete(null)) {
                log.warn("[启动自动拉流] 等待结果超时，No: {}/{}，id：{}", number, total, id);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("[启动自动拉流] 异常，No: {}/{}，id：{}，原因：{}", number, total, id, ex.getMessage());
        } finally {
            // 忽略超时、异常或关闭后迟到的回调；这里不重试，也不停止 ZLM 任务。
            result.complete(null);
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
    }

    private boolean shouldStart(StreamProxy proxy) {
        return proxy.isEnable() && (proxy.getServerId() == null
                || Objects.equals(userSetting.getServerId(), proxy.getServerId()));
    }
}
