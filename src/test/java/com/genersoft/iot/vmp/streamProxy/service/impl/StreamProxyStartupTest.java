package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StreamProxyStartupTest {
    private StreamProxyMapper mapper;
    private IStreamProxyPlayService playService;
    private StreamProxyStartup startup;
    private UserSetting settings;

    @BeforeEach
    void setup() {
        mapper = mock(StreamProxyMapper.class);
        playService = mock(IStreamProxyPlayService.class);
        settings = mock(UserSetting.class);
        when(settings.getServerId()).thenReturn("wvp");
        when(settings.getPlayTimeout()).thenReturn(1000);
        doAnswer(invocation -> {
            ErrorCallback<StreamInfo> callback = invocation.getArgument(1);
            callback.run(0, "ok", new StreamInfo());
            return null;
        }).when(playService).startProxy(any(), any());
        startup = new StreamProxyStartup(mapper, playService, settings);
    }

    @Test
    void startsEachEligibleProxyOnlyOnce() {
        StreamProxy automatic = proxy(1, null);
        StreamProxy fixed = proxy(2, "node2");
        StreamProxy disabled = proxy(3, null);
        disabled.setEnable(false);
        StreamProxy remote = proxy(4, null);
        remote.setServerId("other");
        when(mapper.selectAll(null, null, null)).thenReturn(Arrays.asList(automatic, fixed, disabled, remote));
        startup.startOnApplicationReady();
        startup.startOnApplicationReady();
        verify(playService).startProxy(eq(automatic), any());
        verify(playService).startProxy(eq(fixed), any());
        verify(mapper).selectAll(null, null, null);
        verifyNoMoreInteractions(playService);
    }

    @Test
    void failureDoesNotBlockOtherProxiesOrRetry() {
        StreamProxy first = proxy(1, null);
        StreamProxy second = proxy(2, null);
        when(mapper.selectAll(null, null, null)).thenReturn(Arrays.asList(first, second));
        doThrow(new IllegalStateException("unavailable")).when(playService).startProxy(eq(first), any());
        startup.startOnApplicationReady();
        startup.startOnApplicationReady();
        verify(playService).startProxy(eq(first), any());
        verify(playService).startProxy(eq(second), any());
        verifyNoMoreInteractions(playService);
    }

    @Test
    void skipsProxiesDeletedOrDisabledBeforeTheirTurn() {
        StreamProxy deleted = proxy(1, null);
        StreamProxy disabled = proxy(2, null);
        when(mapper.selectAll(null, null, null)).thenReturn(Arrays.asList(deleted, disabled));
        when(mapper.select(1)).thenReturn(null);
        StreamProxy latest = proxy(2, null);
        latest.setEnable(false);
        startup.startOnApplicationReady();
        verifyNoInteractions(playService);
    }

    @Test
    void limitsInFlightPullsUntilCallbacksComplete() throws Exception {
        java.util.List<StreamProxy> proxies = Arrays.asList(
                proxy(1, null), proxy(2, null), proxy(3, null), proxy(4, null), proxy(5, null),
                proxy(6, null), proxy(7, null));
        when(mapper.selectAll(null, null, null)).thenReturn(proxies);
        when(settings.getPlayTimeout()).thenReturn(10000);
        BlockingQueue<ErrorCallback<StreamInfo>> callbacks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return null;
        }).when(playService).startProxy(any(), any());
        CompletableFuture<Void> batch = CompletableFuture.runAsync(startup::startOnApplicationReady);
        try {
            ErrorCallback<StreamInfo> first = callbacks.poll(2, TimeUnit.SECONDS);
            ErrorCallback<StreamInfo> second = callbacks.poll(2, TimeUnit.SECONDS);
            ErrorCallback<StreamInfo> third = callbacks.poll(2, TimeUnit.SECONDS);
            ErrorCallback<StreamInfo> fourth = callbacks.poll(2, TimeUnit.SECONDS);
            ErrorCallback<StreamInfo> fifth = callbacks.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            assertNotNull(third);
            assertNotNull(fourth);
            assertNotNull(fifth);
            assertNull(callbacks.poll(100, TimeUnit.MILLISECONDS), "并发上限为5，回调前不应启动第6路");
            first.run(0, "ok", null);
            ErrorCallback<StreamInfo> sixth = callbacks.poll(2, TimeUnit.SECONDS);
            assertNotNull(sixth);
            first.run(0, "duplicate", null);
            assertNull(callbacks.poll(100, TimeUnit.MILLISECONDS), "重复回调不应释放额外名额");
            second.run(-1, "failed", null);
            ErrorCallback<StreamInfo> seventh = callbacks.poll(2, TimeUnit.SECONDS);
            assertNotNull(seventh);
            third.run(0, "ok", null);
            fourth.run(0, "ok", null);
            fifth.run(0, "ok", null);
            sixth.run(0, "ok", null);
            seventh.run(0, "ok", null);
            batch.get(2, TimeUnit.SECONDS);
            verify(playService, times(7)).startProxy(any(), any());
        } finally {
            startup.stop();
            batch.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingCallbackTimesOutAndAllowsNextProxy() {
        when(settings.getPlayTimeout()).thenReturn(20);
        // 超过固定并发数5，确认等待超时后排队的第6路仍能启动。
        java.util.List<StreamProxy> proxies = Arrays.asList(
                proxy(1, null), proxy(2, null), proxy(3, null), proxy(4, null), proxy(5, null), proxy(6, null));
        when(mapper.selectAll(null, null, null)).thenReturn(proxies);
        doNothing().when(playService).startProxy(any(), any());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), startup::startOnApplicationReady);
        verify(playService, times(6)).startProxy(any(), any());
    }

    private StreamProxy proxy(int id, String node) {
        StreamProxy proxy = new StreamProxy();
        proxy.setId(id);
        proxy.setEnable(true);
        proxy.setServerId("wvp");
        proxy.setRelatesMediaServerId(node);
        when(mapper.select(id)).thenReturn(proxy);
        return proxy;
    }

}
