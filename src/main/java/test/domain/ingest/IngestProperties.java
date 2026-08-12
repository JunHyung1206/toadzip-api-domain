package test.domain.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증키는 계정 단위라 네 API가 같은 값을 쓴다. 엔드포인트만 갈라진다.
 *
 * @param baseUrl 원천별 엔드포인트. 마이홈은 단지(HWSPR04)와 공고(HWSPR02)가 서로 다른 서비스다.
 */
@ConfigurationProperties(prefix = "domain.ingest")
public record IngestProperties(String serviceKey, BaseUrl baseUrl) {

    public record BaseUrl(String lh, String myhomeNotice, String myhomeComplex) {
    }
}
