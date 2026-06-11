package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.Documents;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档处理服务
 * 包括 文档解析、向量化、入库（向量数据库 + 关系型数据库）
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentsMapper documentsMapper;

    @Autowired
    private DocumentChunksMapper documentChunksMapper;

    @Value("${app.rag.document-scan-path}")
    private String scanPath;

    // 初始化用于跑本地文档
    @PostConstruct
    public void init() {
        scanAndIngest();
    }

    /**
     * 判断文件内容是否已经入库，通过hash判断文件一致性
     */
    private String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 扫描目录下所有文件，并解析入库
     */
    public List<DocumentInfo> scanAndIngest() {
        log.info("Scanning document directory: {}", scanPath);
        List<DocumentInfo> results = new ArrayList<>();
        File dir = new File(scanPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created document directory: {}", scanPath);
            return results;
        }
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                    || n.endsWith(".json") || n.endsWith(".xml")
                    || n.endsWith(".pdf")
                    || n.endsWith(".xlsx") || n.endsWith(".xls")
                    || n.endsWith(".docx")
                    || n.endsWith(".pptx")
                    || n.endsWith(".html") || n.endsWith(".htm");
        });
        if (files == null || files.length == 0) {
            log.info("No documents found in {}", scanPath);
            return results;
        }
        for (File file : files) {
            try {
                DocumentInfo info = ingestDocument(file.toPath());
                results.add(info);
            } catch (Exception e) {
                log.error("Failed to ingest document: {}", file.getName(), e);
            }
        }
        log.info("Document scan complete. Total ingested: {}", results.size());
        return results;
    }

    /**
     * 通过文件路径直接解析文档入库
     */
    public DocumentInfo ingestDocument(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        try (InputStream inputStream = new FileInputStream(filePath.toFile())) {
            return ingestDocument(fileName, inputStream);
        }
    }

    /**
     * 通过输入流直接解析文档入库
     */
    public DocumentInfo ingestDocument(String fileName, InputStream inputStream) throws IOException {
        byte[] content = IOUtils.toByteArray(inputStream);
        String hash = sha256(content);
        log.info("Ingesting document: {}, hash={}", fileName, hash);

        // 检查文件是否已经入库
        Documents existing = documentsMapper.selectOne(
                Wrappers.<Documents>lambdaQuery().eq(Documents::getContentHash, hash)
        );
        if (existing != null) {
            log.info("Document already ingested: {} (hash={})", existing.getDocumentName(), hash);
            return new DocumentInfo(existing.getId(), existing.getDocumentName(), existing.getChunkCount());
        }

        // 解析文档
        Document document = parseDocument(fileName, new ByteArrayInputStream(content));
        // 入库（向量数据库 + 关系型数据库）
        return processAndSave(document, fileName, "UPLOAD", hash, (long) content.length,
                null, null, null, "upload");
    }

    /**
     * 直接传入文档文本内容入库。适用于飞书等文本来源。
     */
    public DocumentInfo ingestDocument(String fileName, String content) throws IOException {
        Document document = Document.from(content);
        return processAndSave(document, fileName, null, null, null,
                null, null, null, "system");
    }

    /**
     * 飞书文档入库（携带飞书元数据）。
     */
    public DocumentInfo ingestFeishuDocument(String fileName, String content,
                                             String nodeToken, long updateTime, String objType) throws IOException {
        Document document = Document.from(content);
        return processAndSave(document, fileName, "FEISHU", null, null,
                nodeToken, objType, updateTime, "system");
    }

    /**
     * 解析文档
     */
    private Document parseDocument(String fileName, InputStream inputStream) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser().parse(inputStream);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".xml")) {
            return new TextDocumentParser().parse(inputStream);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcel(inputStream);
        } else if (lower.endsWith(".docx")) {
            return parseWord(inputStream);
        } else if (lower.endsWith(".pptx")) {
            return parsePowerPoint(inputStream);
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return parseHtml(inputStream);
        } else {
            throw new IllegalArgumentException("Unsupported file: " + fileName);
        }
    }

    /**
     * 入库主流程
     */
    private DocumentInfo processAndSave(Document document, String fileName, String source,
                                        String contentHash, Long fileSize,
                                        String feishuNodeToken, String feishuObjType,
                                        Long feishuUpdateTime, String creator) throws IOException {
        Tokenizer tokenizer = new OpenAiTokenizer();
        String prefix = "[来源:" + fileName + "]\n";
        int prefixTokenCount = tokenizer.estimateTokenCountInText(prefix);
        if (prefixTokenCount > 200) {
            log.warn("文件名前缀占用 token 过多: {} tokens, fileName={}", prefixTokenCount, fileName);
        }

        // bge-large-zh-v1.5 的上限为 512 token （虽然 bge-large-zh-v1.5 上限低，但他在硅基流动上是免费的，而且能力也不错）
        // 如果切换模型后，那么向量数据库中记录的数据都不可用了，要注意哦
        int maxSegmentSize = Math.max(50, 512 - prefixTokenCount);
        int maxOverlap = 51;
        // 基于 token 的分割器，层级降级（官方推荐）
        DocumentSplitter splitter = DocumentSplitters.recursive(
                // maxSegmentSize: 每个分段最大token数
                maxSegmentSize,
                // maxOverlap: 段落间重叠token数
                maxOverlap,
                // separator 优先级
                tokenizer
        );
        List<TextSegment> segments = splitter.split(document);

        segments.replaceAll(textSegment -> TextSegment.from(
                prefix + textSegment.text()));

//        todo 权限 rag 新增 仅 chroma 和 milvus 使用 如果是 私有文档，使用 .put("document_id", docRecord.getId())))
//        Metadata 的内容不会被送入 Embedding 模型，因此不消耗模型的 token 额度。
//        segments.replaceAll(textSegment -> TextSegment.from(
//                "[来源:" + fileName + "]\n" + textSegment.text(),
//                new Metadata()
//                        .put("visibility", "public")));

        List<Embedding> allEmbeddings = new ArrayList<>();
        // 记录嵌入成功的文本段，保证与 allEmbeddings 一一对应，避免失败时错位
        List<TextSegment> successSegments = new ArrayList<>();
        // 一次 http 请求 10 条，避免反复建立连接增大开销
        int batchSize = 10;
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            try {
                List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
                allEmbeddings.addAll(embeddings);
                successSegments.addAll(batch);
                log.info("  Embedded batch {}-{}/{}", i, end, segments.size());
            } catch (Exception e) {
                log.warn("  Batch {}-{} failed, trying one-by-one", i, end);
                for (TextSegment seg : batch) {
                    try {
                        allEmbeddings.add(embeddingModel.embed(seg.text()).content());
                        successSegments.add(seg);
                    } catch (Exception e2) {
                        log.warn("  Skipping chunk: {}", seg.text().substring(0, Math.min(50, seg.text().length())));
                    }
                }
            }
        }

        // 向量 ID 后面方便删除
        List<String> vectorIds = embeddingStore.addAll(allEmbeddings, successSegments);

        String docType = "unknown";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            docType = fileName.substring(dotIdx + 1).toLowerCase();
        }

        // 文档入库
        Documents docRecord = new Documents();
        docRecord.setDocumentName(fileName);
        docRecord.setDocumentType(docType);
        docRecord.setSource(source);
        docRecord.setContentHash(contentHash);
        docRecord.setFileSize(fileSize != null ? fileSize : 0L);
        docRecord.setChunkCount(successSegments.size());
        docRecord.setFeishuNodeToken(feishuNodeToken);
        docRecord.setFeishuObjType(feishuObjType);
        docRecord.setFeishuUpdateTime(feishuUpdateTime);
        docRecord.setCreator(creator);
        documentsMapper.insert(docRecord);

        // 向量分片入库
        for (int i = 0; i < successSegments.size(); i++) {
            DocumentChunks chunk = new DocumentChunks();
            chunk.setDocumentId(docRecord.getId());
            chunk.setVectorId(vectorIds.get(i));
            chunk.setChunkIndex(i);
            chunk.setChunkText(successSegments.get(i).text());
            documentChunksMapper.insert(chunk);
        }

        log.info("Ingested {} with {} chunks, documentId={}", fileName, successSegments.size(), docRecord.getId());
        return new DocumentInfo(docRecord.getId(), fileName, successSegments.size());
    }

    /**
     * 解析 Excel
     */
    private Document parseExcel(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet.getPhysicalNumberOfRows() == 0) continue;
                text.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            switch (cell.getCellType()) {
                                case STRING:
                                    text.append(cell.getStringCellValue());
                                    break;
                                case NUMERIC:
                                    if (DateUtil.isCellDateFormatted(cell)) {
                                        text.append(dateFmt.format(cell.getDateCellValue()));
                                    } else {
                                        double val = cell.getNumericCellValue();
                                        if (val == Math.floor(val) && !Double.isInfinite(val)) {
                                            text.append((long) val);
                                        } else {
                                            text.append(val);
                                        }
                                    }
                                    break;
                                case BOOLEAN:
                                    text.append(cell.getBooleanCellValue());
                                    break;
                                case FORMULA:
                                    try {
                                        String formulaVal = cell.getStringCellValue();
                                        text.append(formulaVal);
                                    } catch (Exception e) {
                                        text.append(cell.getCellFormula());
                                    }
                                    break;
                                default:
                                    text.append(" ");
                            }
                        }
                        if (c < row.getLastCellNum() - 1) {
                            text.append(" | ");
                        }
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        String content = text.toString();
        log.info("  Extracted {} chars from Excel", content.length());
        return Document.from(content);
    }

    /**
     * 解析 Word
     */
    private Document parseWord(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                text.append(para.getText()).append("\n");
            }
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText()).append(" | ");
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    /**
     * 解析 PPT
     */
    private Document parsePowerPoint(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(inputStream)) {
            int slideNum = 1;
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
                text.append("=== 幻灯片 ").append(slideNum++).append(" ===\n");
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        text.append(((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    /**
     * 解析 HTML
     */
    private Document parseHtml(InputStream inputStream) throws IOException {
        org.jsoup.nodes.Document html = Jsoup.parse(inputStream, "UTF-8", "");
        html.select("script, style, nav, footer, header").remove();
        return Document.from(html.body().text());
    }

    /**
     * 获取已导入的文档列表
     */
    public List<DocumentInfo> getIngestedDocuments() {
        List<Documents> docs = documentsMapper.selectList(
                Wrappers.<Documents>lambdaQuery()
                        .select(Documents::getId, Documents::getDocumentName, Documents::getChunkCount)
                        .orderByDesc(Documents::getCreateTime)
        );
        List<DocumentInfo> result = new ArrayList<>();
        for (Documents doc : docs) {
            result.add(new DocumentInfo(doc.getId(), doc.getDocumentName(), doc.getChunkCount()));
        }
        return result;
    }

    /**
     * 删除文档
     */
    public void deleteDocument(Long documentId) {
        List<DocumentChunks> chunks = documentChunksMapper.selectList(
                Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getDocumentId, documentId)
        );
        List<String> vectorIds = chunks.stream()
                .map(DocumentChunks::getVectorId)
                .collect(Collectors.toList());
        embeddingStore.removeAll(vectorIds);

        documentChunksMapper.delete(
                Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getDocumentId, documentId)
        );
        documentsMapper.deleteById(documentId);
        log.info("Deleted document id={} with {} chunks", documentId, chunks.size());
    }
}
