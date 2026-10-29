package com.genersoft.iot.vmp.conf.exception;

import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControllerExceptionTest {
    @Test
    void exposesBusinessMessageThroughStandardExceptionInterface() {
        ControllerException exception = new ControllerException(100, "查询拉流代理失败");
        assertEquals("查询拉流代理失败", exception.getMessage());
        assertEquals(100, exception.getCode());
        assertTrue(exception.toString().contains("查询拉流代理失败"));
    }

    @Test
    void errorCodeConstructorAndUpdatedMessageAreConsistent() {
        ControllerException exception = new ControllerException(ErrorCode.ERROR100);
        assertEquals(ErrorCode.ERROR100.getMsg(), exception.getMessage());
        exception.setMsg("请求失败");
        assertEquals(exception.getMsg(), exception.getMessage());
    }
}
