package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamProxyServiceImplDeletionTest {
    @Test
    void replacingProxyResumesOnlyAfterCommit() {
        StreamProxyMapper mapper = mock(StreamProxyMapper.class);
        IStreamProxyPlayService play = mock(IStreamProxyPlayService.class);
        StreamProxyBackgroundTask background = mock(StreamProxyBackgroundTask.class);
        com.genersoft.iot.vmp.conf.UserSetting settings = mock(com.genersoft.iot.vmp.conf.UserSetting.class);
        when(settings.getServerId()).thenReturn("wvp");
        StreamProxyServiceImpl service = new StreamProxyServiceImpl();
        ReflectionTestUtils.setField(service, "streamProxyMapper", mapper);
        ReflectionTestUtils.setField(service, "playService", play);
        ReflectionTestUtils.setField(service, "backgroundTask", background);
        ReflectionTestUtils.setField(service, "userSetting", settings);
        StreamProxy old = new StreamProxy();
        old.setId(1);
        old.setApp("drone");
        old.setStream("SN");
        when(mapper.selectOneByAppAndStream("drone", "SN")).thenReturn(old, null);
        doAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; })
                .when(play).removeProxy(eq(old), any());
        doAnswer(call -> { call.<StreamProxy>getArgument(0).setId(2); return 1; }).when(mapper).add(any());
        com.genersoft.iot.vmp.streamProxy.bean.StreamProxyParam param =
                new com.genersoft.iot.vmp.streamProxy.bean.StreamProxyParam();
        param.setApp("drone");
        param.setStream("SN");
        param.setMediaServerId("auto");
        param.setEnable(true);
        param.setUrl("rtsp://custom/new");
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.save(param, (code, msg, info) -> {});
            verify(background).cancel(old);
            verify(background, never()).resume(any());
            for (org.springframework.transaction.support.TransactionSynchronization synchronization :
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            org.mockito.ArgumentCaptor<StreamProxy> replacement = org.mockito.ArgumentCaptor.forClass(StreamProxy.class);
            verify(background).resume(replacement.capture());
            org.junit.jupiter.api.Assertions.assertEquals(2, replacement.getValue().getId());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deletingOfflineProxyStillCleansPullAndCancelsTask() {
        StreamProxyMapper mapper = mock(StreamProxyMapper.class);
        IStreamProxyPlayService play = mock(IStreamProxyPlayService.class);
        StreamProxyBackgroundTask background = mock(StreamProxyBackgroundTask.class);
        StreamProxyServiceImpl service = new StreamProxyServiceImpl();
        ReflectionTestUtils.setField(service, "streamProxyMapper", mapper);
        ReflectionTestUtils.setField(service, "playService", play);
        ReflectionTestUtils.setField(service, "backgroundTask", background);
        StreamProxy proxy = new StreamProxy();
        proxy.setId(1);
        proxy.setApp("live");
        proxy.setStream("drone");
        proxy.setPulling(false);
        when(mapper.select(1)).thenReturn(proxy);
        doAnswer(call -> {
            Runnable remove = call.getArgument(1);
            remove.run();
            return null;
        }).when(play).removeProxy(eq(proxy), any());
        service.delete(1);
        org.mockito.InOrder order = inOrder(play, background, mapper);
        order.verify(background).cancel(proxy);
        order.verify(play).removeProxy(eq(proxy), any());
        order.verify(mapper).delete(1);
    }
}
