package com.genersoft.iot.vmp.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
public final class SikongApiUtils {
    private final String baseUrl;
    private final String organizationKey;
    private final String xAuthToken;

    public SikongApiUtils(String baseUrl, String username, String password, String organizationKey) throws IOException {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl 必须为有效的 HTTP(S) 地址");
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawQuery() != null || base.getRawFragment() != null
                || base.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl 必须为不含认证信息、查询参数和片段的 HTTP(S) 地址");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        if (organizationKey == null || organizationKey.trim().isEmpty()) {
            throw new IllegalArgumentException("organizationKey 不能为空");
        }
        this.organizationKey = organizationKey;
        this.xAuthToken = getXAuthToken(username, password);
        log.info(this.xAuthToken);
    }

    /**
     * 账号认证并提取 redirect_url 中的 token。
     * password 传登录明文密码；按前端规则发送 SHA256(password.trim() + timespan) 的小写十六进制摘要。
     * 返回 authToken，用于后续请求的 x-auth-token；与配置文件中的 x-user-token 分开传递。
     */
    public String getXAuthToken(String account, String password) throws IOException {
        if (account == null || account.trim().isEmpty()) {
            throw new IllegalArgumentException("account 不能为空");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("password 不能为空");
        }
        // 与部署前端 PrivateHome.handleLogin 保持一致，摘要和请求复用同一个时间戳。
        long timespan = System.currentTimeMillis();
        StringBuilder encryptedPassword = new StringBuilder(64);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((password.trim() + timespan).getBytes(StandardCharsets.UTF_8));
            for (byte value : digest) {
                encryptedPassword.append(Character.forDigit((value & 0xff) >>> 4, 16));
                encryptedPassword.append(Character.forDigit(value & 0x0f, 16));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 Java 环境不支持 SHA-256", e);
        }
        String requestUrl = baseUrl + "manage/api/v1/account-auth"
                + "?account=" + URLEncoder.encode(account.trim(), "UTF-8")
                + "&password=" + encryptedPassword
                + "&timespan=" + timespan;
        HttpRequest request = HttpRequest.get(requestUrl)
                .header("Accept", "application/json, text/plain, */*")
                .contentType("application/json; charset=utf-8")
                .setConnectionTimeout(10000).setReadTimeout(30000)
                .setFollowRedirects(false);
        try (HttpResponse response = request.execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IOException("账号认证 HTTP " + response.getStatus());
            }
            JSONObject result;
            try {
                result = JSON.parseObject(response.body());
            } catch (RuntimeException e) {
                throw new IOException("账号认证响应不是有效 JSON 对象");
            }
            if (result == null || !(result.get("code") instanceof Number)) {
                throw new IOException("账号认证响应缺少有效 code");
            }
            if (!"0".equals(result.get("code").toString())) {
                throw new IOException("账号认证失败, code=" + result.get("code")
                        + ", message=" + result.getString("message"));
            }
            JSONObject data = result.getJSONObject("data");
            String redirectUrl = data == null ? null : data.getString("redirect_url");
            if (redirectUrl == null || redirectUrl.trim().isEmpty()) {
                throw new IOException("账号认证响应缺少 redirect_url");
            }
            String query;
            try {
                query = URI.create(redirectUrl).getRawQuery();
            } catch (IllegalArgumentException e) {
                throw new IOException("账号认证返回的 redirect_url 无效");
            }
            if (query != null) {
                for (String parameter : query.split("&")) {
                    String[] pair = parameter.split("=", 2);
                    if (pair.length == 2 && "token".equals(pair[0]) && !pair[1].isEmpty()) {
                        try {
                            // 保留 Base64 的 + 和 =，只解码百分号转义；不包含后续 endpoint 参数。
                            return URLDecoder.decode(pair[1].replace("+", "%2B"), "UTF-8");
                        } catch (IllegalArgumentException e) {
                            throw new IOException("账号认证返回的 token 编码无效");
                        }
                    }
                }
            }
            throw new IOException("账号认证 redirect_url 中缺少 token");
        } catch (HttpException e) {
            throw new IOException("账号认证网络请求失败");
        }
    }

    /**
     * 查询组织下的直播同步列表，默认第 1 页，每页 10 条。
     */
    public JSONObject listLiveStreamConverters() throws IOException {
        String queryUrl = baseUrl + "manage/api/v1/organizations/"
                + URLEncoder.encode(organizationKey, "UTF-8") + "/c2c/live-sync/list?page=1&size=1000";
        String requestId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.get(queryUrl)
                .header("x-auth-token", xAuthToken)
                .header("x-request-id", requestId)
                .contentType("application/json; charset=utf-8")
                .setConnectionTimeout(10000).setReadTimeout(30000).setFollowRedirects(false);
        System.out.println(request.toString());
        try (HttpResponse response = request.execute()) {
            System.out.println(response.toString());
            // 不把响应正文写入异常，避免暴露 RTSP 密码等敏感信息。
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IOException("司空接口 HTTP " + response.getStatus() + ", requestId=" + requestId);
            }
            String responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("司空接口响应为空, requestId=" + requestId);
            }
            JSONObject result;
            try {
                result = JSON.parseObject(responseBody);
            } catch (RuntimeException e) {
                throw new IOException("司空接口响应不是有效 JSON 对象, requestId=" + requestId);
            }
            if (result == null || !(result.get("code") instanceof Number)) {
                throw new IOException("司空接口响应缺少有效 code, requestId=" + requestId);
            }
            if (!"0".equals(result.get("code").toString())) {
                throw new IOException("司空接口业务失败, code=" + result.get("code")
                        + ", requestId=" + requestId);
            }
            return result;
        } catch (HttpException e) {
            throw new IOException("司空接口网络请求失败, requestId=" + requestId, e);
        }
    }

    /** 按转发器 uuid 开启或关闭直播同步，返回完整接口响应。 */
    public JSONObject setLiveStreamConverterEnabled(String uuid, boolean enable) throws IOException {
        if (uuid == null || uuid.trim().isEmpty()) {
            throw new IllegalArgumentException("uuid 不能为空");
        }
        String requestUrl = baseUrl + "manage/api/v1/organizations/"
                + URLEncoder.encode(organizationKey, "UTF-8") + "/c2c/live-sync/"
                + URLEncoder.encode(uuid, "UTF-8");
        String requestId = UUID.randomUUID().toString();
        JSONObject body = new JSONObject();
        body.put("enable", enable);
        HttpRequest request = HttpRequest.put(requestUrl)
                .header("x-auth-token", xAuthToken)
                .header("x-request-id", requestId)
                .contentType("application/json; charset=utf-8")
                .body(body.toJSONString())
                .setConnectionTimeout(10000).setReadTimeout(30000).setFollowRedirects(false);
        System.out.println(request.toString());
        try (HttpResponse response = request.execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IOException("司空接口 HTTP " + response.getStatus() + ", requestId=" + requestId);
            }
            JSONObject result;
            try {
                result = JSON.parseObject(response.body());
            } catch (RuntimeException e) {
                throw new IOException("司空接口响应不是有效 JSON 对象, requestId=" + requestId);
            }
            if (result == null || !(result.get("code") instanceof Number)) {
                throw new IOException("司空接口响应缺少有效 code, requestId=" + requestId);
            }
            if (!"0".equals(result.get("code").toString())) {
                throw new IOException("司空接口业务失败, code=" + result.get("code")
                        + ", message=" + result.getString("message") + ", requestId=" + requestId);
            }
            return result;
        } catch (HttpException e) {
            throw new IOException("司空接口网络请求失败, requestId=" + requestId, e);
        }
    }

    /**
     * 按设备 SN 精确匹配列表中的首个转发器，未找到返回 null。
     */
    public JSONObject getLiveStreamConverter(String querySn) throws IOException {
        if (querySn == null || querySn.trim().isEmpty()) {
            throw new IllegalArgumentException("querySn 不能为空");
        }
        JSONObject result = listLiveStreamConverters();
        JSONObject data = result.getJSONObject("data");
        JSONArray list = data == null ? null : data.getJSONArray("list");
        if (list == null) {
            throw new IOException("司空接口响应缺少 data.list");
        }
        for (int i = 0; i < list.size(); i++) {
            JSONObject converter = list.getJSONObject(i);
            if (converter != null && querySn.equals(converter.getString("sn"))) {
                return converter;
            }
        }
        return null;
    }
}
