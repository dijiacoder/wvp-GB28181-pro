package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamProxyBackgroundTaskTest {
    private final StreamProxyMapper mapper = mock(StreamProxyMapper.class);
    private final IStreamProxyPlayService play = mock(IStreamProxyPlayService.class);
    private final UserSetting settings = mock(UserSetting.class);
    private final Clock clock = mock(Clock.class);
    private final ScheduledExecutorService workers = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final StreamProxyBackgroundTask task = new StreamProxyBackgroundTask(mapper, play, settings, clock, workers);
    private StreamProxy proxy;

    @BeforeEach
    void setup() {
        when(settings.getServerId()).thenReturn("wvp");
        doReturn(future).when(workers).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
        proxy = proxy(1);
        when(mapper.selectAll(null, null, null)).thenReturn(Collections.singletonList(proxy));
    }

    @Test
    void waitsTenSecondsAndReusesTaskWhilePendingAndWaiting() {
        task.check();
        task.ready();
        when(clock.millis()).thenReturn(9999L);
        task.check();
        verifyNoInteractions(mapper, workers, play);
        when(clock.millis()).thenReturn(10000L);
        task.check();
        Runnable pull = scheduled(0, 1).get(0);
        task.check();
        verify(workers, times(1)).schedule(any(Runnable.class), anyLong(), any());
        pull.run();
        task.check();
        verify(workers, times(1)).schedule(any(Runnable.class), anyLong(), any());
        callback().run(0, "ok", null);
        assertSame(pull, scheduled(11, 1).get(0));
        task.check();
        verify(workers, times(2)).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void allHundredSourcesGetIndependentTasksEvenWithoutResults() {
        List<StreamProxy> proxies = new ArrayList<>();
        for (int id = 1; id <= 100; id++) {
            proxies.add(proxy(id));
        }
        when(mapper.selectAll(null, null, null)).thenReturn(proxies);
        activate();
        List<Runnable> pulls = scheduled(0, 100);
        pulls.forEach(Runnable::run);
        verify(play, times(100)).startInBackground(any(), any());
        task.check();
        verify(workers, times(100)).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void disablingCancelsTaskAndLateCallbackCannotRestartIt() {
        activate();
        Runnable pull = scheduled(0, 1).get(0);
        pull.run();
        proxy.setEnable(false);
        task.check();
        verify(future).cancel(false);
        callback().run(0, "late", null);
        verify(workers, times(1)).schedule(any(Runnable.class), anyLong(), any());
        proxy.setEnable(true);
        task.check();
        verify(workers, times(2)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.SECONDS));
        pull.run();
        verify(play, times(1)).startInBackground(any(), any());
    }

    @Test
    void deletedSourceCancelsPendingTask() {
        activate();
        Runnable pull = scheduled(0, 1).get(0);
        when(mapper.selectAll(null, null, null)).thenReturn(Collections.emptyList());
        task.check();
        pull.run();
        verify(future).cancel(false);
        verifyNoInteractions(play);
    }

    @Test
    void queryFailureKeepsTaskForRetry() {
        activate();
        Runnable pull = scheduled(0, 1).get(0);
        when(mapper.select(1)).thenThrow(new IllegalStateException("db unavailable"));
        pull.run();
        assertSame(pull, scheduled(16, 1).get(0));
    }

    @Test
    void synchronousCallbackFollowedByExceptionSchedulesOnlyOnce() {
        activate();
        doAnswer(call -> {
            ErrorCallback<StreamInfo> callback = call.getArgument(1);
            callback.run(1, "failed", null);
            // 任务尚未返回时不能提前安排下一轮。
            verify(workers, times(1)).schedule(any(Runnable.class), anyLong(), any());
            throw new IllegalStateException("failed");
        }).when(play).startInBackground(any(), any());
        scheduled(0, 1).get(0).run();
        scheduled(11, 1);
        verify(workers, times(2)).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void shutdownCancelsTasksAndRejectsLateWork() {
        activate();
        Runnable pull = scheduled(0, 1).get(0);
        task.shutdown();
        pull.run();
        task.check();
        verify(future).cancel(false);
        verify(workers).shutdownNow();
        verifyNoInteractions(play);
    }

    @Test
    void explicitRemovalCancelsTaskImmediatelyAndLateCallbackCannotSchedule() {
        activate();
        Runnable pull = scheduled(0, 1).get(0);
        pull.run();
        ErrorCallback<StreamInfo> late = callback();
        task.cancel(proxy);
        verify(future).cancel(false);
        late.run(0, "late", null);
        verify(workers, times(1)).schedule(any(Runnable.class), anyLong(), any());
    }

    private void activate() {
        task.ready();
        when(clock.millis()).thenReturn(10000L);
        task.check();
    }

    private List<Runnable> scheduled(long delay, int count) {
        ArgumentCaptor<Runnable> capture = ArgumentCaptor.forClass(Runnable.class);
        verify(workers, times(count)).schedule(capture.capture(), eq(delay), eq(TimeUnit.SECONDS));
        return capture.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private ErrorCallback<StreamInfo> callback() {
        ArgumentCaptor<ErrorCallback<StreamInfo>> capture = ArgumentCaptor.forClass(ErrorCallback.class);
        verify(play).startInBackground(eq(proxy), capture.capture());
        return capture.getValue();
    }

    private StreamProxy proxy(int id) {
        StreamProxy proxy = new StreamProxy();
        proxy.setId(id);
        proxy.setApp("live");
        proxy.setStream("drone-" + id);
        proxy.setTimeout(10);
        proxy.setEnable(true);
        when(mapper.select(id)).thenReturn(proxy);
        return proxy;
    }
}
