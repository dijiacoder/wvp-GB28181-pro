package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamProxyStartupTest {
    private final StreamProxyMapper mapper = mock(StreamProxyMapper.class);
    private final UserSetting settings = mock(UserSetting.class);
    private final IGbChannelService channels = mock(IGbChannelService.class);
    private final DynamicTask tasks = mock(DynamicTask.class);
    private final StreamProxyStartup startup = new StreamProxyStartup(mapper, settings, channels, tasks);

    @BeforeEach
    void setup() {
        when(settings.getServerId()).thenReturn("wvp");
    }

    @Test
    void delaysReportingThirtySecondsAndSchedulesOnlyOnce() {
        StreamProxy enabled = proxy(1, true, null);
        StreamProxy disabled = proxy(2, false, null);
        StreamProxy remote = proxy(3, true, "remote");
        when(mapper.selectAll(null, null, null)).thenReturn(Arrays.asList(enabled, disabled, remote));
        startup.startOnApplicationReady();
        startup.startOnApplicationReady();
        verifyNoInteractions(mapper, channels);
        delayed().run();
        verify(channels).online(nullable(CommonGBChannel.class));
    }

    @Test
    void shutdownPreventsDelayedWork() {
        startup.startOnApplicationReady();
        Runnable work = delayed();
        startup.stop();
        work.run();
        verify(tasks).stop("stream-proxy-startup-online");
        verifyNoInteractions(mapper, channels);
    }

    @Test
    void rechecksConfigurationBeforeReportingOnline() {
        StreamProxy enabled = proxy(1, true, null);
        when(mapper.selectAll(null, null, null)).thenReturn(Arrays.asList(enabled));
        startup.startOnApplicationReady();
        when(mapper.select(1)).thenReturn(null);
        delayed().run();
        verifyNoInteractions(channels);
    }

    private Runnable delayed() {
        ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        verify(tasks).startDelay(eq("stream-proxy-startup-online"), work.capture(), eq(30000));
        return work.getValue();
    }

    private StreamProxy proxy(int id, boolean enabled, String owner) {
        StreamProxy proxy = new StreamProxy();
        proxy.setId(id);
        proxy.setEnable(enabled);
        proxy.setServerId(owner);
        proxy.setGbId(id);
        when(mapper.select(id)).thenReturn(proxy);
        return proxy;
    }
}
