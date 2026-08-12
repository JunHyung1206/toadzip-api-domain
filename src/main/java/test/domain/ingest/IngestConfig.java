package test.domain.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration
public class IngestConfig {

    /** 적재 시각(fetchedAt) 등에 쓴다. 테스트는 고정 Clock 을 직접 만들어 넣는다. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

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
