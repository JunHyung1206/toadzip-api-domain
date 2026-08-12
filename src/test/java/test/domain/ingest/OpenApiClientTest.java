package test.domain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
}
