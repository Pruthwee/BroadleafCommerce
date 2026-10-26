/*-
 * #%L
 * BroadleafCommerce Common Libraries
 * %%
 * Copyright (C) 2009 - 2026 Broadleaf Commerce
 * %%
 * Licensed under the Broadleaf Fair Use License Agreement, Version 1.0
 * (the "Fair Use License" located  at http://license.broadleafcommerce.org/fair_use_license-1.0.txt)
 * unless the restrictions on use therein are violated and require payment to Broadleaf in which case
 * the Broadleaf End User License Agreement (EULA), Version 1.1
 * (the "Commercial License" located at http://license.broadleafcommerce.org/commercial_license-1.1.txt)
 * shall apply.
 * 
 * Alternatively, the Commercial License may be replaced with a mutually agreed upon license (the "Custom License")
 * between you and Broadleaf Commerce. You may not use this file except in compliance with the applicable license.
 * #L%
 */
package org.broadleafcommerce.common.io;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Component("blConcurrentFileOutputStream")
public class ConcurrentFileOutputStreamImpl implements ConcurrentFileOutputStream {
    private static final Log LOG = LogFactory.getLog(ConcurrentFileOutputStreamImpl.class);
    private static final Map<String, Object> FILE_MOVE_LOCKS = new WeakHashMap<>();

    @Value("${asset.server.file.buffer.size:8192}")
    protected int defaultFileBufferSize;

    @Value("${aws.s3.bucket.name:}")
    protected String s3BucketName;

    // Lazy S3 client - built on first use, never in static initializer
    protected S3Client s3Client;

    @Override
    public int write(InputStream src, File dest) throws IOException {
        return write(src, dest, defaultFileBufferSize);
    }

    public int write(InputStream src, File dest, int bufferSize) throws IOException {
        // Upload to S3 if configured
        if (org.apache.commons.lang3.StringUtils.isNotBlank(s3BucketName)) {
            return writeToS3(src, dest, bufferSize);
        }
        File tempFile = createTempFile(dest);
        int totalWrote = writeToTempFile(src, tempFile, bufferSize);
        replaceExisting(tempFile, dest);
        return totalWrote;
    }

    protected int writeToS3(InputStream src, File dest, int bufferSize) throws IOException {
        String s3Key = dest.getAbsolutePath();
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("application/octet-stream")
                    .build();

            // Read the stream into a byte array for S3 upload
            byte[] bytes = src.readAllBytes();
            getS3Client().putObject(putRequest, RequestBody.fromBytes(bytes));
            return bytes.length;
        } catch (S3Exception e) {
            LOG.error("Error uploading to S3: " + s3Key, e);
            throw new IOException("Error uploading to S3", e);
        }
    }

    protected File createTempFile(File dest) throws IOException {
        File tempFile = new File(dest.getAbsolutePath() + getTempFileSuffix());
        if (!tempFile.exists()) {
            tempFile.createNewFile();
        }
        return tempFile;
    }

    protected String getTempFileSuffix() {
        return ".temp-" + UUID.randomUUID();
    }

    protected int writeToTempFile(InputStream src, File tempFile, int bufferSize) throws IOException {
        FileOutputStream fos = new FileOutputStream(tempFile);

        int totalWrote = 0;

        int readBytes;
        byte[] bytes = new byte[bufferSize];
        while ((readBytes = src.read(bytes)) != -1) {
            fos.write(bytes, 0, readBytes);
            totalWrote += readBytes;
        }

        fos.close();

        return totalWrote;
    }

    protected void replaceExisting(File src, File dest) throws IOException {
        synchronized (getFileMoveLock(dest)) {
            if (src.exists()) {
                try {
                    Files.move(src.toPath(), dest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(src.toPath(), dest.toPath(), REPLACE_EXISTING);
                }
            }
        }
    }

    protected Object getFileMoveLock(File file) {
        String filePath = file.getAbsolutePath();
        synchronized (FILE_MOVE_LOCKS) {
            if (!FILE_MOVE_LOCKS.containsKey(filePath)) {
                FILE_MOVE_LOCKS.put(filePath, new Object());
            }
            return FILE_MOVE_LOCKS.get(filePath);
        }
    }

    protected S3Client getS3Client() {
        if (s3Client == null) {
            try {
                s3Client = S3Client.builder()
                        .region(Region.of(System.getenv("AWS_REGION")))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
            } catch (SdkException e) {
                LOG.error("Unable to create S3 client", e);
            }
        }
        return s3Client;
    }

}
