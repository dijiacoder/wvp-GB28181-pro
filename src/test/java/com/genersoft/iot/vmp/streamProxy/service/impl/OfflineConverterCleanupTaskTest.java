package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.mqtt.UavOnlineStatusService;
import com.genersoft.iot.vmp.utils.SikongApiUtils;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.mockito.Mockito.*;

class OfflineConverterCleanupTaskTest {
    @Test
    void closesOnlyOfflineConvertersAndContinuesAfterFailures() throws Exception {
        UavOnlineStatusService online = mock(UavOnlineStatusService.class);
        SikongApiUtils api = null;
        OfflineConverterCleanupTask task = spy(new OfflineConverterCleanupTask(online) {
            @Override SikongApiUtils client() { return api; }
        });
        doNothing().when(task).closeConverter(eq(api), anyString());
        doReturn(JSONObject.parseObject(
                "{\'data\':{\'list\':[{\'sn\':'ONLINE',\'uuid\':'a',\'enable\':true},"
                + "{\'sn\':'BROKEN',\'uuid\':'b',\'enable\':true},{\'sn\':'OFFLINE',\'uuid\':'c',\'enable\':true},"
                + "{\'sn\':'UNKNOWN',\'uuid\':'d',\'enable\':true},{\'sn\':'DISABLED',\'uuid\':'e',\'enable\':false},"
                + "{\'uuid\':'invalid'},null]}}")).when(task).listConverters(api);
        when(online.isOnline("ONLINE")).thenReturn(true);
        when(online.isOnline("UNKNOWN")).thenThrow(new IllegalStateException("Redis unavailable"));
        doThrow(new IOException("failed")).when(task).closeConverter(api, "b");
        task.closeOfflineConverters();
        verify(task).closeConverter(api, "b");
        verify(task).closeConverter(api, "c");
        verify(task, never()).closeConverter(api, "a");
        verify(task, never()).closeConverter(api, "d");
        verify(task, never()).closeConverter(api, "e");
        verify(task, never()).closeConverter(api, "invalid");
    }

    @Test
    void listFailureDoesNotPreventNextRun() throws Exception {
        UavOnlineStatusService online = mock(UavOnlineStatusService.class);
        SikongApiUtils api = null;
        OfflineConverterCleanupTask task = spy(new OfflineConverterCleanupTask(online) {
            @Override SikongApiUtils client() { return api; }
        });
        doNothing().when(task).closeConverter(eq(api), anyString());
        doThrow(new IOException("failed"))
                .doReturn(JSONObject.parseObject("{\'data\':{\'list\':[{\'sn\':'OFFLINE',\'uuid\':'a'}]}}"))
                .when(task).listConverters(api);
        task.closeOfflineConverters();
        verifyNoInteractions(online);
        task.closeOfflineConverters();
        verify(task).closeConverter(api, "a");
    }
}
