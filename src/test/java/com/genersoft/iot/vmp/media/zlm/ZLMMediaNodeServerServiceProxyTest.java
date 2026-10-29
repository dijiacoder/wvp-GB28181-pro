package com.genersoft.iot.vmp.media.zlm;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZLMMediaNodeServerServiceProxyTest {
    private final ZLMRESTfulUtils api = mock(ZLMRESTfulUtils.class);
    private final ZLMMediaNodeServerService service = new ZLMMediaNodeServerService();
    private final MediaServer server = new MediaServer();
    private final StreamProxy proxy = new StreamProxy();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "zlmresTfulUtils", api);
        proxy.setApp("live");
        proxy.setStream("test");
        proxy.setSrcUrl("rtsp://source/live");
        proxy.setTimeout(10);
    }

    @Test
    void existingProxyIsReusedWithoutClosingOrCreatingAnother() {
        JSONObject result = new JSONObject();
        result.put("code", 0);
        JSONArray data = new JSONArray();
        data.add(new JSONObject());
        result.put("data", data);
        when(api.listStreamProxy(server, "__defaultVhost__/live/test")).thenReturn(result);
        service.startProxy(server, proxy);
        verify(api, never()).addStreamProxy(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean());
        verify(api, never()).closeStreams(any(), any(), any());
    }

    @Test
    void failedExistenceQueryDoesNotCreateProxy() {
        assertThrows(ControllerException.class, () -> service.startProxy(server, proxy));
        verify(api, never()).addStreamProxy(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean());
    }

    @Test
    void missingProxyCanBeCreatedAgain() {
        JSONObject result = new JSONObject();
        result.put("code", 0);
        when(api.listStreamProxy(server, "__defaultVhost__/live/test")).thenReturn(result);
        JSONObject added = new JSONObject();
        added.put("code", 0);
        added.put("data", new JSONObject());
        when(api.addStreamProxy(eq(server), eq("live"), eq("test"), anyString(), anyBoolean(), anyBoolean(), any(), eq(10), eq(true)))
                .thenReturn(added);
        service.startProxy(server, proxy);
        verify(api).addStreamProxy(eq(server), eq("live"), eq("test"), anyString(), anyBoolean(), anyBoolean(), any(), eq(10), eq(true));
    }
}
