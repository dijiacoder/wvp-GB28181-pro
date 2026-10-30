package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.mqtt.UavHeartbeatEvent;
import com.genersoft.iot.vmp.mqtt.UavOnlineStatusService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.utils.SikongApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.ObjectUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/** MQTT 驱动，每个 SN 在一次在线会话中只持有一个异步任务。 */
@Slf4j
@Component
public class StreamProxyBackgroundTask {
    @Autowired
    @Lazy
    private IStreamProxyService streamProxyService;

    private final StreamProxyMapper mapper;
    private final IStreamProxyPlayService playService;
    private final UserSetting settings;
    private final UavOnlineStatusService onlineStatus;
    private final ScheduledExecutorService workers;
    private final Map<String, PullTask> tasks = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    @Value("${sikong.base-url:}") private String baseUrl;
    @Value("${sikong.username:}") private String username;
    @Value("${sikong.password:}") private String password;
    @Value("${sikong.organization-key:}") private String organizationKey;

    @Autowired
    public StreamProxyBackgroundTask(StreamProxyMapper mapper, IStreamProxyPlayService playService,
                                     UserSetting settings, UavOnlineStatusService onlineStatus) {
        this(mapper, playService, settings, onlineStatus, new ScheduledThreadPoolExecutor(16));
    }

    StreamProxyBackgroundTask(StreamProxyMapper mapper, IStreamProxyPlayService playService,
                              UserSetting settings, UavOnlineStatusService onlineStatus,
                              ScheduledExecutorService workers) {
        this.workers = workers;
        this.mapper = mapper;
        this.playService = playService;
        this.settings = settings;
        this.onlineStatus = onlineStatus;
        if (workers instanceof ScheduledThreadPoolExecutor) {
            ((ScheduledThreadPoolExecutor) workers).setRemoveOnCancelPolicy(true);
            ((ScheduledThreadPoolExecutor) workers).setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
    }

    @EventListener
    public void onHeartbeat(UavHeartbeatEvent event) {
        if (stopping || tasks.containsKey(event.getSn())) {
            return;
        }
        PullTask task = new PullTask(event.getSn());
        // containsKey 仅是高频心跳的快速路径，原子注册保证并发首次上报也只启动一次。
        if (tasks.putIfAbsent(event.getSn(), task) == null) {
            task.schedule(0);
        }
    }

    public synchronized void cancel(StreamProxy proxy) {
        if (!"drone".equals(proxy.getApp())) {
            return;
        }
        PullTask task = tasks.get(proxy.getStream());
        if (task != null) {
            // 保留会话占位，避免下一条心跳立即重建被手动删除/停用的代理。
            task.disabled = true;
        }
    }

    SikongApiUtils client() throws IOException {
        return new SikongApiUtils(baseUrl, username, password, organizationKey);
    }

    JSONObject getConverter(SikongApiUtils api, String sn) throws IOException {
        return api.getLiveStreamConverter(sn);
    }

    void setEnabled(SikongApiUtils api, String uuid, boolean enable) throws IOException {
        api.setLiveStreamConverterEnabled(uuid, enable);
    }

    private class PullTask implements Runnable {
        private final String sn;
        private StreamProxy proxy;
        private boolean initialized;
        private volatile boolean disabled;

        PullTask(String sn) { this.sn = sn; }

        void schedule(long seconds) {
            if (stopping) {
                tasks.remove(sn, this);
                return;
            }
            if (tasks.get(sn) != this) {
                return;
            }
            try {
                workers.schedule(this, seconds, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ex) {
                tasks.remove(sn, this);
                if (!stopping) {
                    throw ex;
                }
            }
        }

        boolean online() {
            return !stopping && tasks.get(sn) == this && onlineStatus.isOnline(sn);
        }

        @Override
        public void run() {
            long delay = 2;
            boolean submitted = false;
            try {
                if (!online()) {
                    tasks.remove(sn, this);
                    return;
                }
                if (disabled) {
                    return;
                }
                if (!initialized) {
                    SikongApiUtils api = client();
                    JSONObject converter = getConverter(api, sn);
                    if (converter == null) {
                        log.warn("[MQTT拉流] 未找到 RTSP 转换器，SN={}", sn);
                        disabled = true;
                        return;
                    }
                    String uuid = converter.getString("uuid");
                    if (!online()) { return; }
                    if (Boolean.TRUE.equals(converter.getBoolean("enable"))) {
                        setEnabled(api, uuid, false);
                        Thread.sleep(1000L);
                        if (!online()) { return; }
                        setEnabled(api, uuid, true);
                    } else if (Boolean.FALSE.equals(converter.getBoolean("enable"))) {
                        setEnabled(api, uuid, true);
                    }
                    Thread.sleep(1000L);
                    if (!online()) { return; }
                    JSONObject option = converter.getJSONObject("schema_option");
                    String url = option == null ? null : option.getString("rtsp_url");
                    if (url == null || url.trim().isEmpty()) {
                        throw new IOException("转换器缺少 schema_option.rtsp_url");
                    }
                    proxy = streamProxyService.getStreamProxyByAppAndStream("drone", sn);
                    if (proxy == null) {
                        proxy = new StreamProxy();
                        proxy.setApp("drone");
                        proxy.setStream(sn);
                        proxy.setType("default");
                        proxy.setTimeout(15);
                        proxy.setRtspType("0");
                        proxy.setEnable(true);
                        proxy.setServerId(settings.getServerId());
                        proxy.setSrcUrl(url);
                        streamProxyService.add(proxy);
                    } else if (eligible(proxy)) {
                        proxy.setSrcUrl(url);
                        if (proxy.getId() == 0) {
                            throw new ControllerException(ErrorCode.ERROR400.getCode(), "缺少代理信息的ID");
                        }
                        if (ObjectUtils.isEmpty(proxy.getRelatesMediaServerId())) {
                            proxy.setRelatesMediaServerId(null);
                        }
                        if (ObjectUtils.isEmpty(proxy.getGbId())) {
                            proxy.setGbDeviceId(null);
                        }
                        streamProxyService.update(proxy);
                    } else {
                        disabled = true;
                        return;
                    }
                    initialized = true;
                }
                StreamProxy current = mapper.select(proxy.getId());
                if (current == null || !eligible(current)) {
                    disabled = true;
                    return;
                }
                delay = (current.getTimeout() > 0 ? current.getTimeout() : 15L) + 1;
                if (!online()) { return; }
                final long retryDelay = delay;
                CompletableFuture<Void> returned = new CompletableFuture<>();
                CompletableFuture<Void> completed = new CompletableFuture<>();
                returned.thenCombine(completed, (a, b) -> null).thenRun(() -> schedule(retryDelay));
                try {
                    playService.startInBackground(current, (code, msg, info) -> completed.complete(null));
                } catch (Exception ex) {
                    completed.complete(null);
                    throw ex;
                } finally {
                    submitted = true;
                    returned.complete(null);
                }
            } catch (Exception ex) {
                log.warn("[MQTT拉流] 任务失败，在线时将重试，SN={}，异常类型={}", sn, ex.getClass().getSimpleName());
            } finally {
                if (!submitted) { schedule(delay); }
            }
        }
    }

    private boolean eligible(StreamProxy proxy) {
        return proxy.isEnable() && (proxy.getServerId() == null
                || Objects.equals(settings.getServerId(), proxy.getServerId()));
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopping = true;
        tasks.clear();
        workers.shutdownNow();
    }
}
