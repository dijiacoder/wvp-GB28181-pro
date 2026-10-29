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
        order.verify(play).removeProxy(eq(proxy), any());
        order.verify(background).cancel(proxy);
        order.verify(mapper).delete(1);
    }
}
