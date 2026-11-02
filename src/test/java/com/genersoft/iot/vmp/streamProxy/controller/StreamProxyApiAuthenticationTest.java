package com.genersoft.iot.vmp.streamProxy.controller;

import com.genersoft.iot.vmp.conf.GlobalResponseAdvice;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.*;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxySummary;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.servlet.Filter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StreamProxyApiAuthenticationTest {
    @Configuration
    @EnableWebMvc
    @Import({WebSecurityConfig.class, JwtAuthenticationFilter.class, AnonymousAuthenticationEntryPoint.class,
            LogoutHandler.class, StreamProxyController.class, GlobalResponseAdvice.class})
    static class Config {
        @Bean UserSetting settings() {
            UserSetting settings = new UserSetting();
            settings.setInterfaceAuthentication(true);
            return settings;
        }
        @Bean com.genersoft.iot.vmp.service.IUserService userService() { return mock(com.genersoft.iot.vmp.service.IUserService.class); }
        @Bean DefaultUserDetailsServiceImpl users() { return mock(DefaultUserDetailsServiceImpl.class); }
        @Bean IStreamProxyService service() { return mock(IStreamProxyService.class); }
        @Bean IStreamProxyPlayService play() { return mock(IStreamProxyPlayService.class); }
        @Bean IMediaServerService media() { return mock(IMediaServerService.class); }
    }

    @Test
    void getStreamProxyAllowsAnonymousAccessAndReturnsUnifiedResult() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            IStreamProxyService service = context.getBean(IStreamProxyService.class);
            StreamProxySummary summary = new StreamProxySummary();
            summary.setApp("drone");
            summary.setStream("SN");
            summary.setOutUrl("rtmp://172.16.5.102:1935/drone/SN");
            summary.setPulling(1);
            summary.setEnable(1);
            when(service.getStreamProxy("SN")).thenReturn(summary);
            String json = "{\"app\":\"drone\",\"stream\":\"SN\",\"out_url\":\"rtmp://172.16.5.102:1935/drone/SN\",\"pulling\":1,\"enable\":1}";
            mvc.perform(get("/api/proxy/getStreamProxy").param("stream", "SN"))
                    .andExpect(status().isOk()).andExpect(content().json("{\"code\":0,\"msg\":\"成功\",\"data\":" + json + "}", true));
            mvc.perform(get("/api/proxy/getStreamProxy").param("stream", "SN")
                    .header(JwtUtils.getHeader(), "invalid-token"))
                    .andExpect(status().isOk()).andExpect(content().json("{\"code\":0,\"msg\":\"成功\",\"data\":" + json + "}", true));
            mvc.perform(get("/api/proxy/getStreamProxy").param("stream", "SN").header(JwtUtils.getApiKeyHeader(), "invalid-key"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/proxy/getStreamProxy").param("stream", "missing"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"code\":404,\"msg\":\"资源未找到\",\"data\":null}", true));
            mvc.perform(get("/api/proxy/getStreamProxy")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/proxy/list")).andExpect(status().isUnauthorized());
        }
    }
}
