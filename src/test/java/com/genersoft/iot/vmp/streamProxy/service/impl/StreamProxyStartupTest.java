package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StreamProxyStartupTest {
    private StreamProxyMapper mapper;
    private IStreamProxyPlayService playService;
    private StreamProxyStartup startup;

    @BeforeEach
    void setup() {
        mapper = mock(StreamProxyMapper.class);
        playService = mock(IStreamProxyPlayService.class);
        UserSetting settings = mock(UserSetting.class);
        when(settings.getServerId()).thenReturn("wvp");
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
