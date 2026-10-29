package com.genersoft.iot.vmp.mqtt;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** MQTT 状态心跳；完整无人机序列号作为 Redis key，mode_code 作为 value。 */
@Slf4j
@Service
public class UavOnlineStatusService {
    private final RedisTemplate<Object, Object> redisTemplate;

    public UavOnlineStatusService(RedisTemplate<Object, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void handleMessage(String topic, String payload) {
        String[] parts = topic.split("/", -1);
        if (parts.length != 4 || !"thing".equals(parts[0]) || !"product".equals(parts[1])
                || !"osd".equals(parts[3]) || !parts[2].contains("1581")) {
            return;
        }
        String uavSN = parts[2];
        try {
            JSONObject json = JSONObject.parseObject(payload);
            JSONObject data = json == null ? null : json.getJSONObject("data");
            Integer modeCode = data == null ? null : data.getInteger("mode_code");
            if (modeCode != null && modeCode > 2) {
                redisTemplate.opsForValue().set(uavSN, modeCode, 2, TimeUnit.SECONDS);
//                log.info("{},{}", uavSN, modeCode);
            }
        } catch (Exception e) {
            // 单条坏消息或 Redis 故障不能使 MQTT 回调抛出异常而断开连接。
            log.warn("[MQTT] 处理无人机状态失败，uavSN={}", uavSN, e);
        }
    }

    public boolean isOnline(String uavSN) {
        return redisTemplate.hasKey(uavSN);
    }
}
