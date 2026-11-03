# 全量查询流代理接口

| 项目 | 内容 |
| --- | --- |
| 请求地址 | `/api/proxy/listStreamProxy` |
| 请求方式 | GET |
| 权限认证 | 无需认证 |
| 请求参数 | 无，不分页 |
| 返回格式 | JSON，WVPResult 包装 |

查询 `wvp_stream_proxy` 全部记录，包含停用和未拉流的代理，按 ID 降序返回。

## 请求示例

```bash
curl "http://服务器IP:端口/api/proxy/listStreamProxy"
```

## 返回示例

```json
{
  "code": 0,
  "msg": "成功",
  "data": [{
    "app": "drone",
    "stream": "1581F8HGX259500A0VJE",
    "rtmpUrl": "rtmp://172.16.5.102:1935/drone/1581F8HGX259500A0VJE",
    "flvUrl": "http://172.16.5.102:80/drone/1581F8HGX259500A0VJE.live.flv",
    "pulling": 1,
    "enable": 1
  }]
}
```

`code=0` 表示成功；无记录时 `data=[]`。数据项字段及播放地址生成规则与 [单条查询接口](getStreamProxy.md) 一致。`pulling`、`enable` 使用数字 0/1，无法生成的播放地址为 null。返回播放地址不保证当前可播放。
