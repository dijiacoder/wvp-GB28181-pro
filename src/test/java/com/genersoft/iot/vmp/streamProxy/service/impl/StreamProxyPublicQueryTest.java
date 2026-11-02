package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxySummary;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamProxyPublicQueryTest {
    @Test
    void queriesByStreamAndBuildsRtmpUrlFromAssignedOrDefaultServer() {
        StreamProxyMapper mapper = mock(StreamProxyMapper.class);
        IMediaServerService media = mock(IMediaServerService.class);
        StreamProxyServiceImpl service = new StreamProxyServiceImpl();
        ReflectionTestUtils.setField(service, "streamProxyMapper", mapper);
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        MediaServer assigned = new MediaServer();
        assigned.setStreamIp("172.16.5.102");
        assigned.setRtmpPort(1935);
        assigned.setFlvPort(80);
        when(media.getOne("assigned")).thenReturn(assigned);
        MediaServer fallback = new MediaServer();
        fallback.setIp("172.16.5.103");
        fallback.setRtmpPort(1936);
        fallback.setHttpPort(8080);
        when(media.getDefaultMediaServer()).thenReturn(fallback);
        StreamProxy active = new StreamProxy();
        active.setApp("drone");
        active.setStream("SN");
        active.setMediaServerId("assigned");
        active.setPulling(true);
        active.setEnable(true);
        StreamProxy disabled = new StreamProxy();
        disabled.setApp("other");
        disabled.setStream("disabled");
        when(mapper.getStreamProxyByStream("SN")).thenReturn(active);
        when(mapper.getStreamProxyByStream("disabled")).thenReturn(disabled);
        StreamProxySummary activeResult = service.getStreamProxy("SN");
        assertEquals("rtmp://172.16.5.102:1935/drone/SN", activeResult.getRtmpUrl());
        assertEquals("http://172.16.5.102:80/drone/SN.live.flv", activeResult.getFlvUrl());
        assertEquals(1, activeResult.getPulling());
        assertEquals(1, activeResult.getEnable());
        StreamProxySummary disabledResult = service.getStreamProxy("disabled");
        assertEquals("rtmp://172.16.5.103:1936/other/disabled", disabledResult.getRtmpUrl());
        assertEquals("http://172.16.5.103:8080/other/disabled.live.flv", disabledResult.getFlvUrl());
        assertEquals(0, disabledResult.getPulling());
        assertEquals(0, disabledResult.getEnable());
        assertNull(service.getStreamProxy("missing"));
        assigned.setRtmpPort(0);
        assertNull(service.getStreamProxy("SN").getRtmpUrl());
        assertEquals("http://172.16.5.102:80/drone/SN.live.flv", service.getStreamProxy("SN").getFlvUrl());
        verify(mapper, never()).selectAll(any(), any(), any());
    }
}
