package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.mqtt.UavHeartbeatEvent;
import com.genersoft.iot.vmp.mqtt.UavOnlineStatusService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService;
import com.genersoft.iot.vmp.utils.SikongApiUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamProxyLifecycleTest {
    private static StreamProxy proxy(int id, String url) {
        StreamProxy proxy = new StreamProxy();
        proxy.setId(id);
        proxy.setApp("drone");
        proxy.setStream("SN");
        proxy.setEnable(true);
        proxy.setSrcUrl(url);
        proxy.setTimeout(15);
        return proxy;
    }

    private static class Fixture implements AutoCloseable {
        final StreamProxyMapper mapper = mock(StreamProxyMapper.class);
        final IStreamProxyService service = mock(IStreamProxyService.class);
        final IStreamProxyPlayService play = mock(IStreamProxyPlayService.class);
        final UavOnlineStatusService online = mock(UavOnlineStatusService.class);
        final ScheduledExecutorService workers = mock(ScheduledExecutorService.class);
        final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        final List<Boolean> changes = new ArrayList<>();
        final AtomicLong now = new AtomicLong();
        final AtomicReference<StreamProxy> saved = new AtomicReference<>();
        final AtomicInteger lookups = new AtomicInteger();
        boolean converterExists = true;
        boolean converterEnabled = true;
        Runnable lookupHook = () -> {};
        Runnable enableHook = () -> {};
        final StreamProxyBackgroundTask task;

        Fixture() {
            when(online.isOnline("SN")).thenReturn(true);
            doAnswer(call -> { pending.add(call.getArgument(0)); return null; })
                    .when(workers).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
            when(service.getStreamProxyByAppAndStream("drone", "SN")).thenAnswer(call -> saved.get());
            when(mapper.select(anyInt())).thenAnswer(call -> {
                StreamProxy proxy = saved.get();
                return proxy != null && proxy.getId() == (int) call.getArgument(0) ? proxy : null;
            });
            doAnswer(call -> {
                StreamProxy proxy = call.getArgument(0);
                proxy.setId(1);
                saved.set(proxy);
                return null;
            }).when(service).add(any());
            doAnswer(call -> {
                call.<com.genersoft.iot.vmp.service.bean.ErrorCallback<com.genersoft.iot.vmp.common.StreamInfo>>getArgument(1)
                        .run(0, "ok", null);
                return null;
            }).when(play).startInBackground(any(), any());
            task = new StreamProxyBackgroundTask(mapper, play, mock(UserSetting.class), online, workers) {
                @Override long nanoTime() { return now.get(); }
                @Override SikongApiUtils client() { return null; }
                @Override void awaitConverter() { }
                @Override JSONObject getConverter(SikongApiUtils api, String sn) {
                    lookups.incrementAndGet();
                    lookupHook.run();
                    return converterExists ? JSONObject.parseObject("{\"uuid\":\"id\",\"enable\":" + converterEnabled + ","
                            + "\"schema_option\":{\"rtsp_url\":\"rtsp://example/live\"}}") : null;
                }
                @Override void setEnabled(SikongApiUtils api, String id, boolean enable) {
                    changes.add(enable);
                    if (enable) { enableHook.run(); }
                }
            };
            ReflectionTestUtils.setField(task, "streamProxyService", service);
        }

        void heartbeat() { task.onHeartbeat(new UavHeartbeatEvent("SN")); }
        void runNext() { Objects.requireNonNull(pending.poll(), "no scheduled task").run(); }
        @Override public void close() { task.shutdown(); }
    }

    @Test
    void converterRequestFailureRetriesPullWithoutRepeatingEnable() {
        try (Fixture f = new Fixture()) {
            f.converterEnabled = false;
            f.enableHook = () -> { throw new ControllerException(500, "converter response failed"); };
            f.heartbeat();
            f.runNext();
            for (int i = 0; i < 3; i++) { f.runNext(); }
            assertEquals(Collections.singletonList(true), f.changes);
            assertEquals(1, f.lookups.get());
            verify(f.play, times(3)).startInBackground(any(), any());
        }
    }

    @Test
    void failedPullsOnlyRetryPulling() {
        try (Fixture f = new Fixture()) {
            doThrow(new ControllerException(500, "pull failed"))
                    .when(f.play).startInBackground(any(), any());
            f.heartbeat();
            for (int i = 0; i < 4; i++) { f.runNext(); }
            assertEquals(Arrays.asList(false, true), f.changes);
            assertEquals(1, f.lookups.get());
            verify(f.play, times(4)).startInBackground(any(), any());
        }
    }

    @Test
    void heartbeatJitterAndTaskRecreationDoNotRepeatInitialRestart() {
        try (Fixture f = new Fixture()) {
            f.converterEnabled = true;
            f.enableHook = () -> f.converterEnabled = true;
            f.heartbeat();
            f.runNext();
            for (int i = 1; i <= 3; i++) {
                f.now.set(TimeUnit.SECONDS.toNanos(i * 6L));
                f.heartbeat();
                f.runNext();
            }
            when(f.online.isOnline("SN")).thenReturn(false);
            f.runNext(); // 在线 key 过期，旧任务被移除。
            when(f.online.isOnline("SN")).thenReturn(true);
            f.heartbeat();
            f.runNext(); // 重建任务也只复用已开启的转换器。
            assertEquals(Arrays.asList(false, true), f.changes);
            verify(f.play, times(5)).startInBackground(any(), any());
        }
    }

    @Test
    void persistenceFailureDoesNotRestartPreparedConverter() {
        for (boolean existing : new boolean[]{false, true}) {
            try (Fixture f = new Fixture()) {
                if (existing) {
                    f.saved.set(proxy(1, "rtsp://old"));
                    when(f.service.update(any())).thenThrow(new ControllerException(400, "temporary"))
                            .thenReturn(true);
                } else {
                    doThrow(new ControllerException(400, "temporary")).doAnswer(call -> {
                        StreamProxy proxy = call.getArgument(0);
                        proxy.setId(1);
                        f.saved.set(proxy);
                        return null;
                    }).when(f.service).add(any());
                }
                f.heartbeat();
                f.runNext();
                f.runNext();
                assertEquals(Arrays.asList(false, true), f.changes);
                assertEquals(1, f.lookups.get());
                verify(f.play).startInBackground(any(), any());
            }
        }
    }

    @Test
    void cancellationDuringNetworkLookupPreventsLaterWritesAndPulls() throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            f.lookupHook = () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            };
            try {
                f.heartbeat();
                Future<?> run = executor.submit(f::runNext);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.task.cancel(proxy(1, "rtsp://old"));
                release.countDown();
                run.get(5, TimeUnit.SECONDS);
                f.heartbeat();
                f.runNext();
                verifyNoInteractions(f.service, f.play);
                assertTrue(f.changes.isEmpty());
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void cancellationWaitsForInFlightWriteAndPreventsRecreation() throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(call -> {
                writing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                StreamProxy proxy = call.getArgument(0);
                proxy.setId(1);
                f.saved.set(proxy);
                return null;
            }).when(f.service).add(any());
            try {
                f.heartbeat();
                Future<?> run = executor.submit(f::runNext);
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                CountDownLatch cancelling = new CountDownLatch(1);
                Future<?> cancel = executor.submit(() -> {
                    cancelling.countDown();
                    f.task.cancel(proxy(1, "rtsp://old"));
                });
                assertTrue(cancelling.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> cancel.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                cancel.get(5, TimeUnit.SECONDS);
                run.get(5, TimeUnit.SECONDS);
                f.saved.set(null); // 删除在 cancel 返回之后执行。
                f.heartbeat();
                f.runNext();
                verify(f.service, times(1)).add(any());
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void heartbeatGapStartsNewSessionEvenWhenRedisIsAlreadyRefreshed() {
        try (Fixture f = new Fixture()) {
            f.heartbeat();
            f.runNext();
            f.now.set(TimeUnit.MILLISECONDS.toNanos(500));
            f.heartbeat();
            f.now.set(TimeUnit.SECONDS.toNanos(6));
            f.heartbeat();
            assertEquals(1, f.pending.size()); // 不并行启动第二个任务。
            f.runNext();
            assertEquals(2, f.lookups.get());
            assertEquals(Arrays.asList(false, true), f.changes);
        }
    }

    @Test
    void missingConverterCanRecoverInNextOnlineSession() {
        try (Fixture f = new Fixture()) {
            f.converterExists = false;
            f.heartbeat();
            f.runNext();
            f.converterExists = true;
            f.now.set(TimeUnit.SECONDS.toNanos(6));
            f.heartbeat();
            f.runNext();
            verify(f.service).add(any());
            assertEquals(2, f.lookups.get());
        }
    }

    @Test
    void newSessionWaitsForPreviousPullCallback() {
        try (Fixture f = new Fixture()) {
            AtomicReference<com.genersoft.iot.vmp.service.bean.ErrorCallback<com.genersoft.iot.vmp.common.StreamInfo>>
                    callback = new AtomicReference<>();
            doAnswer(call -> { callback.set(call.getArgument(1)); return null; })
                    .when(f.play).startInBackground(any(), any());
            f.heartbeat();
            f.runNext();
            f.now.set(TimeUnit.SECONDS.toNanos(6));
            f.heartbeat();
            assertTrue(f.pending.isEmpty());
            assertEquals(1, f.lookups.get());
            callback.get().run(0, "done", null);
            assertEquals(1, f.pending.size());
            f.runNext();
            assertEquals(2, f.lookups.get());
        }
    }

    @Test
    void cancelledTransactionRestoresOriginalProxy() {
        try (Fixture f = new Fixture()) {
            f.heartbeat();
            f.runNext();
            StreamProxy original = f.saved.get();
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                f.task.cancel(original);
                for (org.springframework.transaction.support.TransactionSynchronization synchronization :
                        org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
                }
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
            f.runNext();
            verify(f.play, times(2)).startInBackground(same(original), any());
            assertEquals(1, f.lookups.get());
        }
    }

    @Test
    void replacementResumesWithoutOverwritingUserConfiguration() {
        try (Fixture f = new Fixture()) {
            f.heartbeat();
            f.runNext();
            f.task.cancel(f.saved.get());
            StreamProxy replacement = proxy(2, "rtsp://custom/new");
            f.saved.set(replacement);
            f.task.resume(replacement);
            f.task.cancel(proxy(1, "rtsp://old")); // 迟到的旧配置取消不能影响新配置。
            f.runNext();
            verify(f.play).startInBackground(same(replacement), any());
            assertEquals("rtsp://custom/new", replacement.getSrcUrl());
            assertEquals(1, f.lookups.get());
            verify(f.service, never()).update(any());
        }
    }
}
