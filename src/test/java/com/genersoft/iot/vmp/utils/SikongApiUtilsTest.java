package com.genersoft.iot.vmp.utils;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实接口功能测试：填写下面的配置后，在 IDE 中单独运行需要测试的方法。
 * 建议顺序：创建 -> 查询（获取 converter_id）-> 开启 -> 关闭 -> 更新。
 * 各测试独立运行，不依赖执行顺序；创建后需手动填写 CONVERTER_ID。
 */
public class SikongApiUtilsTest {

    private static final String BASE_URL = "http://172.16.5.56:30812";
    private static final String USERNAME = "admin";
    // 填写登录明文密码，工具类自动按时间戳计算 SHA-256 摘要。
    private static final String PASSWORD = "Cykj@2026+";
    private static final String ORGANIZATION_KEY = "73a69adc-8d83-439a-a6b5-b3ad2b0e8a0a";

    private SikongApiUtils client() throws IOException {
        return new SikongApiUtils(BASE_URL, USERNAME, PASSWORD, ORGANIZATION_KEY);
    }

    @Test
    void listLiveStreamConverters() throws IOException {
        System.out.println(client().listLiveStreamConverters());
    }

    @Test
    void getLiveStreamConverter() throws IOException {
        System.out.println(client().getLiveStreamConverter("1581F5BMD235V001HJWB"));
    }
    @Test
    void startLiveStreamConverter() throws IOException {
        JSONObject result = client().setLiveStreamConverterEnabled("8c8db917-3299-4e09-8ea2-40176e8f6e4f", true);
        System.out.println(result);
    }

    @Test
    void stopLiveStreamConverter() throws IOException {
        JSONObject result = client().setLiveStreamConverterEnabled("8c8db917-3299-4e09-8ea2-40176e8f6e4f", false);
        System.out.println(result);
    }
}
