package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.mqtt.UavOnlineStatusService;
import com.genersoft.iot.vmp.utils.SikongApiUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 独立巡检司空转发器，关闭 Redis 中已离线无人机的转换器。 */
@Slf4j
@Component
public class OfflineConverterCleanupTask {
    private final UavOnlineStatusService onlineStatus;

    @Value("${sikong.base-url:}") private String baseUrl;
    @Value("${sikong.username:}") private String username;
    @Value("${sikong.password:}") private String password;
    @Value("${sikong.organization-key:}") private String organizationKey;

    public OfflineConverterCleanupTask(UavOnlineStatusService onlineStatus) {
        this.onlineStatus = onlineStatus;
    }

    SikongApiUtils client() throws IOException {
        return new SikongApiUtils(baseUrl, username, password, organizationKey);
    }

    JSONObject listConverters(SikongApiUtils api) throws IOException {
        return api.listLiveStreamConverters();
    }

    void closeConverter(SikongApiUtils api, String uuid) throws IOException {
        api.setLiveStreamConverterEnabled(uuid, false);
    }

    @Scheduled(initialDelay = 2 * 60 * 1000L, fixedRate = 5 * 60 * 1000L)
    public void closeOfflineConverters() {
        try {
            SikongApiUtils api = client();
            JSONObject result = listConverters(api);
            JSONObject data = result == null ? null : result.getJSONObject("data");
            JSONArray list = data == null ? null : data.getJSONArray("list");
            if (list == null) {
                throw new IOException("司空接口响应缺少 data.list");
            }
            for (int i = 0; i < list.size(); i++) {
                try {
                    JSONObject converter = list.getJSONObject(i);
                    if (converter == null) { continue; }
                    String sn = converter.getString("sn");
                    String uuid = converter.getString("uuid");
                    if (sn == null || sn.trim().isEmpty() || uuid == null || uuid.trim().isEmpty()) {
                        log.warn("[离线转换器巡检] 跳过缺少 SN 或 uuid 的转换器，序号={}", i);
                        continue;
                    }
                    if (Boolean.FALSE.equals(converter.getBoolean("enable"))) { continue; }
                    // Redis 查询异常时不会执行关闭，避免把状态未知误判为离线。
                    if (!onlineStatus.isOnline(sn)) {
                        closeConverter(api, uuid);
                        log.info("[离线转换器巡检] 已关闭离线无人机转换器，SN={}，uuid={}", sn, uuid);
                    }
                } catch (Exception ex) {
                    log.warn("[离线转换器巡检] 处理转换器失败，序号={}", i, ex);
                }
            }
        } catch (Exception ex) {
            log.warn("[离线转换器巡检] 查询转换器失败，等待下次巡检", ex);
        }
    }
}
