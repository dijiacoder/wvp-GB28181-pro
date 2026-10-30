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
    // 关闭后的开启义务不随任务或会话重建而丢失；成功开启后才清除。
    private final Map<String, String> convertersPendingEnable = new ConcurrentHashMap<>();
    // 开启成功后须重新获取地址并替换流媒体代理；刷新失败保留标记供下一轮重试。
    private final Map<String, String> convertersPendingRefresh = new ConcurrentHashMap<>();
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

    void awaitConverter() throws InterruptedException { Thread.sleep(2000L); }

    private static class PullResult {
        private final int code;
        private final String message;

        private PullResult(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

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
        private int consecutivePullFailures;

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

        private boolean active() {
            return !stopping && !disabled && generation == runningGeneration && tasks.get(sn) == this;
        }

        boolean online() {
            // Redis 查询期间可能发生取消或会话切换，返回前再次校验本地状态。
            return active() && onlineStatus.isOnline(sn) && active();
        }

        private boolean changeConverter(SikongApiUtils api, String uuid, boolean enable) throws IOException {
            synchronized (mutationLock) {
                if (!online()) { return false; }
                // 请求超时不能确定远端是否已关闭，也应保留开启补偿。
                if (!enable) { convertersPendingEnable.put(sn, uuid); }
                setEnabled(api, uuid, enable);
                if (enable) {
                    convertersPendingRefresh.put(sn, uuid);
                    convertersPendingEnable.remove(sn, uuid);
                }
                return true;
            }
        }

        private StreamProxy createProxy(String url) {
            StreamProxy created = new StreamProxy();
            created.setApp("drone");
            created.setStream(sn);
            created.setType("default");
            created.setTimeout(15);
            created.setRtspType("0");
            created.setEnable(true);
            created.setServerId(settings.getServerId());
            created.setSrcUrl(url);
            return created;
        }

        private void refreshProxyAfterConverterChange() throws IOException {
            String uuid = convertersPendingRefresh.get(sn);
            if (uuid == null || !online()) { return; }
            SikongApiUtils api = client();
            JSONObject converter = getConverter(api, sn);
            if (converter == null || !uuid.equals(converter.getString("uuid"))) {
                throw new IOException("刷新代理时未找到对应转换器");
            }
            JSONObject option = converter.getJSONObject("schema_option");
            String url = option == null ? null : option.getString("rtsp_url");
            if (url == null || url.trim().isEmpty()) {
                throw new IOException("刷新代理时转换器缺少 schema_option.rtsp_url");
            }
            synchronized (mutationLock) {
                if (!online()) { return; }
                StreamProxy current = streamProxyService.getStreamProxyByAppAndStream("drone", sn);
                if (current != null) {
                    if (!eligible(current)) { disableCurrentSession(); return; }
                    if (current.getId() == 0) { throw new IOException("刷新代理时缺少代理ID"); }
                    // update 只更新数据库；清理 ZLM 旧代理后，下一次提交才会使用新地址。
                    playService.stopProxy(current);
                    current.setSrcUrl(url);
                    current.setPulling(false);
                    current.setMediaServerId(null);
                    current.setStreamKey(null);
                    if (ObjectUtils.isEmpty(current.getRelatesMediaServerId())) {
                        current.setRelatesMediaServerId(null);
                    }
                    if (ObjectUtils.isEmpty(current.getGbId())) { current.setGbDeviceId(null); }
                    streamProxyService.update(current);
                    proxy = current;
                    initialized = true;
                } else {
                    proxy = createProxy(url);
                    streamProxyService.add(proxy);
                    initialized = true;
                }
                converterUrl = url;
                convertersPendingRefresh.remove(sn, uuid);
                log.info("[MQTT拉流] 转换器开启后已刷新代理，SN={}，RTSP URL={}", sn, url);
            }
        }

        private synchronized void disableCurrentSession() {
            if (generation == runningGeneration) { disabled = true; }
        }

        private synchronized void recordPullResult(long attemptGeneration, PullResult result,
                                                   String url, long retryDelay) {
            if (!disabled && tasks.get(sn) == this && generation == attemptGeneration) {
                if (result.code == ErrorCode.SUCCESS.getCode()) {
                    consecutivePullFailures = 0;
                    log.info("[MQTT拉流] 拉流成功，SN={}，RTSP URL={}，连续失败次数已清零，{}秒后检查流状态",
                            sn, url, retryDelay);
                } else {
                    consecutivePullFailures++;
                    log.warn("[MQTT拉流] 拉流失败，SN={}，RTSP URL={}，错误码={}，原因={}，连续失败次数={}/5，{}秒后重试{}",
                            sn, url, result.code, result.message, consecutivePullFailures, retryDelay,
                            consecutivePullFailures >= 5 ? "，重试前将关闭并开启转换器" : "");
                }
            }
        }

        private synchronized boolean consumeConverterRestart() {
            if (consecutivePullFailures < 5) { return false; }
            // 无论停启请求是否成功，每五次新的拉流失败才允许再次停启。
            consecutivePullFailures = 0;
            return true;
        }

        private void restartConverterAfterFailures() throws IOException, InterruptedException {
            SikongApiUtils api = client();
            JSONObject converter = getConverter(api, sn);
            if (converter == null) { throw new IOException("重置时未找到转换器"); }
            String uuid = converter.getString("uuid");
            if (uuid == null || uuid.trim().isEmpty()) { throw new IOException("转换器缺少 uuid"); }
            if (!online()) { return; }
            log.warn("[MQTT拉流] 连续5次拉流失败，重启转换器，SN={}，uuid={}", sn, uuid);
            if (!changeConverter(api, uuid, false)) { return; }
            log.info("[MQTT拉流] 转换器已关闭，SN={}，uuid={}", sn, uuid);
            awaitConverter();
            if (!online()) { return; }
            if (!changeConverter(api, uuid, true)) { return; }
            log.info("[MQTT拉流] 转换器已开启，继续拉流，SN={}，uuid={}", sn, uuid);
            awaitConverter();
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
                        consecutivePullFailures = 0;
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
                String pendingUuid = convertersPendingEnable.get(sn);
                if (pendingUuid != null) {
                    stage = "恢复转换器开启";
                    SikongApiUtils api = client();
                    if (!changeConverter(api, pendingUuid, true)) { return; }
                    log.info("[MQTT拉流] 已恢复待开启的转换器，SN={}，uuid={}", sn, pendingUuid);
                    awaitConverter();
                    if (!online()) { return; }
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
                        if (!changeConverter(api, uuid, false)) { return; }
                        awaitConverter();
                        if (!online()) { return; }
                    }
                    if (firstInitialization || !enabled) {
                        stage = "开启转换器";
                        log.info("[MQTT拉流] 开启转换器，SN={}，uuid={}，serverId={}",
                                sn, uuid, settings.getServerId());
                        if (!changeConverter(api, uuid, true)) { return; }
                        awaitConverter();
                    }
                    if (!online()) { return; }
                }
                stage = "转换器开启后刷新代理";
                refreshProxyAfterConverterChange();
                if (!online()) { return; }
                rtspUrl = converterUrl;
                if (!initialized) {
                    String url = converterUrl;
                    stage = "查询代理";
                    synchronized (mutationLock) {
                        if (!online()) { return; }
                        proxy = streamProxyService.getStreamProxyByAppAndStream("drone", sn);
                        if (proxy == null) {
                            proxy = createProxy(url);
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
                StreamProxy selected = mapper.select(proxy.getId());
                if (selected == null || !eligible(selected)) {
                    disableCurrentSession();
                    return;
                }
                delay = (selected.getTimeout() > 0 ? selected.getTimeout() : 15L) + 1;
                if (!online()) { return; }
                if (consumeConverterRestart()) {
                    stage = "连续5次失败后重启转换器";
                    restartConverterAfterFailures();
                    if (!online()) { return; }
                    stage = "转换器重启后刷新代理";
                    refreshProxyAfterConverterChange();
                    if (!online()) { return; }
                    selected = mapper.select(proxy.getId());
                    if (selected == null || !eligible(selected)) { disableCurrentSession(); return; }
                }
                final StreamProxy current = selected;
                final long retryDelay = delay;
                final long attemptGeneration = runningGeneration;
                CompletableFuture<Void> returned = new CompletableFuture<>();
                CompletableFuture<PullResult> completed = new CompletableFuture<>();
                returned.thenCombine(completed, (ignored, result) -> result).thenAccept(result -> {
                    if (result != null) {
                        recordPullResult(attemptGeneration, result, current.getSrcUrl(), retryDelay);
                    }
                    schedule(retryDelay);
                });
                try {
                    stage = "发起拉流";
                    rtspUrl = current.getSrcUrl();
                    synchronized (mutationLock) {
                        if (!online()) { completed.complete(null); return; }
                        playService.startInBackground(current,
                                (code, msg, info) -> completed.complete(new PullResult(code, msg)));
                    }
                } catch (Exception ex) {
                    completed.complete(new PullResult(ErrorCode.ERROR100.getCode(), ex.getMessage()));
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
        convertersPendingEnable.clear();
        convertersPendingRefresh.clear();
        workers.shutdownNow();
    }
}
