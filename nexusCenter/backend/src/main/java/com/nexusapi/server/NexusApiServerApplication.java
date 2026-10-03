package com.nexusapi.server;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.config.HealthProperties;
import com.nexusapi.server.common.config.DashboardProperties;
import com.nexusapi.server.common.config.ModelSyncProperties;
import com.nexusapi.server.common.config.PricingProperties;
import com.nexusapi.server.common.config.PaymentProperties;
import com.nexusapi.server.common.config.FileUploadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({
        NexusProperties.class, HealthProperties.class, DashboardProperties.class, ModelSyncProperties.class,
        PricingProperties.class, PaymentProperties.class, FileUploadProperties.class
})
@SpringBootApplication
public class NexusApiServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApiServerApplication.class, args);
    }
}
