package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.Hook;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookType;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.service.redisMsg.IRedisRpcPlayService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ObjectUtils;

import javax.validation.constraints.NotNull;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频代理业务
 */
@Slf4j
@Service
public class StreamProxyPlayServiceImpl implements IStreamProxyPlayService {

    @Autowired
    private StreamProxyMapper streamProxyMapper;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private HookSubscribe subscribe;

    @Autowired
    private DynamicTask dynamicTask;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private IRedisRpcPlayService redisRpcPlayService;

    private final Map<String, PullState> pulls = new ConcurrentHashMap<>();

    private String pullKey(StreamProxy proxy) {
        return proxy.getApp() + "/" + proxy.getStream();
    }

    private static class PullState {
        private final Set<Integer> removedIds = new HashSet<>();
        private PullAttempt attempt;
        private Runnable cleanup = () -> {};
        private MediaServer mediaServer;
    }

    private static class PullAttempt {
        private final ErrorCallback<StreamInfo> callback;

        private PullAttempt(ErrorCallback<StreamInfo> callback) {
            this.callback = callback;
        }
    }

    /** 只有后台调度入口可以创建实际拉流任务。 */
    @Override
    public void startInBackground(StreamProxy proxy, ErrorCallback<StreamInfo> callback) {
        if (proxy.getServerId() == null) {
            proxy.setServerId(userSetting.getServerId());
        }
        startCoordinated(proxy, callback);
    }

    @Override
    public void start(int id, Boolean record, ErrorCallback<StreamInfo> callback) {
        log.info("[拉流代理] 获取后台拉流结果，ID：{}", id);
        StreamProxy streamProxy = streamProxyMapper.select(id);
        if (streamProxy == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "代理信息未找到");
        }
        log.info("[拉流代理] 类型： {}， app：{}, stream: {}, 流地址： {}", streamProxy.getType(), streamProxy.getApp(), streamProxy.getStream(), streamProxy.getSrcUrl());
        if (record != null) {
            streamProxy.setEnableMp4(record);
        }

        startProxy(streamProxy, callback);
    }

    @Override
    public void startProxy(@NotNull StreamProxy streamProxy, ErrorCallback<StreamInfo> callback){
        if (!streamProxy.isEnable()) {
            callback.run(ErrorCode.ERROR100.getCode(), "代理未启用", null);
            return;
        }
        if (streamProxy.getServerId() == null) {
            streamProxy.setServerId(userSetting.getServerId());
        }
        if (!userSetting.getServerId().equals(streamProxy.getServerId())) {
            log.info("[拉流代理] 由其他服务{}管理", streamProxy.getServerId());
            redisRpcPlayService.playProxy(streamProxy.getServerId(), streamProxy.getId(), callback);
            return;
        }

        // 播放入口只查询，不持有后台拉流锁，也不等待收流回调。
        StreamInfo info = streamProxy.getMediaServerId() == null ? null
                : mediaServerService.getStreamInfoByAppAndStreamWithCheck(streamProxy.getApp(),
                streamProxy.getStream(), streamProxy.getMediaServerId(), null, false);
        if (info != null) {
            callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), info);
        } else {
            callback.run(ErrorCode.ERROR100.getCode(), "视频尚未上线，请等待后台拉流", null);
        }
    }

    private void startCoordinated(StreamProxy streamProxy, ErrorCallback<StreamInfo> callback) {
        PullState state = pulls.computeIfAbsent(pullKey(streamProxy), key -> new PullState());
        synchronized (state) {
            // 删除可能已发生在任务读取配置之后；旧配置即使仍可被读到，也不能再次提交。
            if (state.removedIds.contains(streamProxy.getId())) {
                callback.run(ErrorCode.ERROR100.getCode(), "代理已删除", null);
                return;
            }
            StreamProxy current = streamProxyMapper.select(streamProxy.getId());
            if (current == null || !pullKey(current).equals(pullKey(streamProxy))) {
                callback.run(ErrorCode.ERROR100.getCode(), "代理不存在", null);
                return;
            }
            streamProxy = current;
            if (!streamProxy.isEnable()) {
                callback.run(ErrorCode.ERROR100.getCode(), "代理未启用", null);
                return;
            }
            if (state.attempt != null) {
                callback.run(ErrorCode.ERROR100.getCode(), "后台正在拉流", null);
                return;
            }
            PullAttempt result = new PullAttempt(callback);
            state.attempt = result;
            try {
                startLocal(streamProxy, state, result);
            } catch (RuntimeException ex) {
                finish(state, result, ErrorCode.ERROR100.getCode(), ex.getMessage(), null);
                throw ex;
            }
        }
    }

    private void finish(PullState state, PullAttempt result, int code, String msg, StreamInfo info) {
        synchronized (state) {
            if (state.attempt != result) {
                return; // 忽略超时、停止或新一轮拉流后的旧回调。
            }
            state.cleanup.run();
            state.cleanup = () -> {};
            state.attempt = null;
            try {
                result.callback.run(code, msg, info);
            } catch (Exception ex) {
                log.warn("[拉流代理] 回调异常", ex);
            }
        }
    }

    private void startLocal(StreamProxy streamProxy, PullState state, PullAttempt result) {
        if (streamProxy.getMediaServerId() != null) {
            StreamInfo streamInfo = mediaServerService.getStreamInfoByAppAndStreamWithCheck(streamProxy.getApp(), streamProxy.getStream(), streamProxy.getMediaServerId(), null, false);
            if (streamInfo != null) {
                streamProxyMapper.updatePulling(streamProxy.getId(), streamProxy.getMediaServerId(), true);
                finish(state, result, ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), streamInfo);
                return;
            }
        }

        MediaServer mediaServer;
        String mediaServerId = streamProxy.getRelatesMediaServerId();
        if (mediaServerId == null) {
            mediaServer = streamProxy.getMediaServerId() == null ? null
                    : mediaServerService.getOne(streamProxy.getMediaServerId());
            if (mediaServer == null) {
                mediaServer = mediaServerService.getMediaServerForMinimumLoad(null);
            }
        }else {
            mediaServer = mediaServerService.getOne(mediaServerId);
        }
        if (mediaServer == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), mediaServerId == null?"未找到可用的媒体节点":"未找到节点" + mediaServerId);
        }

        final MediaServer selectedServer = mediaServer;
        state.mediaServer = mediaServer;
        streamProxy.setMediaServerId(mediaServer.getId());
        // 只更新本次确认的状态和节点，避免旧快照覆盖 streamKey 等字段。
        streamProxyMapper.updatePulling(streamProxy.getId(), mediaServer.getId(), false);
        String timeOutTaskKey = UUID.randomUUID().toString();
        Hook rtpHook = Hook.getInstance(HookType.on_media_arrival, streamProxy.getApp(), streamProxy.getStream(), mediaServer.getId());
        state.cleanup = () -> {
            dynamicTask.stop(timeOutTaskKey);
            subscribe.removeSubscribe(rtpHook);
        };
        subscribe.addSubscribe(rtpHook, hookData -> {
            synchronized (state) {
                if (state.attempt != result) {
                    return;
                }
                StreamInfo info = mediaServerService.getStreamInfoByAppAndStream(selectedServer,
                        hookData.getApp(), hookData.getStream(), hookData.getMediaInfo(), null);
                streamProxyMapper.updatePulling(streamProxy.getId(), selectedServer.getId(), true);
                finish(state, result, InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), info);
            }
        });
        dynamicTask.startDelay(timeOutTaskKey, () -> finish(state, result,
                InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(),
                InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getMsg(), null), userSetting.getPlayTimeout());

        mediaServerService.startProxy(mediaServer, streamProxy);
        // 兼容代理已存在且收流 hook 早于本次订阅的情况。
        StreamInfo info = mediaServerService.getStreamInfoByAppAndStreamWithCheck(streamProxy.getApp(),
                streamProxy.getStream(), mediaServer.getId(), null, false);
        if (info != null) {
            streamProxyMapper.updatePulling(streamProxy.getId(), mediaServer.getId(), true);
            finish(state, result, ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), info);
        }
    }

    @Override
    public void stop(int id) {
        StreamProxy streamProxy = streamProxyMapper.select(id);
        if (streamProxy == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "代理信息未找到");
        }
        if (streamProxy.getServerId() != null && !userSetting.getServerId().equals(streamProxy.getServerId())) {
            redisRpcPlayService.stopProxy(streamProxy.getServerId(), streamProxy.getId());
            return;
        }
        stopProxy(streamProxy);
    }

    @Override
    public void removeProxy(StreamProxy streamProxy, Runnable removeAction) {
        PullState state = pulls.computeIfAbsent(pullKey(streamProxy), key -> new PullState());
        synchronized (state) {
            int id = streamProxy.getId();
            if (!state.removedIds.add(id)) {
                return; // 旧配置已撤销，重复删除不能关闭同名的新代理。
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            synchronized (state) {
                                state.removedIds.remove(id);
                            }
                        }
                    }
                });
            }
            try {
                // 不依赖 pulling；尚未收流的代理也必须从 ZLM 清理。
                stopProxy(streamProxy);
                removeAction.run();
            } catch (RuntimeException ex) {
                state.removedIds.remove(id);
                throw ex;
            }
        }
    }

    @Override
    public void stopProxy(StreamProxy streamProxy){
        PullState state = pulls.computeIfAbsent(pullKey(streamProxy), key -> new PullState());
        synchronized (state) {
            if (state.attempt != null) {
                finish(state, state.attempt, ErrorCode.ERROR100.getCode(), "拉流已停止", null);
            }
            MediaServer mediaServer = state.mediaServer;
            if (mediaServer == null && streamProxy.getMediaServerId() != null) {
                mediaServer = mediaServerService.getOne(streamProxy.getMediaServerId());
            }
            if (mediaServer != null) {
                if (!"ffmpeg".equalsIgnoreCase(streamProxy.getType())) {
                    String key = ObjectUtils.isEmpty(streamProxy.getStreamKey())
                            ? "__defaultVhost__/" + streamProxy.getApp() + "/" + streamProxy.getStream()
                            : streamProxy.getStreamKey();
                    mediaServerService.stopProxy(mediaServer, key);
                } else if (ObjectUtils.isEmpty(streamProxy.getStreamKey())) {
                    mediaServerService.closeStreams(mediaServer, streamProxy.getApp(), streamProxy.getStream());
                } else {
                    mediaServerService.stopProxy(mediaServer, streamProxy.getStreamKey());
                }
            }
            streamProxyMapper.removeStream(streamProxy.getId());
        }
    }

}
