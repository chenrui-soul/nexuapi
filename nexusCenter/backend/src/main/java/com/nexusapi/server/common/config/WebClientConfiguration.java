package com.nexusapi.server.common.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfiguration {

    @Bean
    WebClient upstreamWebClient(NexusProperties properties) {
        NexusProperties.Gateway gateway = properties.gateway();
        ConnectionProvider pool = ConnectionProvider.builder("nexus-upstream")
                .maxConnections(gateway.maxConnections())
                .pendingAcquireMaxCount(gateway.pendingAcquireMaxCount())
                .build();
        HttpClient client = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(gateway.connectTimeout().toMillis()))
                .responseTimeout(gateway.responseTimeout());

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(client))
                // 非流式响应和上游错误体设置硬上限，防止异常渠道返回超大 JSON 占满堆内存。
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(gateway.maxInMemoryBytes()))
                .build();
    }
}
