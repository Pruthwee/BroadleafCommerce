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
package org.broadleafcommerce.common.file.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.common.file.FileServiceException;
import org.broadleafcommerce.common.file.domain.FileWorkArea;
import org.broadleafcommerce.common.file.service.type.FileApplicationType;
import org.broadleafcommerce.common.extension.ExtensionResultHolder;
import org.broadleafcommerce.common.extension.ExtensionResultStatusType;
import org.broadleafcommerce.common.site.domain.Site;
import org.broadleafcommerce.common.web.BroadleafRequestContext;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Resource;

/**
 * S3-based implementation of FileServiceProvider that replaces local file system operations
 * with Amazon S3 object storage for cloud-native deployments.
 *
 * @author Broadleaf Commerce
 */
@Service("blS3FileServiceProvider")
public class S3FileServiceProvider implements FileServiceProvider {

    private static final Log LOG = LogFactory.getLog(S3FileServiceProvider.class);

    @Value("${asset.server.file.system.path:}")
    protected String fileSystemBaseDirectory;

    @Value("${asset.server.max.generated.file.system.directories:2}")
    protected int maxGeneratedDirectoryDepth;

    @Value("${aws.s3.bucket.name:}")
    protected String bucketName;

    @Resource(name = "blBroadleafFileServiceExtensionManager")
    protected BroadleafFileServiceExtensionManager extensionManager;

    // Lazy S3 client - built on first use, never in static initializer
    private S3Client s3Client;

    protected String baseDirectory;

    @Override
    public File getResource(String url) {
        return getResource(url, FileApplicationType.ALL);
    }

    @Override
    public File getResource(String url, FileApplicationType applicationType) {
        String fileName = buildResourceName(url);
        String baseDirectory = getBaseDirectory(true);
        ExtensionResultHolder<String> holder = new ExtensionResultHolder<>();
        if (extensionManager != null) {
            ExtensionResultStatusType result = extensionManager.getProxy().processPathForSite(
                    baseDirectory, fileName, holder
            );
            if (!ExtensionResultStatusType.NOT_HANDLED.equals(result)) {
                return downloadFromS3(holder.getResult());
            }
        }
        String s3Key = getBaseDirectory(false) + "/" + fileName;
        return downloadFromS3(s3Key);
    }

    @Override
    @Deprecated
    public void addOrUpdateResources(FileWorkArea workArea, List<File> files, boolean removeFilesFromWorkArea) {
        addOrUpdateResourcesForPaths(workArea, files, removeFilesFromWorkArea);
    }

    @Override
    public List<String> addOrUpdateResourcesForPaths(
            FileWorkArea workArea,
            List<File> files,
            boolean removeFilesFromWorkArea
    ) {
        List<String> result = new ArrayList<>();
        for (File srcFile : files) {
            if (!srcFile.getAbsolutePath().startsWith(workArea.getFilePathLocation())) {
                throw new FileServiceException("Attempt to update file " + srcFile.getAbsolutePath() +
                        " that is not in the passed in WorkArea " + workArea.getFilePathLocation());
            }

            String fileName = srcFile.getAbsolutePath().substring(workArea.getFilePathLocation().length());
            String url = FilenameUtils.separatorsToUnix(fileName);
            String resourceName = buildResourceName(url);
            String s3Key = getBaseDirectory(false) + "/" + resourceName;

            try {
                uploadToS3(srcFile, s3Key);
                result.add(fileName);
                if (removeFilesFromWorkArea) {
                    srcFile.delete();
                }
            } catch (Exception e) {
                throw new FileServiceException("Error uploading resource named " + fileName + " to S3", e);
            }
        }
        return result;
    }

    @Override
    public boolean removeResource(String name) {
        String resourceName = buildResourceName(name);
        String s3Key = getBaseDirectory(false) + "/" + resourceName;
        try {
            deleteFromS3(s3Key);
            return true;
        } catch (Exception e) {
            LOG.error("Error removing resource " + s3Key + " from S3", e);
            return false;
        }
    }

    /**
     * Stores the file on S3 by performing an MD5 hash of the passed in fileName
     */
    protected String buildResourceName(String url) {
        String fileHash = null;
        if (!url.startsWith("/")) {
            fileHash = DigestUtils.md5Hex("/" + url);
        } else {
            fileHash = DigestUtils.md5Hex(url);
        }

        String resourceName = "";
        for (int i = 0; i < maxGeneratedDirectoryDepth; i++) {
            if (i == 4) {
                LOG.warn("Property maxGeneratedDirectoryDepth set to high, ignoring values past 4 - value set to" +
                        maxGeneratedDirectoryDepth);
                break;
            }
            resourceName = FilenameUtils.concat(resourceName, fileHash.substring(i * 2, (i + 1) * 2));
        }

        return FilenameUtils.concat(resourceName, FilenameUtils.getName(url));
    }

    protected String getBaseDirectory(boolean skipSite) {
        if (baseDirectory == null) {
            if (StringUtils.isNotBlank(fileSystemBaseDirectory)) {
                baseDirectory = fileSystemBaseDirectory;
            } else {
                baseDirectory = System.getProperty("java.io.tmpdir");
            }
        }
        if (!skipSite) {
            return getSiteDirectory(baseDirectory);
        } else {
            return baseDirectory;
        }
    }

    protected String getSiteDirectory(String baseDirectory) {
        BroadleafRequestContext brc = BroadleafRequestContext.getBroadleafRequestContext();
        if (brc != null) {
            Site site = brc.getSite();
            if (site != null) {
                String siteDirectory = "site-" + site.getId();
                String siteHash = DigestUtils.md5Hex(siteDirectory);
                String sitePath = FilenameUtils.concat(siteHash.substring(0, 2), siteDirectory);
                return FilenameUtils.concat(baseDirectory, sitePath);
            }
        }
        return baseDirectory;
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
                throw new FileServiceException("Unable to create S3 client", e);
            }
        }
        return s3Client;
    }

    protected File downloadFromS3(String s3Key) {
        try {
            String bucket = getBucketName();
            if (bucket == null || bucket.isEmpty()) {
                throw new FileServiceException("S3 bucket name is not configured");
            }

            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build();

            ResponseBytes<software.amazon.awssdk.services.s3.model.GetObjectResponse> response =
                    getS3Client().getObjectAsBytes(getRequest);

            Path tempFile = Files.createTempFile("blc-s3-", ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                fos.write(response.asByteArray());
            }
            return tempFile.toFile();
        } catch (S3Exception e) {
            if (e instanceof NoSuchKeyException) {
                return null;
            }
            LOG.error("Error downloading from S3: " + s3Key, e);
            throw new FileServiceException("Error downloading from S3", e);
        } catch (IOException e) {
            LOG.error("Error writing temp file for S3 download: " + s3Key, e);
            throw new FileServiceException("Error writing temp file for S3 download", e);
        }
    }

    protected void uploadToS3(File file, String s3Key) {
        try {
            String bucket = getBucketName();
            if (bucket == null || bucket.isEmpty()) {
                throw new FileServiceException("S3 bucket name is not configured");
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .contentType("application/octet-stream")
                    .build();

            getS3Client().putObject(putRequest, RequestBody.fromFile(file.toPath()));
        } catch (S3Exception e) {
            LOG.error("Error uploading to S3: " + s3Key, e);
            throw new FileServiceException("Error uploading to S3", e);
        }
    }

    protected void deleteFromS3(String s3Key) {
        try {
            String bucket = getBucketName();
            if (bucket == null || bucket.isEmpty()) {
                throw new FileServiceException("S3 bucket name is not configured");
            }

            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build();

            getS3Client().deleteObject(deleteRequest);
        } catch (S3Exception e) {
            LOG.error("Error deleting from S3: " + s3Key, e);
            throw new FileServiceException("Error deleting from S3", e);
        }
    }

    protected boolean existsInS3(String s3Key) {
        try {
            String bucket = getBucketName();
            if (bucket == null || bucket.isEmpty()) {
                return false;
            }

            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build();

            getS3Client().headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            LOG.error("Error checking existence in S3: " + s3Key, e);
            return false;
        }
    }

    protected String getBucketName() {
        if (StringUtils.isNotBlank(bucketName)) {
            return bucketName;
        }
        return System.getenv("S3_BUCKET_NAME");
    }

    protected List<String> listObjectsFromS3(String prefix) {
        try {
            String bucket = getBucketName();
            if (bucket == null || bucket.isEmpty()) {
                return new ArrayList<>();
            }

            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response response = getS3Client().listObjectsV2(listRequest);

            List<String> result = new ArrayList<>();
            for (S3Object obj : response.contents()) {
                result.add(obj.key());
            }
            return result;
        } catch (S3Exception e) {
            LOG.error("Error listing objects from S3 with prefix: " + prefix, e);
            return new ArrayList<>();
        }
    }
}
