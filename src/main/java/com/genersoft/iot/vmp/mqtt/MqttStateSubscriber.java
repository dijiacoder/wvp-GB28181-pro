package com.genersoft.iot.vmp.mqtt;

import com.genersoft.iot.vmp.conf.MqttProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 订阅产品状态并输出原始 UTF-8 消息。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
public class MqttStateSubscriber {
    private final MqttProperties properties;
    private final UavOnlineStatusService onlineStatus;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mqtt-state-subscriber");
        thread.setDaemon(true);
        return thread;
    });
    private MqttClient client;
    private boolean subscribed;
    private volatile boolean stopping;

    public MqttStateSubscriber(MqttProperties properties, UavOnlineStatusService onlineStatus) {
        this.properties = properties;
        this.onlineStatus = onlineStatus;
    }

    @PostConstruct
    public void start() throws MqttException {
        client = new MqttClient(properties.getUrl(), MqttClient.generateClientId(), new MemoryPersistence());
        client.setTimeToWait(10000);
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("[MQTT] 连接断开，将重新连接", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                onlineStatus.handleMessage(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 此客户端仅订阅消息。
            }
        });
        // 初次连接失败、断线或订阅失败均会重试，不阻塞应用启动。
        executor.scheduleWithFixedDelay(this::connectAndSubscribe, 0, 5, TimeUnit.SECONDS);
    }

    private synchronized void connectAndSubscribe() {
        if (stopping) {
            return;
        }
        try {
            if (!client.isConnected()) {
                subscribed = false;
                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(properties.getUsername());
                options.setPassword(properties.getPassword().toCharArray());
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(30);
                client.connect(options);
            }
            if (!subscribed) {
                client.subscribe(properties.getTopic(), 1);
                subscribed = true;
                log.info("[MQTT] 已订阅 topic={}", properties.getTopic());
            }
        } catch (Exception e) {
            log.warn("[MQTT] 连接或订阅失败，5 秒后重试", e);
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        executor.shutdownNow();
        synchronized (this) {
            if (client != null) {
                try {
                    if (client.isConnected()) {
                        client.disconnectForcibly(1000, 1000);
                    }
                    client.close(true);
                } catch (MqttException e) {
                    log.warn("[MQTT] 关闭客户端失败", e);
                }
            }
        }
    }
}
