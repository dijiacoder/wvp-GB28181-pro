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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
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
    // 独立于 PullTask，避免心跳抖动/任务重建重复停启同一转换器；应用重启后重新初始化。
    private final Set<String> initializedConverters = ConcurrentHashMap.newKeySet();
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
        while (!stopping) {
            PullTask task = tasks.get(event.getSn());
            boolean created = false;
            if (task == null) {
                PullTask candidate = new PullTask(event.getSn());
                PullTask existing = tasks.putIfAbsent(event.getSn(), candidate);
                task = existing == null ? candidate : existing;
                created = existing == null;
            }
            synchronized (task) {
                if (tasks.get(event.getSn()) != task) {
                    continue;
                }
                long now = nanoTime();
                if (task.lastHeartbeat != Long.MIN_VALUE
                        && now - task.lastHeartbeat >= TimeUnit.SECONDS.toNanos(UavOnlineStatusService.ONLINE_TIMEOUT_SECONDS)) {
                    task.generation++;
                    task.replacement = null;
                    task.disabled = false;
                }
                task.lastHeartbeat = now;
            }
            if (created) {
                task.schedule(0);
            }
            return;
        }
    }

    long nanoTime() { return System.nanoTime(); }

    public void cancel(StreamProxy proxy) {
        if (!"drone".equals(proxy.getApp())) { return; }
        PullTask task = tasks.get(proxy.getStream());
        if (task != null) {
            // 与写入、提交共用每 SN 的锁；调用者必须在获取播放状态锁之前取消。
            synchronized (task.mutationLock) {
                synchronized (task) {
                    StreamProxy current = task.replacement != null ? task.replacement : task.proxy;
                    if (current != null && current.getId() > 0 && current.getId() != proxy.getId()) {
                        return; // 旧配置的迟到取消不能停用已替换的新配置。
                    }
                    task.disabled = true;
                }
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK && tasks.get(proxy.getStream()) == task) {
                            StreamProxyBackgroundTask.this.resume(proxy);
                        }
                    }
                });
            }
        }
    }

    /** 替换配置提交后恢复已有在线会话，不等待下一次开机。 */
    public void resume(StreamProxy proxy) {
        if (!"drone".equals(proxy.getApp())) { return; }
        PullTask task = tasks.get(proxy.getStream());
        if (task != null) {
            synchronized (task) {
                task.replacement = proxy;
                task.generation++;
                task.disabled = false;
            }
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

    void awaitConverter() throws InterruptedException { Thread.sleep(1000L); }

    private class PullTask implements Runnable {
        private final String sn;
        private StreamProxy proxy;
        private boolean initialized;
        private boolean converterSetupAttempted;
        private String converterUrl;
        private volatile boolean disabled;
        private final Object mutationLock = new Object();
        private long lastHeartbeat = Long.MIN_VALUE;
        private volatile long generation;
        private long runningGeneration = -1;
        private StreamProxy replacement;

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
            return !stopping && !disabled && generation == runningGeneration
                    && tasks.get(sn) == this && onlineStatus.isOnline(sn);
        }

        private synchronized void disableCurrentSession() {
            if (generation == runningGeneration) { disabled = true; }
        }

        @Override
        public void run() {
            long delay = 2;
            boolean submitted = false;
            String stage = "检查在线状态";
            String rtspUrl = proxy == null ? null : proxy.getSrcUrl();
            try {
                synchronized (this) {
                    if (stopping || tasks.get(sn) != this) { return; }
                    if (!onlineStatus.isOnline(sn)) {
                        tasks.remove(sn, this);
                        return;
                    }
                    if (disabled) { return; }
                    if (runningGeneration != generation) {
                        runningGeneration = generation;
                        converterSetupAttempted = false;
                        initialized = false;
                        proxy = null;
                        converterUrl = null;
                        if (replacement != null) {
                            proxy = replacement;
                            converterUrl = proxy.getSrcUrl();
                            converterSetupAttempted = true;
                            initialized = true;
                            replacement = null;
                        }
                    }
                }
                if (!converterSetupAttempted) {
                    stage = "司空认证";
                    SikongApiUtils api = client();
                    stage = "查询转换器";
                    JSONObject converter = getConverter(api, sn);
                    if (converter == null) {
                        log.warn("[MQTT拉流] 未找到 RTSP 转换器，SN={}", sn);
                        disableCurrentSession();
                        return;
                    }
                    JSONObject option = converter.getJSONObject("schema_option");
                    rtspUrl = option == null ? null : option.getString("rtsp_url");
                    stage = "检查拉流地址";
                    if (rtspUrl == null || rtspUrl.trim().isEmpty()) {
                        throw new IOException("转换器缺少 schema_option.rtsp_url");
                    }
                    String uuid = converter.getString("uuid");
                    if (!online()) { return; }
                    converterUrl = rtspUrl;
                    if (uuid == null || uuid.trim().isEmpty()) {
                        throw new IOException("转换器缺少 uuid");
                    }
                    Boolean enabled = converter.getBoolean("enable");
                    if (enabled == null) { throw new IOException("转换器缺少 enable"); }
                    converterSetupAttempted = true;
                    boolean firstInitialization = initializedConverters.add(sn + "/" + uuid);
                    if (firstInitialization && enabled) {
                        stage = "关闭转换器";
                        log.info("[MQTT拉流] 首次初始化，先关闭转换器，SN={}，uuid={}，serverId={}",
                                sn, uuid, settings.getServerId());
                        setEnabled(api, uuid, false);
                        awaitConverter();
                        if (!online()) { return; }
                    }
                    if (firstInitialization || !enabled) {
                        stage = "开启转换器";
                        log.info("[MQTT拉流] 开启转换器，SN={}，uuid={}，serverId={}",
                                sn, uuid, settings.getServerId());
                        setEnabled(api, uuid, true);
                        awaitConverter();
                    }
                    if (!online()) { return; }
                }
                rtspUrl = converterUrl;
                if (!initialized) {
                    String url = converterUrl;
                    stage = "查询代理";
                    synchronized (mutationLock) {
                        if (!online()) { return; }
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
                            stage = "新增代理";
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
                            stage = "更新代理";
                            streamProxyService.update(proxy);
                        } else {
                            disableCurrentSession();
                            return;
                        }
                        initialized = true;
                    }
                }
                stage = "读取代理配置";
                StreamProxy current = mapper.select(proxy.getId());
                if (current == null || !eligible(current)) {
                    disableCurrentSession();
                    return;
                }
                delay = (current.getTimeout() > 0 ? current.getTimeout() : 15L) + 1;
                if (!online()) { return; }
                final long retryDelay = delay;
                CompletableFuture<Void> returned = new CompletableFuture<>();
                CompletableFuture<Void> completed = new CompletableFuture<>();
                returned.thenCombine(completed, (a, b) -> null).thenRun(() -> schedule(retryDelay));
                try {
                    stage = "发起拉流";
                    rtspUrl = current.getSrcUrl();
                    synchronized (mutationLock) {
                        if (!online()) { completed.complete(null); return; }
                        playService.startInBackground(current, (code, msg, info) -> completed.complete(null));
                    }
                } catch (Exception ex) {
                    completed.complete(null);
                    throw ex;
                } finally {
                    submitted = true;
                    returned.complete(null);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                Integer errorCode = ex instanceof ControllerException ? ((ControllerException) ex).getCode() : null;
                log.warn("[MQTT拉流] 任务失败，在线时将重试，SN={}，阶段={}，RTSP URL={}，错误码={}，异常类型={}，原因={}",
                        sn, stage, rtspUrl, errorCode, ex.getClass().getSimpleName(), ex.getMessage(), ex);
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
        initializedConverters.clear();
        workers.shutdownNow();
    }
}
