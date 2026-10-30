package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.mqtt.UavHeartbeatEvent;
import com.genersoft.iot.vmp.mqtt.UavOnlineStatusService;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.IOException;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StreamProxyBackgroundTaskTest {
    @Test
    void concurrentHeartbeatsRegisterOneTaskPerSerial() throws Exception {
        ScheduledExecutorService workers = mock(ScheduledExecutorService.class);
        StreamProxyBackgroundTask task = new StreamProxyBackgroundTask(mock(StreamProxyMapper.class),
                mock(IStreamProxyPlayService.class), mock(UserSetting.class),
                mock(UavOnlineStatusService.class), workers);
        ExecutorService senders = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<?>> pending = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 1000; i++) {
                final String sn = "SN-" + (i % 10);
                pending.add(senders.submit(() -> {
                    start.await();
                    task.onHeartbeat(new UavHeartbeatEvent(sn));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : pending) { result.get(5, TimeUnit.SECONDS); }
            verify(workers, times(10)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.SECONDS));
        } finally {
            senders.shutdownNow();
            task.shutdown();
        }
    }

    @Test
    void anotherSerialCanRegisterWhileOneSubmissionIsBlocked() throws Exception {
        ScheduledExecutorService workers = mock(ScheduledExecutorService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (submissions.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("submission blocked"); }
            }
            return null;
        }).when(workers).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.SECONDS));
        StreamProxyBackgroundTask task = new StreamProxyBackgroundTask(mock(StreamProxyMapper.class),
                mock(IStreamProxyPlayService.class), mock(UserSetting.class),
                mock(UavOnlineStatusService.class), workers);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = senders.submit(() -> task.onHeartbeat(new UavHeartbeatEvent("A")));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            senders.submit(() -> task.onHeartbeat(new UavHeartbeatEvent("B"))).get(2, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            senders.shutdownNow();
            task.shutdown();
        }
    }

    @Test
    void enabledConverterRestartsOnceAndUsesNestedRtspUrl() throws Exception {
        for (boolean existing : new boolean[]{false, true}) {
            for (boolean enabled : new boolean[]{false, true}) {
                StreamProxyMapper mapper = mock(StreamProxyMapper.class);
                IStreamProxyPlayService play = mock(IStreamProxyPlayService.class);
                UavOnlineStatusService online = mock(UavOnlineStatusService.class);
                ScheduledExecutorService workers = mock(ScheduledExecutorService.class);
                java.util.List<Boolean> changes = new java.util.ArrayList<>();
                StreamProxyBackgroundTask task = new StreamProxyBackgroundTask(mapper, play,
                        mock(UserSetting.class), online, workers) {
                    @Override
                    com.genersoft.iot.vmp.utils.SikongApiUtils client() { return null; }
                    @Override
                    com.alibaba.fastjson2.JSONObject getConverter(
                            com.genersoft.iot.vmp.utils.SikongApiUtils api, String sn) {
                        return com.alibaba.fastjson2.JSONObject.parseObject("{\"uuid\":\"id\",\"enable\":"
                                + enabled + ",\"schema_option\":{\"rtsp_url\":\"rtsp://example/live\"}}");
                    }
                    @Override
                    void setEnabled(com.genersoft.iot.vmp.utils.SikongApiUtils api, String id, boolean value) {
                        changes.add(value);
                    }
                };
                com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService service =
                        mock(com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService.class);
                org.springframework.test.util.ReflectionTestUtils.setField(task, "streamProxyService", service);
                if (existing) {
                    com.genersoft.iot.vmp.streamProxy.bean.StreamProxy saved =
                            new com.genersoft.iot.vmp.streamProxy.bean.StreamProxy();
                    saved.setId(42);
                    saved.setApp("drone");
                    saved.setStream("SN");
                    saved.setEnable(true);
                    saved.setRelatesMediaServerId("");
                    when(service.getStreamProxyByAppAndStream("drone", "SN")).thenReturn(saved);
                }
                when(online.isOnline("SN")).thenReturn(true);
                task.onHeartbeat(new UavHeartbeatEvent("SN"));
                ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
                verify(workers).schedule(run.capture(), eq(0L), eq(TimeUnit.SECONDS));
                run.getValue().run();
                org.junit.jupiter.api.Assertions.assertEquals(enabled ? java.util.Arrays.asList(false, true)
                        : java.util.Collections.singletonList(true), changes);
                ArgumentCaptor<com.genersoft.iot.vmp.streamProxy.bean.StreamProxy> proxy =
                        ArgumentCaptor.forClass(com.genersoft.iot.vmp.streamProxy.bean.StreamProxy.class);
                verify(service).getStreamProxyByAppAndStream("drone", "SN");
                verify(mapper, never()).selectOneByAppAndStream(anyString(), anyString());
                if (existing) {
                    verify(service).update(proxy.capture());
                    verify(mapper, never()).update(any());
                    verify(mapper, never()).add(any());
                    org.junit.jupiter.api.Assertions.assertNull(proxy.getValue().getRelatesMediaServerId());
                } else {
                    verify(service).add(proxy.capture());
                    verify(mapper, never()).add(any());
                    verify(service, never()).update(any());
                }
                org.junit.jupiter.api.Assertions.assertEquals("rtsp://example/live", proxy.getValue().getSrcUrl());
                task.shutdown();
            }
        }
    }

    @Test
    void deduplicatesHeartbeatsAndStopsRetryingWhenOffline() {
        StreamProxyMapper mapper = mock(StreamProxyMapper.class);
        IStreamProxyPlayService play = mock(IStreamProxyPlayService.class);
        UavOnlineStatusService online = mock(UavOnlineStatusService.class);
        ScheduledExecutorService workers = mock(ScheduledExecutorService.class);
        StreamProxyBackgroundTask task = new StreamProxyBackgroundTask(mapper, play,
                mock(UserSetting.class), online, workers) {
            @Override
            com.genersoft.iot.vmp.utils.SikongApiUtils client() throws IOException {
                throw new IOException("temporary failure");
            }
        };
        when(online.isOnline("SN")).thenReturn(true);
        task.onHeartbeat(new UavHeartbeatEvent("SN"));
        task.onHeartbeat(new UavHeartbeatEvent("SN"));
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(workers).schedule(run.capture(), eq(0L), eq(TimeUnit.SECONDS));
        run.getValue().run();
        verify(workers).schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS));
        when(online.isOnline("SN")).thenReturn(false);
        run.getValue().run();
        verify(workers, times(2)).schedule(any(Runnable.class), anyLong(), any());
        task.onHeartbeat(new UavHeartbeatEvent("SN"));
        verify(workers, times(2)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.SECONDS));
        verifyNoInteractions(mapper, play);
        task.shutdown();
    }
}
