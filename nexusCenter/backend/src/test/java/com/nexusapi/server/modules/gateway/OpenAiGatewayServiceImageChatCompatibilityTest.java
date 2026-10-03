package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.modules.apikey.service.ApiKeyAuthenticationService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.billing.service.BillingService;
import com.nexusapi.server.modules.gateway.adapter.CapabilityAdapterRegistry;
import com.nexusapi.server.modules.gateway.capability.image.model.OpenAiImageGenerationRequest;
import com.nexusapi.server.modules.gateway.security.GatewayCallerPrincipal;
import com.nexusapi.server.modules.gateway.service.OpenAiGatewayService;
import com.nexusapi.server.modules.gateway.support.GatewayPricingService;
import com.nexusapi.server.modules.gateway.support.TokenEstimator;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import com.nexusapi.server.modules.health.service.ChannelHealthService;
import com.nexusapi.server.modules.quota.service.GatewayQuotaService;
import com.nexusapi.server.modules.requestlog.service.RequestLogService;
import com.nexusapi.server.modules.requestlog.service.RequestPayloadSummaryService;
import com.nexusapi.server.modules.routing.service.GatewayRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiGatewayServiceImageChatCompatibilityTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayRoutingService routingService = mock(GatewayRoutingService.class);
    private final RequestPayloadSummaryService payloadSummaryService = mock(RequestPayloadSummaryService.class);
    private OpenAiGatewayService service;
    private GatewayCallerPrincipal principal;
    private ClientRequestMetadata metadata;

    @BeforeEach
    void setUp() {
        NexusProperties nexusProperties = mock(NexusProperties.class);
        when(nexusProperties.gateway()).thenReturn(new NexusProperties.Gateway(
                Duration.ofSeconds(1), Duration.ofSeconds(10), 10, 10, 3,
                4096, 1_000_000, Duration.ofMinutes(1), Duration.ofSeconds(1)
        ));
        service = spy(new OpenAiGatewayService(
                objectMapper,
                routingService,
                mock(GatewayQuotaService.class),
                mock(GatewayPricingService.class),
                mock(TokenEstimator.class),
                mock(BillingService.class),
                mock(OpenAiUpstreamClient.class),
                mock(CapabilityAdapterRegistry.class),
                mock(ChannelHealthService.class),
                mock(RequestLogService.class),
                payloadSummaryService,
                mock(ApiKeyAuthenticationService.class),
                nexusProperties
        ));
        principal = mock(GatewayCallerPrincipal.class);
        when(principal.ipAllowlist()).thenReturn(List.of());
        metadata = new ClientRequestMetadata("127.0.0.1", "user-agent-hash", "req_workbuddy_unit");
        when(routingService.isImageModel("gpt-image-2")).thenReturn(true);
    }

    @Test
    void wrapsImageResponseAsNonStreamingChatCompletion() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-image-2");
        request.put("stream", false);
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", "忽略这条系统消息");
        messages.addObject().put("role", "user").put("content", "一只戴草帽的小猫");
        doReturn(imageResponse("https://img.test/workbuddy.png"))
                .when(service).imageGenerations(eq(principal), any(OpenAiImageGenerationRequest.class),
                        isNull(), eq(metadata));

        OpenAiGatewayService.GatewayResult result = service.chatCompletions(
                principal, request, null, metadata
        );

        assertThat(result).isInstanceOf(OpenAiGatewayService.JsonResult.class);
        JsonNode body = ((OpenAiGatewayService.JsonResult) result).body();
        assertThat(body.path("object").asText()).isEqualTo("chat.completion");
        assertThat(body.path("model").asText()).isEqualTo("gpt-image-2");
        assertThat(body.path("choices").path(0).path("message").path("content").asText())
                .isEqualTo("![Generated image](https://img.test/workbuddy.png)");

        ArgumentCaptor<OpenAiImageGenerationRequest> imageRequest =
                ArgumentCaptor.forClass(OpenAiImageGenerationRequest.class);
        verify(service).imageGenerations(eq(principal), imageRequest.capture(), isNull(), eq(metadata));
        assertThat(imageRequest.getValue().prompt()).isEqualTo("一只戴草帽的小猫");
        assertThat(imageRequest.getValue().quantity()).isOne();
    }

    @Test
    void wrapsImageResponseAsStreamingChatCompletion() throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-image-2");
        request.put("stream", true);
        ObjectNode message = request.putArray("messages").addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "text").put("text", "电影感城市夜景");
        content.addObject().put("type", "image_url")
                .putObject("image_url").put("url", "https://input.test/ref.png");
        doReturn(imageResponse("https://img.test/stream.png"))
                .when(service).imageGenerations(eq(principal), any(OpenAiImageGenerationRequest.class),
                        isNull(), eq(metadata));

        OpenAiGatewayService.GatewayResult result = service.chatCompletions(
                principal, request, null, metadata
        );

        assertThat(result).isInstanceOf(OpenAiGatewayService.StreamResult.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((OpenAiGatewayService.StreamResult) result).body().writeTo(output);
        String body = output.toString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("![Generated image](https://img.test/stream.png)")
                .contains("\"finish_reason\":\"stop\"")
                .endsWith("data: [DONE]\n\n");
    }

    private ObjectNode imageResponse(String url) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("created", 1_730_000_000L);
        response.putArray("data").addObject().put("url", url);
        return response;
    }
}
