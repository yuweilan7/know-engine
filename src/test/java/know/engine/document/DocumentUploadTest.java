package know.engine.document;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import know.engine.document.constant.DocumentStatus;
import know.engine.document.constant.IndexStatus;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentUploadParam;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.mapper.DocumentMapper;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.impl.DocumentProcessServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentUploadTest {
    @Test
    void uploadReturnsDraftVersionAndDoesNotPublish() throws Exception {
        var documents = mock(DocumentMapper.class);
        var versions = mock(DocumentVersionService.class);
        var storage = mock(FileStorageService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var service = new DocumentProcessServiceImpl(documents, versions, null, storage, null, null,
                new TransactionTemplate(manager), new com.fasterxml.jackson.databind.ObjectMapper());

        var doc = new Document();
        doc.setDocId(1L);
        when(documents.insert(any(Document.class))).thenAnswer(call -> {
            ((Document) call.getArgument(0)).setDocId(1L);
            return 1;
        });
        when(documents.lockLive(1L)).thenReturn(doc);
        var storedVersion = new AtomicReference<DocumentVersion>();
        when(versions.save(any(DocumentVersion.class))).thenAnswer(call -> {
            DocumentVersion v = call.getArgument(0);
            v.setVersionId(2L);
            storedVersion.set(v);
            return true;
        });
        when(versions.getById(2L)).thenAnswer(call -> storedVersion.get());
        when(versions.updateById(any(DocumentVersion.class))).thenReturn(true);

        var file = new MockMultipartFile("file", "test.md", "text/markdown",
                "# Test".getBytes(StandardCharsets.UTF_8));
        var result = service.upload(new DocumentUploadParam(file, "Test", "", "1.0.0"), "learner");

        assertEquals(2L, result.getVersionId());
        assertEquals(DocumentStatus.UPLOADED, result.getBuildStatus());
        assertEquals(IndexStatus.NONE, result.getIndexStatus());
        assertEquals("documents/1/versions/2/source/test.md", result.getSourceObjectKey());
        verify(storage).uploadFile(file, result.getSourceObjectKey());
        verify(documents, never()).publish(anyLong(), anyLong());
    }

    @Test
    void missingMultipartContentTypeUsesOctetStream() throws Exception {
        var minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        var storage = new FileStorageService();
        ReflectionTestUtils.setField(storage, "minioClient", minio);
        ReflectionTestUtils.setField(storage, "bucketName", "knowledge");
        ReflectionTestUtils.setField(storage, "endpoint", "http://s3");
        var file = new MockMultipartFile("file", "test.txt", null, "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals("http://s3/knowledge/test.txt", storage.uploadFile(file, "test.txt"));

        var args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(args.capture());
        assertEquals("application/octet-stream", args.getValue().contentType());
    }
}
