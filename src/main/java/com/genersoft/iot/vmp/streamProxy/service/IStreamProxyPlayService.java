package com.genersoft.iot.vmp.streamProxy.service;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;

import javax.validation.constraints.NotNull;

public interface IStreamProxyPlayService {

    void start(int id, Boolean record, ErrorCallback<StreamInfo> callback);

    /** 只查询已在线的流；离线立即提示等待，不创建代理或等待后台回调。 */
    void startProxy(@NotNull StreamProxy streamProxy, ErrorCallback<StreamInfo> callback);

    /** 后台调度专用，发起实际拉流。 */
    void startInBackground(StreamProxy streamProxy, ErrorCallback<StreamInfo> callback);

    void stop(int id);

    void stopProxy(StreamProxy streamProxy);

    /** 与后台提交共用流锁，撤销旧代理代次、清理拉流后再删除配置。 */
    void removeProxy(StreamProxy streamProxy, Runnable removeAction);
}
