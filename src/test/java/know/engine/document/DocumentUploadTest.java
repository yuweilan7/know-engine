package know.engine.document;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import know.engine.document.constant.DocumentStatus;
import know.engine.document.entity.Document;
import know.engine.document.entity.DocumentUploadParam;
import know.engine.document.entity.DocumentVersion;
import know.engine.document.service.DocumentService;
import know.engine.document.service.DocumentVersionService;
import know.engine.document.service.FileProcessService;
import know.engine.document.service.FileProcessServiceFactory;
import know.engine.document.service.FileStorageService;
import know.engine.document.service.impl.DocumentProcessServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentUploadTest {

    @Test
    void uploadReturnsThePersistedConvertedDocument() throws Exception {
        var documents = mock(DocumentService.class);
        var versions = mock(DocumentVersionService.class);
        var storage = mock(FileStorageService.class);
        var factory = mock(FileProcessServiceFactory.class);
        var processor = mock(FileProcessService.class);
        var service = service(documents, versions, storage);
        ReflectionTestUtils.setField(service, "fileProcessServiceFactory", factory);

        var persisted = new Document();
        persisted.setDocId(1L);
        persisted.setCurrentVersionId(2L);
        persisted.setStatus(DocumentStatus.CONVERTED);
        var version = new DocumentVersion();
        version.setVersionId(2L);
        version.setStatus(DocumentStatus.CONVERTED);
        when(documents.getById(1L)).thenReturn(persisted);
        when(documents.updateById(any(Document.class))).thenReturn(true);
        when(versions.save(any(DocumentVersion.class))).thenAnswer(call -> {
            ((DocumentVersion) call.getArgument(0)).setVersionId(2L);
            return true;
        });
        when(versions.getById(2L)).thenReturn(version);
        when(versions.updateById(any(DocumentVersion.class))).thenReturn(true);
        when(storage.uploadFile(any(), anyString())).thenReturn("http://s3/test.md");
        when(factory.get(any())).thenReturn(processor);
        when(processor.processDocument(any(), any(), anyString())).thenReturn("http://s3/converted/test.md");

        var result = service.upload(param(), "learner");

        assertSame(persisted, result);
        assertEquals(DocumentStatus.CONVERTED, result.getStatus());
        assertEquals("http://s3/converted/test.md", version.getConvertedDocUrl());
    }

    @Test
    void originalStorageFailureIsReportedAndItsDocumentIsRemoved() throws Exception {
        var documents = mock(DocumentService.class);
        var versions = mock(DocumentVersionService.class);
        var storage = mock(FileStorageService.class);
        var service = service(documents, versions, storage);
        var failure = new IOException("storage unavailable");
        when(storage.uploadFile(any(), anyString())).thenThrow(failure);

        var thrown = assertThrows(IOException.class, () -> service.upload(param(), "learner"));

        assertSame(failure, thrown.getCause());
        verify(documents).removeDocumentWithSegments(1L);
        verify(versions, never()).save(any(DocumentVersion.class));
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

    private static DocumentProcessServiceImpl service(DocumentService documents, DocumentVersionService versions,
                                                       FileStorageService storage) {
        var service = new DocumentProcessServiceImpl();
        ReflectionTestUtils.setField(service, "documentService", documents);
        ReflectionTestUtils.setField(service, "documentVersionService", versions);
        ReflectionTestUtils.setField(service, "fileStorageService", storage);
        when(documents.save(any(Document.class))).thenAnswer(call -> {
            ((Document) call.getArgument(0)).setDocId(1L);
            return true;
        });
        return service;
    }

    private static DocumentUploadParam param() {
        var file = new MockMultipartFile("file", "test.md", "text/markdown", "# Test".getBytes(StandardCharsets.UTF_8));
        return new DocumentUploadParam(file, "Test", "Test upload", "1.0.0");
    }
}
