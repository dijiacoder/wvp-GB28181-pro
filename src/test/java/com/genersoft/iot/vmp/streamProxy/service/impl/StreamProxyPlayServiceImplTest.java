package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamProxyPlayServiceImplTest {
    private final StreamProxyPlayServiceImpl service = new StreamProxyPlayServiceImpl();
    private final StreamProxyMapper mapper = mock(StreamProxyMapper.class);
    private final IMediaServerService media = mock(IMediaServerService.class);
    private final HookSubscribe hooks = mock(HookSubscribe.class);
    private final DynamicTask tasks = mock(DynamicTask.class);
    private final UserSetting settings = mock(UserSetting.class);
    private final MediaServer server = new MediaServer();

    @BeforeEach
    void setup() {
        server.setId("zlm");
        when(settings.getServerId()).thenReturn("wvp");
        when(settings.getPlayTimeout()).thenReturn(10000);
        when(media.getMediaServerForMinimumLoad(null)).thenReturn(server);
        when(media.getOne("zlm")).thenReturn(server);
        when(mapper.select(1)).thenReturn(proxy());
        ReflectionTestUtils.setField(service, "streamProxyMapper", mapper);
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "subscribe", hooks);
        ReflectionTestUtils.setField(service, "dynamicTask", tasks);
        ReflectionTestUtils.setField(service, "userSetting", settings);
    }

    @Test
    void concurrentRequestsNeverMakeFrontendCreateExtraPull() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final boolean background = i % 2 == 0;
                futures.add(callers.submit(() -> {
                    go.await();
                    if (background) {
                        service.startInBackground(proxy(), (code, msg, data) -> results.incrementAndGet());
                    } else {
                        service.startProxy(proxy(), (code, msg, data) -> results.incrementAndGet());
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(3, TimeUnit.SECONDS);
            }
            verify(media, times(1)).startProxy(eq(server), any());
            ArgumentCaptor<HookSubscribe.Event> event = ArgumentCaptor.forClass(HookSubscribe.Event.class);
            verify(hooks).addSubscribe(any(), event.capture());
            StreamInfo info = new StreamInfo();
            when(media.getStreamInfoByAppAndStream(eq(server), eq("live"), eq("test"), any(), isNull())).thenReturn(info);
            HookData data = new HookData();
            data.setApp("live");
            data.setStream("test");
            event.getValue().response(data);
            assertEquals(8, results.get());
            timeout().run();
            assertEquals(8, results.get(), "迟到的超时不能重复完成请求");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void stopCancelsCurrentRequestButNextBackgroundCheckCanRestart() {
        AtomicInteger stoppedResult = new AtomicInteger();
        service.startInBackground(proxy(), (code, msg, data) -> stoppedResult.incrementAndGet());
        Runnable oldTimeout = timeout();
        service.stopProxy(proxy());
        assertEquals(1, stoppedResult.get());
        verify(media).stopProxy(server, "__defaultVhost__/live/test");
        AtomicInteger nextResult = new AtomicInteger();
        service.startInBackground(proxy(), (code, msg, data) -> nextResult.incrementAndGet());
        oldTimeout.run();
        assertEquals(0, nextResult.get(), "旧任务不得取消新一轮拉流");
        verify(media, times(2)).startProxy(eq(server), any());
    }

    @Test
    void failedSubmissionCleansHookAndAllowsRetry() {
        doThrow(new IllegalStateException("offline")).doNothing().when(media).startProxy(eq(server), any());
        assertThrows(IllegalStateException.class, () -> service.startInBackground(proxy(), (code, msg, data) -> {}));
        verify(hooks).removeSubscribe(any());
        verify(tasks).stop(anyString());
        service.startInBackground(proxy(), (code, msg, data) -> {});
        verify(media, times(2)).startProxy(eq(server), any());
    }

    @Test
    void frontendCannotStartPullOrCreateHook() {
        AtomicInteger callbacks = new AtomicInteger();
        service.startProxy(proxy(), (code, msg, data) -> {
            assertNotEquals(0, code);
            callbacks.incrementAndGet();
        });
        assertEquals(1, callbacks.get());
        verify(media, never()).startProxy(any(), any());
        verifyNoInteractions(hooks, tasks);
    }

    @Test
    void frontendImmediatelyReportsOfflineEvenWhenBackgroundIsPulling() {
        AtomicInteger callbacks = new AtomicInteger();
        service.startInBackground(proxy(), (code, msg, data) -> {});
        service.startProxy(proxy(), (code, msg, data) -> callbacks.incrementAndGet());
        assertEquals(1, callbacks.get());
        timeout().run();
        assertEquals(1, callbacks.get());
        verify(media).startProxy(any(), any());
    }

    @Test
    void frontendReturnsExistingOnlineStreamWithoutStartingPull() {
        StreamProxy proxy = proxy();
        proxy.setMediaServerId("zlm");
        StreamInfo info = new StreamInfo();
        when(media.getStreamInfoByAppAndStreamWithCheck("live", "test", "zlm", null, false)).thenReturn(info);
        AtomicInteger callbacks = new AtomicInteger();
        service.startProxy(proxy, (code, msg, data) -> {
            assertEquals(0, code);
            assertSame(info, data);
            callbacks.incrementAndGet();
        });
        assertEquals(1, callbacks.get());
        verify(media, never()).startProxy(any(), any());
        verifyNoInteractions(hooks, tasks);
    }

    @Test
    void frontendDoesNotWaitForBlockedBackgroundSubmission() throws Exception {
        CountDownLatch submitting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            submitting.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(media).startProxy(any(), any());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> background = callers.submit(() -> service.startInBackground(proxy(), (code, msg, data) -> {}));
            assertTrue(submitting.await(2, TimeUnit.SECONDS));
            AtomicInteger callbacks = new AtomicInteger();
            Future<?> frontend = callers.submit(() -> service.startProxy(proxy(), (code, msg, data) -> callbacks.incrementAndGet()));
            frontend.get(1, TimeUnit.SECONDS);
            assertEquals(1, callbacks.get());
            release.countDown();
            background.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void removingPendingProxyClosesZlmAndRejectsStaleTaskEvenBeforeDbCommit() {
        StreamProxy snapshot = proxy();
        snapshot.setPulling(false);
        AtomicInteger callbacks = new AtomicInteger();
        service.startInBackground(snapshot, (code, msg, data) -> callbacks.incrementAndGet());
        Runnable oldTimeout = timeout();
        AtomicInteger deleted = new AtomicInteger();
        service.removeProxy(snapshot, deleted::incrementAndGet);
        assertEquals(1, deleted.get());
        assertEquals(1, callbacks.get());
        verify(media).stopProxy(server, "__defaultVhost__/live/test");
        // 模拟后台已读到的旧快照、以及删除事务提交前仍能查到的数据库记录。
        service.startInBackground(snapshot, (code, msg, data) -> {
            assertNotEquals(0, code);
            callbacks.incrementAndGet();
        });
        oldTimeout.run();
        assertEquals(2, callbacks.get());
        verify(media, times(1)).startProxy(any(), any());
    }

    @Test
    void removalWaitsForSubmissionThenClosesItBeforeDeleting() throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            submitted.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(media).startProxy(any(), any());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Runnable deletion = mock(Runnable.class);
        CountDownLatch removing = new CountDownLatch(1);
        try {
            Future<?> background = callers.submit(() -> service.startInBackground(proxy(), (code, msg, data) -> {}));
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            Future<?> removal = callers.submit(() -> {
                removing.countDown();
                service.removeProxy(proxy(), deletion);
            });
            assertTrue(removing.await(2, TimeUnit.SECONDS));
            assertFalse(removal.isDone());
            release.countDown();
            background.get(2, TimeUnit.SECONDS);
            removal.get(2, TimeUnit.SECONDS);
            org.mockito.InOrder order = inOrder(media, deletion);
            order.verify(media).startProxy(any(), any());
            order.verify(media).stopProxy(server, "__defaultVhost__/live/test");
            order.verify(deletion).run();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void recreatedProxyWithNewIdCanPullButOldGenerationCannot() {
        service.removeProxy(proxy(), () -> {});
        StreamProxy replacement = proxy();
        replacement.setId(2);
        when(mapper.select(2)).thenReturn(replacement);
        service.startInBackground(replacement, (code, msg, data) -> {});
        service.startInBackground(proxy(), (code, msg, data) -> assertNotEquals(0, code));
        service.removeProxy(proxy(), () -> fail("旧配置重复删除不得再次执行清理"));
        verify(media, never()).stopProxy(any(), anyString());
        verify(media, times(1)).startProxy(any(), eq(replacement));
    }

    @Test
    void failedRemovalDoesNotPermanentlyBlockExistingConfiguration() {
        assertThrows(IllegalStateException.class, () -> service.removeProxy(proxy(), () -> {
            throw new IllegalStateException("delete failed");
        }));
        service.startInBackground(proxy(), (code, msg, data) -> {});
        verify(media).startProxy(any(), any());
    }

    @Test
    void rolledBackDeletionRestoresBackgroundEligibility() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.removeProxy(proxy(), () -> {});
            for (org.springframework.transaction.support.TransactionSynchronization synchronization
                    : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            service.startInBackground(proxy(), (code, msg, data) -> {});
            verify(media).startProxy(any(), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Runnable timeout() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(tasks).startDelay(anyString(), task.capture(), eq(10000));
        return task.getValue();
    }

    private StreamProxy proxy() {
        StreamProxy proxy = new StreamProxy();
        proxy.setId(1);
        proxy.setApp("live");
        proxy.setStream("test");
        proxy.setEnable(true);
        return proxy;
    }
}
