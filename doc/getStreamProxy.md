# 查询流代理接口

根据无人机 SN（流 ID）查询流代理状态、RTMP 和 HTTP-FLV 播放地址。

## 接口信息

| 项目 | 内容 |
| --- | --- |
| 请求地址 | `/api/proxy/getStreamProxy` |
| 请求方式 | `GET` |
| 权限认证 | 无需认证，无需传入 `access-token` 或 `api-key` |
| 返回格式 | `application/json`，使用 `WVPResult` 统一包装 |

## 请求参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `stream` | String | 是 | 流 ID，例如无人机 SN，不能为空，精确匹配 |

## 请求示例

将 `服务器IP:端口` 替换为实际 WVP 服务地址。

```bash
curl "http://172.16.5.102:18080/api/proxy/getStreamProxy?stream=1581F8HGX259500A0VJE"
```

## 成功响应

HTTP 状态码：`200`。

```json
{
  "code": 0,
  "msg": "成功",
  "data": {
    "app": "drone",
    "stream": "1581F8HGX259500A0VJE",
    "rtmpUrl": "rtmp://172.16.5.102:1935/drone/1581F8HGX259500A0VJE",
    "flvUrl": "http://172.16.5.102:80/drone/1581F8HGX259500A0VJE.live.flv",
    "pulling": 1,
    "enable": 1
  }
}
```

## 返回字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | Integer | 业务状态码，`0` 表示成功 |
| `msg` | String | 结果说明 |
| `data` | Object / null | 流代理信息，查询失败时为 `null` |
| `data.app` | String | 应用名 |
| `data.stream` | String | 流 ID，即查询的无人机 SN |
| `data.rtmpUrl` | String / null | RTMP 播放地址，无法生成时为 `null` |
| `data.flvUrl` | String / null | HTTP-FLV 播放地址，无法生成时为 `null` |
| `data.pulling` | Integer | 拉流状态：`1` 正在拉流，`0` 未拉流 |
| `data.enable` | Integer | 启用状态：`1` 启用，`0` 停用 |

## 未找到记录

HTTP 状态码仍为 `200`，通过业务状态码 `404` 表示未找到。

```json
{
  "code": 404,
  "msg": "资源未找到",
  "data": null
}
```

## stream 为空字符串或全为空白

HTTP 状态码为 `200`，业务状态码为 `400`。

```json
{
  "code": 400,
  "msg": "stream不能为空",
  "data": null
}
```

## 使用说明

- 同一 `stream` 存在多条记录时，返回数据库 ID 最大的一条。
- 已停用或未拉流的代理仍可查询，需结合 `pulling` 和 `enable` 判断状态。
- 播放地址根据代理当前流媒体节点、固定关联节点或默认节点配置生成，IP 和端口以实际返回为准。
- HTTP-FLV 地址优先使用节点配置的 FLV 端口；未配置时使用 HTTP 端口，路径为 `/{app}/{stream}.live.flv`。
- 返回播放地址不代表流当前可播放。
- 原字段 `out_url` 已替换为 `rtmpUrl`，并新增 `flvUrl`。
