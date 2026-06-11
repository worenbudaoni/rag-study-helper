package com.rag.studyhelper.feishu.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.client.WikiNode;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.service.DocumentIngestionService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 这里没有 @Service 注解是因为需要在 FeishuConfig 中配置注入
 */
public class FeishuSyncService {

    private static final Logger log = LoggerFactory.getLogger(FeishuSyncService.class);

    // 自定义飞书客户端
    private final FeishuClient feishuClient;
    // 处理文档的工具
    private final DocumentIngestionService ingestionService;
    // 飞书 wiki 空间ID
    private final String spaceId;

    @Autowired
    private DocumentsMapper documentsMapper;

    @Autowired
    private DocumentChunksMapper documentChunksMapper;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    public FeishuSyncService(FeishuClient feishuClient,
                             DocumentIngestionService ingestionService, String spaceId) {
        if (spaceId == null || spaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("app.feishu.space-id 未配置，可通过 FeishuClient.listSpaces() 获取可用 space_id");
        }
        this.feishuClient = feishuClient;
        this.ingestionService = ingestionService;
        this.spaceId = spaceId.trim();
    }

//    可以用来测试启动后 飞书文档 同步功能
//    @PostConstruct
//    public void init(){
//        syncWiki();
//    }

    @Scheduled(cron = "${app.feishu.cron}")
    public void syncWiki() {
        log.info("Starting Feishu wiki sync for space: {}", spaceId);
        try {
            List<WikiNode> nodes = feishuClient.getWikiNodeTree(spaceId);
            log.info("Found {} nodes in wiki", nodes.size());

            int synced = 0, skipped = 0, failed = 0;

            for (WikiNode node : nodes) {
                String objType = node.getObjType();
                String nodeToken = node.getNodeToken();
                long updateTime = node.getUpdateTime();

                Documents doc = documentsMapper.selectOne(
                        Wrappers.<Documents>lambdaQuery()
                                .eq(Documents::getFeishuNodeToken, nodeToken)
                );
                if (doc != null && doc.getFeishuUpdateTime() != null
                        && doc.getFeishuUpdateTime() == updateTime) {
                    skipped++;
                    continue;
                }

                try {
                    String content;
                    String fileName;
                    switch (objType) {
                        case "doc":
                        case "docx":
                            content = feishuClient.getDocumentContent(node.getObjToken());
                            fileName = node.getNodeTitle() + "_文档";
                            break;
                        case "sheet":
                            content = feishuClient.getSheetContent(node.getObjToken());
                            fileName = node.getNodeTitle() + "_表格";
                            break;
                        case "bitable":
                            content = feishuClient.getBitableContent(node.getObjToken());
                            fileName = node.getNodeTitle() + "_多维表格";
                            break;
                        default:
                            skipped++;
                            continue;
                    }

                    // 如果是更新，先删旧向量和映射记录
                    if (doc != null) {
                        // 查询旧文档相关的向量映射
                        List<DocumentChunks> oldChunks = documentChunksMapper.selectList(
                                Wrappers.<DocumentChunks>lambdaQuery()
                                        .eq(DocumentChunks::getDocumentId, doc.getId())
                        );
                        List<String> vectorIds = oldChunks.stream()
                                .map(DocumentChunks::getVectorId)
                                .collect(Collectors.toList());
                        // 删除向量
                        embeddingStore.removeAll(vectorIds);

                        // 删除映射记录
                        documentChunksMapper.delete(
                                Wrappers.<DocumentChunks>lambdaQuery()
                                        .eq(DocumentChunks::getDocumentId, doc.getId())
                        );
                        // 删除文档
                        documentsMapper.deleteById(doc.getId());
                    }

                    // 插入文档 RAG 流程
                    ingestionService.ingestFeishuDocument(fileName, content, nodeToken, updateTime, objType);
                    synced++;
                    log.info("  Synced: {} ({})", node.getNodeTitle(), nodeToken);
                } catch (Exception e) {
                    log.error("  Failed to sync node: {} ({})", node.getNodeTitle(), nodeToken, e);
                    failed++;
                }
            }

            // 清理远程已删除的文档
            // 这里的逻辑是 第一次飞书给了 A、B、C 入库，删了C，第二次只有 A、B 就去数据库中和向量库中删 C
            // MySQL 查出本地多出的记录，只遍历需要删除的
            List<String> remoteTokens = nodes.stream()
                    .map(WikiNode::getNodeToken)
                    .collect(Collectors.toList());
            if (!remoteTokens.isEmpty()) {
                List<Documents> toRemove = documentsMapper.selectList(
                        Wrappers.<Documents>lambdaQuery()
                                .isNotNull(Documents::getFeishuNodeToken)
                                .notIn(Documents::getFeishuNodeToken, remoteTokens)
                );
                for (Documents removed : toRemove) {
                    log.info("Document removed remotely, cleaning up: {} ({})", removed.getDocumentName(), removed.getFeishuNodeToken());
                    List<DocumentChunks> chunks = documentChunksMapper.selectList(
                            Wrappers.<DocumentChunks>lambdaQuery()
                                    .eq(DocumentChunks::getDocumentId, removed.getId())
                    );
                    List<String> vectorIds = chunks.stream()
                            .map(DocumentChunks::getVectorId)
                            .collect(Collectors.toList());
                    embeddingStore.removeAll(vectorIds);

                    documentChunksMapper.delete(
                            Wrappers.<DocumentChunks>lambdaQuery()
                                    .eq(DocumentChunks::getDocumentId, removed.getId())
                    );
                    documentsMapper.deleteById(removed.getId());
                }
            }

            log.info("Feishu wiki sync complete: synced={}, skipped={}, failed={}",
                    synced, skipped, failed);
        } catch (Exception e) {
            log.error("Feishu wiki sync failed", e);
        }
    }
}
