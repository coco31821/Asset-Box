package io.teabag.assetbox.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {
    @Value("${custom.s3.access-key}")
    private String accessKey;

    @Value("${custom.s3.secret-key}")
    private String secretKey;

    @Value("${spring.cloud.aws.region.static}")
    private String region;

    @Value("${custom.s3.endpoint:}")
    private String endpoint;


    @Bean
    public S3Client s3client() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .serviceConfiguration(
                        S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build());
        applyEndpointOverride(builder);
        return builder.build();
    }

    @Bean
    public S3Presigner s3presigner() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds));
        applyEndpointOverride(builder);
        return builder.build();
    }

    private void applyEndpointOverride(S3ClientBuilder builder) {
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
    }

    private void applyEndpointOverride(S3Presigner.Builder builder) {
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
    }
}
