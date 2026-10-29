package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.mqtt.UavOnlineStatusService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 每路一个独立任务，完成后延迟 timeout + 1 秒再检查；线程由调度池复用。 */
@Slf4j
@Component
public class StreamProxyBackgroundTask {
    private final StreamProxyMapper mapper;
    private final IStreamProxyPlayService playService;
    private final UserSetting settings;
    private final UavOnlineStatusService onlineStatus;
    private final Clock clock;
    private final ScheduledExecutorService workers;
    private final Map<String, PullTask> tasks = new ConcurrentHashMap<>();
    private volatile long startAt = Long.MAX_VALUE;
    private volatile boolean ready;
    private volatile boolean stopping;

    @Autowired
    public StreamProxyBackgroundTask(StreamProxyMapper mapper, IStreamProxyPlayService playService,
                                     UserSetting settings, UavOnlineStatusService onlineStatus) {
        this(mapper, playService, settings, Clock.systemUTC(), createWorkers(), onlineStatus);
    }

    StreamProxyBackgroundTask(StreamProxyMapper mapper, IStreamProxyPlayService playService,
                              UserSetting settings, Clock clock, ScheduledExecutorService workers,
                              UavOnlineStatusService onlineStatus) {
        this.mapper = mapper;
        this.playService = playService;
        this.settings = settings;
        this.onlineStatus = onlineStatus;
        this.clock = clock;
        this.workers = workers;
    }

    private static ScheduledExecutorService createWorkers() {
        AtomicInteger number = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(100, task -> {
            Thread thread = new Thread(task, "stream-proxy-background-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void ready() {
        if (!ready) {
            startAt = clock.millis() + 10000L;
            ready = true;
            log.info("[后台拉流] 应用启动完成，10秒后开始自动拉流");
        }
    }

    /** 仅同步配置；网络检查分别由每路任务执行，不在扫描中争抢拉流名额。 */
    @Scheduled(fixedDelay = 1000)
    public synchronized void check() {
        if (!ready || stopping || clock.millis() < startAt) {
            return;
        }
        Set<String> configured = new HashSet<>();
        for (StreamProxy proxy : mapper.selectAll(null, null, null)) {
            if (!eligible(proxy)) {
                continue;
            }
            String key = proxy.getApp() + "/" + proxy.getStream();
            configured.add(key);
            PullTask existing = tasks.get(key);
            if (existing != null && existing.id == proxy.getId()) {
                continue; // 等待重试或正在执行都复用同一任务。
            }
            if (existing != null) {
                existing.cancel();
            }
            PullTask task = new PullTask(key, proxy.getId());
            tasks.put(key, task);
            task.schedule(0);
        }
        tasks.forEach((key, task) -> {
            if (!configured.contains(key) && tasks.remove(key, task)) {
                task.cancel();
            }
        });
    }

    /** 立即撤销该配置对应的定时任务；旧任务的迟到回调不能重新调度。 */
    public synchronized void cancel(StreamProxy proxy) {
        String key = proxy.getApp() + "/" + proxy.getStream();
        PullTask task = tasks.get(key);
        if (task != null && task.id == proxy.getId() && tasks.remove(key, task)) {
            task.cancel();
        }
    }

    private boolean eligible(StreamProxy proxy) {
        return proxy.isEnable()
                && (proxy.getServerId() == null || Objects.equals(settings.getServerId(), proxy.getServerId()));
    }

    private class PullTask implements Runnable {
        private final String key;
        private final int id;
        private ScheduledFuture<?> future;
        private boolean cancelled;
        private boolean running;

        private PullTask(String key, int id) {
            this.key = key;
            this.id = id;
        }

        private synchronized void schedule(long delaySeconds) {
            if (!cancelled && !stopping && tasks.get(key) == this) {
                running = false;
                future = workers.schedule(this, delaySeconds, TimeUnit.SECONDS);
            }
        }

        private synchronized void cancel() {
            cancelled = true;
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void run() {
            synchronized (this) {
                if (cancelled || stopping || running || tasks.get(key) != this) {
                    return;
                }
                running = true;
            }
            long interval = 16L;
            String srcUrl = null;
            // 必须同时等到接口调用返回及收流结果完成，才安排下一轮。
            CompletableFuture<Void> submitted = new CompletableFuture<>();
            CompletableFuture<Long> completed = new CompletableFuture<>();
            submitted.thenCombine(completed, (returned, delay) -> delay).thenAccept(this::schedule);
            try {
                StreamProxy proxy = mapper.select(id);
                if (proxy != null) {
                    srcUrl = proxy.getSrcUrl();
                }
                if (proxy == null || !eligible(proxy)
                        || !Objects.equals(key, proxy.getApp() + "/" + proxy.getStream())) {
                    tasks.remove(key, this);
                    cancel();
                    return;
                }
                interval = (proxy.getTimeout() > 0 ? (long) proxy.getTimeout() : 15L) + 1L;
                final long delay = interval;
                playService.startInBackground(proxy, (code, msg, info) -> completed.complete(delay));
            } catch (Exception ex) {
                boolean abnormal = true;
                if (key.startsWith("drone/")) {
                    String uavSN = key.substring("drone/".length());
                    try {
                        abnormal = onlineStatus.isOnline(uavSN);
                        if (!abnormal) {
                            log.info("[后台拉流] 无人机不在线，拉流失败属于正常情况，uavSN：{}", uavSN);
                        }
                    } catch (Exception statusException) {
                        log.warn("[后台拉流] 查询无人机在线状态失败，uavSN：{}", uavSN, statusException);
                    }
                }
                if (abnormal) {
                    log.error("[后台拉流] 失败，app/stream：{}，拉流地址：{}，异常类型：{}，原因：{}",
                            key, srcUrl, ex.getClass().getName(), ex.getMessage(), ex);
                }
                // 配置查询也可能失败，此时同样保留任务，稍后重试。
                completed.complete(interval);
            } finally {
                submitted.complete(null);
            }
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopping = true;
        tasks.values().forEach(PullTask::cancel);
        tasks.clear();
        workers.shutdownNow();
    }
}
