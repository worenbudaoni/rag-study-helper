package com.rag.studyhelper.feishu.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书开放平台 API 封装。
 * 通过 FeishuConfig 注册到 spring bean（如果 app.feishu.sync-enabled 参数不为 true 则不注入）
 */
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String appId;
    private final String appSecret;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 缓存 token
    private String cachedToken;
    // token 过期时间
    private long tokenExpireAt;

    public FeishuClient(String appId, String appSecret, String baseUrl, OkHttpClient httpClient) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取 tenant_access_token（内部自动缓存和刷新）
     */
    public synchronized String getAccessToken() throws IOException {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        String json = "{\"app_id\":\"" + appId + "\",\"app_secret\":\"" + appSecret + "\"}";
        // https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal
        Request request = new Request.Builder()
                .url(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal")
                .post(RequestBody.create(JSON, json))
                .build();
        try (Response resp = httpClient.newCall(request).execute()) {
            JsonNode body = objectMapper.readTree(resp.body().string());
            if (body.get("code").asInt() != 0) {
                throw new IOException("Failed to get access token: " + body);
            }
            cachedToken = body.get("tenant_access_token").asText();
            // tenant_access_token 的最大有效期是 2 小时
            // 7200 是秒
            int expire = body.get("expire").asInt(7200);
            // 防御性编程 免得刚好过期 由于网络延时 造成接口调用失败
            tokenExpireAt = System.currentTimeMillis() + (expire - 60) * 1000L;
            return cachedToken;
        }
    }

    /**
     * 获取所有知识库空间列表，用于查找 space_id。
     * 后需要做权限升级可以从这里入手
     *
     * @return 知识库列表，每个元素包含 name、space_id 等字段
     */
    public List<JsonNode> listSpaces() throws IOException {
        List<JsonNode> result = new ArrayList<>();
        // https://open.feishu.cn/document/server-docs/docs/wiki-v2/space/list
        String url = baseUrl + "/open-apis/wiki/v2/spaces?page_size=50";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();
        try (Response resp = httpClient.newCall(request).execute()) {
            JsonNode body = objectMapper.readTree(resp.body().string());
            if (body.get("code").asInt() != 0) {
                log.error("List spaces API error: {}", body);
                return result;
            }
            JsonNode items = body.path("data").path("items");
            log.info("=== 可用知识库列表（{} 个）===", items.size());
            for (JsonNode item : items) {
                String name = item.path("name").asText();
                String spaceId = item.path("space_id").asText();
                log.info("  name: {}, space_id: {}", name, spaceId);
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 递归获取知识库所有文档节点。
     */
    public List<WikiNode> getWikiNodeTree(String spaceId) throws IOException {
        List<WikiNode> allNodes = new ArrayList<>();
        collectNodes(spaceId, null, allNodes);
        return allNodes;
    }

    private void collectNodes(String spaceId, String parentNodeToken, List<WikiNode> result) throws IOException {
        List<WikiNode> currentLevelNodes = new ArrayList<>();
        String pageToken = null;
        do {
            // https://open.feishu.cn/document/server-docs/docs/wiki-v2/space-node/create
            StringBuilder url = new StringBuilder(baseUrl + "/open-apis/wiki/v2/spaces/" + spaceId + "/nodes");
            if (parentNodeToken != null) {
                url.append("/").append(parentNodeToken).append("/children");
            }
            url.append("?page_size=50");
            if (pageToken != null) {
                url.append("&page_token=").append(pageToken);
            }

            Request request = new Request.Builder()
                    .url(url.toString())
                    .header("Authorization", "Bearer " + getAccessToken())
                    .get()
                    .build();

            try (Response resp = httpClient.newCall(request).execute()) {
                JsonNode body = objectMapper.readTree(resp.body().string());
                if (body.get("code").asInt() != 0) {
                    log.error("Wiki API error for URL [{}]: {}", url, body);
                    break;
                }

                JsonNode items = body.path("data").path("items");
                for (JsonNode item : items) {
                    WikiNode node = new WikiNode();
                    // 节点token
                    node.setNodeToken(item.path("node_token").asText());
                    // 对应文档类型的token，可根据 obj_type 判断属于哪种文档类型。
                    node.setObjToken(item.path("obj_token").asText());
                    // 文档类型，对于快捷方式，该字段是对应的实体的obj_type。
                    // 可选值有：
                    // doc：旧版文档 sheet：表格 mindnote：思维导图 bitable：多维表格 file：文件 docx：新版文档 slides：幻灯片
                    node.setObjType(item.path("obj_type").asText());
                    // 文档标题
                    node.setNodeTitle(item.path("title").asText());
                    node.setParentNodeToken(parentNodeToken);
                    // 是否有子节点
                    node.setHasChild(item.path("has_child").asBoolean(false));
                    // 文档最近编辑时间
                    String editTime = item.path("obj_edit_time").asText();
                    node.setUpdateTime(Long.parseLong(editTime.isEmpty() ? "0" : editTime));
                    currentLevelNodes.add(node);
                }

                pageToken = body.path("data").path("page_token").asText(null);
            }
        } while (pageToken != null && !pageToken.isEmpty());

        // Add all nodes from this level, then recurse into children
        result.addAll(currentLevelNodes);
        for (WikiNode node : currentLevelNodes) {
            if (node.isHasChild()) {
                collectNodes(spaceId, node.getNodeToken(), result);
            }
        }
    }

    /**
     * 获取飞书文档纯文本内容。
     */
    public String getDocumentContent(String documentToken) throws IOException {
        // https://open.feishu.cn/document/server-docs/docs/docs/docx-v1/document/raw_content
        String url = baseUrl + "/open-apis/docx/v1/documents/" + documentToken + "/raw_content";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();
        try (Response resp = httpClient.newCall(request).execute()) {
            JsonNode body = objectMapper.readTree(resp.body().string());
            if (body.get("code").asInt() != 0) {
                throw new IOException("Failed to get document content: " + body);
            }
            return body.path("data").path("content").asText();
        }
    }

    /**
     * 获取电子表格内容，返回 Markdown 表格格式文本。
     * 下面接口看这个，这里是汇总的
     * https://open.feishu.cn/document/sales-statistics-base-on-spreadsheets/sales-statistics-based-on-sheets
     */
    public String getSheetContent(String spreadsheetToken) throws IOException {
        // 先获取元信息，知道有哪些 sheet 和行列数
        String metaUrl = baseUrl + "/open-apis/sheets/v2/spreadsheets/" + spreadsheetToken + "/metainfo";
        Request metaRequest = new Request.Builder()
                .url(metaUrl)
                .header("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();
        List<String> sheetRanges = new ArrayList<>();
        try (Response resp = httpClient.newCall(metaRequest).execute()) {
            JsonNode body = objectMapper.readTree(resp.body().string());
            if (body.get("code").asInt() != 0) {
                throw new IOException("Failed to get sheet meta: " + body);
            }
            JsonNode sheets = body.path("data").path("sheets");
            for (JsonNode sheet : sheets) {
                String title = sheet.path("title").asText();
                int rowCount = sheet.path("row_count").asInt(100);
                int colCount = sheet.path("column_count").asInt(26);
                sheetRanges.add(title + "!A1:" + toExcelCol(colCount) + rowCount);
            }
        }

        StringBuilder result = new StringBuilder();
        for (String range : sheetRanges) {
            String sheetTitle = range.substring(0, range.indexOf('!'));
            result.append("=== 工作表: ").append(sheetTitle).append(" ===\n");

            String dataUrl = baseUrl + "/open-apis/sheets/v2/spreadsheets/" + spreadsheetToken
                    + "/values/" + range;
            Request dataRequest = new Request.Builder()
                    .url(dataUrl)
                    .header("Authorization", "Bearer " + getAccessToken())
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(dataRequest).execute()) {
                JsonNode body = objectMapper.readTree(resp.body().string());
                if (body.get("code").asInt() != 0) {
                    log.warn("  Failed to read sheet range {}: {}", range, body);
                    continue;
                }
                JsonNode values = body.path("data").path("valueRange").path("values");
                for (JsonNode row : values) {
                    if (row.isArray()) {
                        for (int i = 0; i < row.size(); i++) {
                            result.append(row.get(i).asText());
                            if (i < row.size() - 1) result.append(" | ");
                        }
                    }
                    result.append("\n");
                }
            }
            result.append("\n");
        }
        return result.toString();
    }

    /**
     * 获取多维表格内容，返回文本格式。
     * 下面接口看这个，这里是汇总的
     * https://open.feishu.cn/document/quick-access-to-base/preparation
     */
    public String getBitableContent(String appToken) throws IOException {
        StringBuilder result = new StringBuilder();
        // 列出所有表
        String tablesUrl = baseUrl + "/open-apis/bitable/v1/apps/" + appToken + "/tables?page_size=50";
        Request tablesRequest = new Request.Builder()
                .url(tablesUrl)
                .header("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();
        List<JsonNode> tables = new ArrayList<>();
        try (Response resp = httpClient.newCall(tablesRequest).execute()) {
            JsonNode body = objectMapper.readTree(resp.body().string());
            if (body.get("code").asInt() != 0) {
                throw new IOException("Failed to list bitable tables: " + body);
            }
            body.path("data").path("items").forEach(tables::add);
        }

        for (JsonNode table : tables) {
            String tableId = table.path("table_id").asText();
            String tableName = table.path("name").asText();
            result.append("=== 多维表格: ").append(tableName).append(" ===\n");

            String pageToken = null;
            do {
                StringBuilder recordsUrl = new StringBuilder(
                        baseUrl + "/open-apis/bitable/v1/apps/" + appToken
                                + "/tables/" + tableId + "/records?page_size=20");
                if (pageToken != null) {
                    recordsUrl.append("&page_token=").append(pageToken);
                }

                Request recordsRequest = new Request.Builder()
                        .url(recordsUrl.toString())
                        .header("Authorization", "Bearer " + getAccessToken())
                        .get()
                        .build();
                try (Response resp = httpClient.newCall(recordsRequest).execute()) {
                    JsonNode body = objectMapper.readTree(resp.body().string());
                    if (body.get("code").asInt() != 0) {
                        log.warn("  Failed to read bitable records: {}", body);
                        break;
                    }
                    JsonNode items = body.path("data").path("items");
                    for (JsonNode item : items) {
                        JsonNode fields = item.path("fields");
                        fields.fieldNames().forEachRemaining(field -> {
                            result.append(field).append(": ").append(fields.get(field)).append("\n");
                        });
                        result.append("---\n");
                    }
                    pageToken = body.path("data").path("page_token").asText(null);
                }
            } while (pageToken != null && !pageToken.isEmpty());
            result.append("\n");
        }
        return result.toString();
    }

    private String toExcelCol(int col) {
        StringBuilder colStr = new StringBuilder();
        while (col > 0) {
            col--;
            colStr.insert(0, (char) ('A' + col % 26));
            col /= 26;
        }
        return colStr.toString();
    }
}
