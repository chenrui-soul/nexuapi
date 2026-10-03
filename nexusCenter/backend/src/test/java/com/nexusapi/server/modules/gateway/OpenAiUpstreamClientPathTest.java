package com.nexusapi.server.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiUpstreamClientPathTest {
    private final Method appendSafePath = appendMethod();

    @Test
    void replacesTaskIdTemplateInsteadOfAppendingAfterTemplate() throws Exception {
        URI uri = invoke("https://caicaiapi.cloud/v1/images/tasks/{id}", "/task_123");

        assertThat(uri).isEqualTo(URI.create("https://caicaiapi.cloud/v1/images/tasks/task_123"));
    }

    @Test
    void keepsLegacyBaseAddressCompatibility() throws Exception {
        URI uri = invoke("https://caicaiapi.cloud/v1/images/tasks", "/task_123");

        assertThat(uri).isEqualTo(URI.create("https://caicaiapi.cloud/v1/images/tasks/task_123"));
    }

    @Test
    void rejectsPathTraversalInTaskId() {
        assertThatThrownBy(() -> invoke("https://caicaiapi.cloud/v1/images/tasks/{id}", "/../secret"))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    private URI invoke(String baseUrl, String suffix) throws Exception {
        OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                WebClient.builder().build(), null, new ObjectMapper()
        );
        return (URI) appendSafePath.invoke(client, baseUrl, suffix);
    }

    private static Method appendMethod() {
        try {
            Method method = OpenAiUpstreamClient.class.getDeclaredMethod("appendSafePath", String.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
