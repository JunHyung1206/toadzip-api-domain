package test.domain.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class IngestConfig {

    @Bean
    public OpenApiClient lhApiClient(ObjectMapper objectMapper, IngestProperties properties) {
        return new OpenApiClient(objectMapper, properties.baseUrl().lh(), properties.serviceKey(), "lh");
    }

    @Bean
    public OpenApiClient myhomeNoticeApiClient(ObjectMapper objectMapper, IngestProperties properties) {
        return new OpenApiClient(objectMapper, properties.baseUrl().myhomeNotice(), properties.serviceKey(),
                "myhome-notice");
    }

    @Bean
    public OpenApiClient myhomeComplexApiClient(ObjectMapper objectMapper, IngestProperties properties) {
        return new OpenApiClient(objectMapper, properties.baseUrl().myhomeComplex(), properties.serviceKey(),
                "myhome-complex");
    }
}
