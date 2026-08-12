package test.domain.ingest;

import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * data.go.kr 공통 호출부.
 *
 * <p>인증키를 쿼리 파라미터로 넘기지 않고 문자열로 직접 이어붙인다.
 * 포털이 주는 Encoding 키에는 이미 %2B, %2F, %3D 같은 이스케이프가 들어 있어서
 * 다시 인코딩하면 %252B 가 되어 인증에 실패한다. 나머지 파라미터(한글 공고명 등)는 인코딩해야 하므로 둘을 분리했다.
 */
public class OpenApiClient {

    /** 국토부 계열은 자료 없음을 오류코드로 준다. 빈 페이지일 뿐이라 예외로 올리지 않는다. */
    private static final String MOLIT_NO_DATA = "03";
    private static final String MOLIT_SUCCESS = "00";
    private static final String LH_SUCCESS = "00";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String serviceKey;
    private final String name;

    public OpenApiClient(ObjectMapper objectMapper, String baseUrl, String serviceKey, String name) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.name = name;
    }

    public JsonNode getRaw(String path, MultiValueMap<String, String> params) {
        requireConfigured();
        JsonNode root = restClient.get()
                .uri(buildUri(path, params))
                .retrieve()
                .body(JsonNode.class);
        if (root == null) {
            throw new IllegalStateException("%s 응답이 비어 있습니다: %s".formatted(name, path));
        }
        verifyResultCode(root);
        return root;
    }

    public <T> List<T> getList(String path, MultiValueMap<String, String> params, String listKey, Class<T> type) {
        List<JsonNode> rows = findRows(getRaw(path, params), listKey);
        List<T> items = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            items.add(objectMapper.convertValue(row, type));
        }
        return items;
    }

    private void requireConfigured() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("domain.ingest.base-url.%s 가 비어 있습니다.".formatted(name));
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException(
                    "domain.ingest.service-key 가 비어 있습니다. DATA_GO_KR_SERVICE_KEY 를 설정하세요.");
        }
    }

    private URI buildUri(String path, MultiValueMap<String, String> params) {
        String query = UriComponentsBuilder.newInstance()
                .queryParams(params)
                .build()
                .encode(StandardCharsets.UTF_8)
                .getQuery();
        String uri = "%s/%s?serviceKey=%s".formatted(baseUrl, path, serviceKey);
        return URI.create(query == null || query.isBlank() ? uri : uri + "&" + query);
    }

    /**
     * 두 원천의 응답 모양이 다르다.
     * <ul>
     *   <li>국토부(1613000): {@code {"response":{"header":{...},"body":{"item":[...]}}}} — JSON Pointer 로 찾는다.
     *       행이 하나뿐이면 배열이 아니라 객체로 오는 경우가 있어 그것도 받아 준다.</li>
     *   <li>LH(B552555): {@code [{"resHeader":[...]},{"dsList":[...]}]} — 배열 안에서 키 이름으로 찾는다.</li>
     * </ul>
     */
    public static List<JsonNode> findRows(JsonNode root, String listKey) {
        JsonNode found = listKey.startsWith("/") ? root.at(listKey) : findByKey(root, listKey);
        if (found.isArray()) {
            List<JsonNode> rows = new ArrayList<>(found.size());
            found.forEach(rows::add);
            return rows;
        }
        return found.isObject() ? List.of(found) : List.of();
    }

    private static JsonNode findByKey(JsonNode root, String listKey) {
        if (root.isArray()) {
            for (JsonNode element : root) {
                JsonNode found = element.path(listKey);
                if (!found.isMissingNode()) {
                    return found;
                }
            }
        }
        return root.path(listKey);
    }

    static void verifyResultCode(JsonNode root) {
        JsonNode molitHeader = root.path("response").path("header");
        if (molitHeader.isObject()) {
            String code = molitHeader.path("resultCode").asString("");
            if (!code.isEmpty() && !MOLIT_SUCCESS.equals(code) && !MOLIT_NO_DATA.equals(code)) {
                throw new IllegalStateException("원천 오류 resultCode=%s, %s"
                        .formatted(code, molitHeader.path("resultMsg").asString("")));
            }
            return;
        }

        List<JsonNode> lhHeader = findRows(root, "resHeader");
        if (lhHeader.isEmpty()) {
            return;
        }
        JsonNode first = lhHeader.get(0);
        String code = first.path("SS_CODE").asString("");
        if (!code.isEmpty() && !LH_SUCCESS.equals(code)) {
            throw new IllegalStateException("원천 오류 SS_CODE=%s, %s"
                    .formatted(code, first.path("RS_MSG").asString("")));
        }
    }
}
