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
package org.broadleafcommerce.common.payment.service;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * <p>This default implementation encodes the seal using a URL-Safe Base64 encoder
 * and 'HmacSHA1'</p>
 *
 * @author Elbert Bautista (elbertbautista)
 */
@Service("blPaymentGatewayTamperProofSealService")
public class PaymentGatewayTamperProofSealServiceImpl implements PaymentGatewayTamperProofSealService {

    private static final Log LOG = LogFactory.getLog(PaymentGatewayTamperProofSealServiceImpl.class);

    @Value("${aws.secretsmanager.secret.name:}")
    protected String secretName;

    // Lazy Secrets Manager client - built on first use, never in static initializer
    protected SecretsManagerClient secretsManagerClient;

    @Override
    public String createTamperProofSeal(
            String secretKey,
            String customerId,
            String orderId
    ) throws NoSuchAlgorithmException, InvalidKeyException {

        // Resolve secret from AWS Secrets Manager if not provided directly (cr-java-0113)
        String resolvedSecretKey = resolveSecretKey(secretKey);

        //Create a URL-Safe Base64 encoder as some of these may get passed back as URL GET parameters
        Base64 encoder = new Base64(true);
        Mac sha1Mac = Mac.getInstance("HmacSHA1");
        SecretKeySpec publicKeySpec = new SecretKeySpec(resolvedSecretKey.getBytes(), "HmacSHA1");
        sha1Mac.init(publicKeySpec);
        String customerOrderString = customerId + orderId;
        byte[] publicBytes = sha1Mac.doFinal(customerOrderString.getBytes());
        String publicDigest = encoder.encodeToString(publicBytes);

        return publicDigest.replaceAll("\\r|\\n", "");
    }

    @Override
    public Boolean verifySeal(String seal, String secretKey, String customerId, String orderId)
            throws InvalidKeyException, NoSuchAlgorithmException {
        Boolean valid = false;
        String constructedSeal = createTamperProofSeal(secretKey, customerId, orderId);

        if (seal != null && seal.equals(constructedSeal)) {
            valid = true;
        }

        return valid;
    }

    /**
     * Resolves the secret key from AWS Secrets Manager when the provided key is null or empty.
     * Falls back to the provided key if Secrets Manager is not configured.
     */
    protected String resolveSecretKey(String secretKey) throws InvalidKeyException {
        if (secretKey != null && !secretKey.isEmpty()) {
            return secretKey;
        }
        if (secretName == null || secretName.isEmpty()) {
            return System.getenv("BLC_TAMPER_PROOF_SEAL_SECRET");
        }
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = getSecretsManagerClient().getSecretValue(request);
            return response.secretString();
        } catch (SecretsManagerException e) {
            LOG.error("Error retrieving secret from AWS Secrets Manager", e);
            throw new InvalidKeyException("Unable to retrieve secret from AWS Secrets Manager", e);
        }
    }

    protected SecretsManagerClient getSecretsManagerClient() {
        if (secretsManagerClient == null) {
            try {
                secretsManagerClient = SecretsManagerClient.builder()
                        .region(Region.of(System.getenv("AWS_REGION")))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
            } catch (SdkException e) {
                LOG.error("Unable to create Secrets Manager client", e);
                throw new IllegalStateException("Unable to create Secrets Manager client", e);
            }
        }
        return secretsManagerClient;
    }

}