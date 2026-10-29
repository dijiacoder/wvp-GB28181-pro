package com.genersoft.iot.vmp.mqtt;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UavOnlineStatusServiceTest {
    @SuppressWarnings("unchecked")
    private final RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<Object, Object> values = mock(ValueOperations.class);
    private final UavOnlineStatusService service = new UavOnlineStatusService(redis);
    private final String sn = "1581F5BMD235X0014JPR";
    private final String topic = "thing/product/" + sn + "/state";

    @Test
    void everyQualifyingMessageRefreshesTwoSecondTtlOnFullSerial() {
        when(redis.opsForValue()).thenReturn(values);
        service.handleMessage(topic, "{\"data\":{\"mode_code\":3}}");
        service.handleMessage(topic, "{\"data\":{\"mode_code\":4}}");
        verify(values).set(sn, 3, 2, TimeUnit.SECONDS);
        verify(values).set(sn, 4, 2, TimeUnit.SECONDS);
    }

    @Test
    void ignoresMissingModeLowModeAndUnrelatedTopicsAndSurvivesMalformedJson() {
        service.handleMessage(topic, "{\"data\":{\"mode_code\":2}}");
        service.handleMessage(topic, "{\"data\":{}}");
        service.handleMessage(topic, "{}");
        service.handleMessage("thing/product/other/state", "{\"data\":{\"mode_code\":3}}");
        assertDoesNotThrow(() -> service.handleMessage(topic, "bad json"));
        verifyNoInteractions(redis);
    }

    @Test
    void redisWriteFailureDoesNotEscapeMqttCallback() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new IllegalStateException("redis unavailable")).when(values).set(sn, 3, 2, TimeUnit.SECONDS);
        assertDoesNotThrow(() -> service.handleMessage(topic, "{\"data\":{\"mode_code\":3}}"));
    }

    @Test
    void onlineOnlyWhileRedisKeyExists() {
        when(redis.hasKey(sn)).thenReturn(true, false, null);
        assertTrue(service.isOnline(sn));
        assertFalse(service.isOnline(sn));
        assertFalse(service.isOnline(sn));
    }
}
