package test.domain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiClientTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("LH 응답은 resHeader와 성공 코드 Y가 모두 있어야 성공이다")
    void requiresExplicitLhSuccessHeader() {
        assertThatThrownBy(() -> OpenApiClient.verifyResultCode(mapper.readTree("[]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resHeader");
        assertThatThrownBy(() -> OpenApiClient.verifyResultCode(
                mapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"\"}]}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SS_CODE");
        assertThatThrownBy(() -> OpenApiClient.verifyResultCode(
                mapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"N\",\"RS_MSG\":\"오류\"}]}]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SS_CODE=N");

        assertThatCode(() -> OpenApiClient.verifyResultCode(
                mapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("서비스키는 원문과 이미 인코딩된 값을 모두 같은 URI로 만든다")
    void acceptsDecodedAndEncodedServiceKeys() {
        OpenApiClient decoded = new OpenApiClient(mapper, "https://example.com", "a+b/c==", "test");
        OpenApiClient encoded = new OpenApiClient(
                mapper, "https://example.com", "a%2Bb%2Fc%3D%3D", "test");

        assertThat(decoded.buildUri("items", new LinkedMultiValueMap<>()))
                .isEqualTo(encoded.buildUri("items", new LinkedMultiValueMap<>()))
                .hasToString("https://example.com/items?serviceKey=a%2Bb%2Fc%3D%3D");
    }
}
