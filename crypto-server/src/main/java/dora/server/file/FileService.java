package dora.server.file;

import dora.crypto.shared.dto.FileInfo;
import dora.crypto.shared.dto.FileUploadResponse;
import dora.server.auth.User;
import dora.server.auth.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileService {
    private final FileRepository fileRepository;
    private final UserService userService;

    @Value("${file.storage.directory:./uploads}")
    private String storageDirectory;

    @Transactional
    public FileUploadResponse uploadFile(MultipartFile file) throws IOException {
        User currentUser = userService.getCurrentUser();

        // Generate unique file ID
        String fileId = UUID.randomUUID().toString();

        // Ensure storage directory exists
        Path storagePath = Paths.get(storageDirectory);
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
        }

        // Save file to disk
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "file_" + fileId;
        }

        Path filePath = storagePath.resolve(fileId);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        // Save file metadata to database
        FileEntity fileEntity = FileEntity.builder()
                .fileId(fileId)
                .fileName(fileName)
                .fileSize(file.getSize())
                .filePath(filePath.toString())
                .uploadedBy(currentUser)
                .uploadedAt(Instant.now())
                .build();

        fileRepository.save(fileEntity);

        // Build response
        return FileUploadResponse.builder()
                .fileId(fileId)
                .fileName(fileName)
                .fileSize(file.getSize())
                .downloadUrl("/files/" + fileId)
                .build();
    }

    public Resource downloadFile(String fileId) throws IOException {
        FileEntity fileEntity = fileRepository.findByFileId(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        Path filePath = Paths.get(fileEntity.getFilePath());
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("File not found or not readable: " + fileId);
        }

        return resource;
    }

    public FileInfo getFileInfo(String fileId) {
        FileEntity fileEntity = fileRepository.findByFileId(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        return FileInfo.builder()
                .fileId(fileEntity.getFileId())
                .fileName(fileEntity.getFileName())
                .fileSize(fileEntity.getFileSize())
                .uploadedBy(fileEntity.getUploadedBy().getUsername())
                .uploadedAt(fileEntity.getUploadedAt().toEpochMilli())
                .build();
    }

    @Transactional
    public void deleteFile(String fileId) throws IOException {
        FileEntity fileEntity = fileRepository.findByFileId(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        // Delete file from disk
        Path filePath = Paths.get(fileEntity.getFilePath());
        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }

        // Delete from database
        fileRepository.delete(fileEntity);
    }
}

