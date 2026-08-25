package io.teabag.assetbox.request.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.teabag.assetbox.common.exception.BusinessException;
import io.teabag.assetbox.file.domain.File;
import io.teabag.assetbox.file.domain.FilePurpose;
import io.teabag.assetbox.file.repository.FileRepository;
import io.teabag.assetbox.request.dto.ReferenceImageSyncRequest;
import io.teabag.assetbox.request.dto.RequestCreateRequest;
import io.teabag.assetbox.request.dto.RequestResponse;
import io.teabag.assetbox.request.repository.RequestPostRepository;
import io.teabag.assetbox.request.service.RequestPostService;
import io.teabag.assetbox.user.constants.Major;
import io.teabag.assetbox.user.domain.CurrentUser;
import io.teabag.assetbox.user.domain.User;
import io.teabag.assetbox.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RequestPost MySQL·LocalStack 통합 테스트")
class RequestPostLocalStackIntegrationTest {

    private static final String BUCKET = "requestpost-integration-test";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
        .withDatabaseName("assetbox")
        .withUsername("assetbox")
        .withPassword("assetbox");

    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.8")
    ).withServices(LocalStackContainer.Service.S3);

    static {
        MYSQL.start();
        LOCALSTACK.start();
    }

    @Autowired
    private RequestPostService requestPostService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private RequestPostRepository requestPostRepository;

    @MockitoSpyBean
    private S3Client s3Client;

    @MockitoBean
    private RedissonClient redissonClient;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("custom.s3.bucket-name", () -> BUCKET);
        registry.add("custom.s3.access-key", LOCALSTACK::getAccessKey);
        registry.add("custom.s3.secret-key", LOCALSTACK::getSecretKey);
        registry.add("custom.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
    }

    @org.junit.jupiter.api.BeforeAll
    void createBucket() {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @BeforeEach
    void resetState() {
        fileRepository.deleteAll();
        requestPostRepository.deleteAll();
        userRepository.deleteAll();
        listObjectKeys().forEach(key -> s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(BUCKET).key(key).build()
        ));
    }

    @Test
    @DisplayName("참조 이미지 동기화는 MySQL 최종 상태와 LocalStack 객체 수를 함께 보장한다")
    void updateSynchronizesReferenceImagesAcrossDatabaseAndStorage() {
        // given: 기존 3개 중 1개는 유지·재정렬하고 2개는 삭제한 뒤 신규 2개를 추가한다.
        User user = userRepository.save(createUser("sync-user"));
        CurrentUser currentUser = CurrentUser.from(user);
        RequestResponse created = requestPostService.save(
            currentUser,
            createRequest("초기 요청"),
            null,
            List.of(image("first.png"), image("second.png"), image("third.png"))
        );
        Long requestId = created.id();
        Long keptFileId = created.referenceImages().get(2).fileId();

        // when
        requestPostService.update(
            requestId,
            currentUser,
            createRequest("수정 요청"),
            null,
            List.of(image("replacement-one.png"), image("replacement-two.png")),
            new ReferenceImageSyncRequest(
                List.of(new ReferenceImageSyncRequest.ExistingImage(keptFileId, 1L)),
                List.of(2L, 3L)
            )
        );

        // then: 현재 참조 파일은 3개이고 순서는 연속적이다.
        List<File> activeFiles = fileRepository
            .findByPurposeAndPurposeIdAndDeletedAtIsNullOrderByUploadOrderAsc(FilePurpose.REQUEST_REFERENCE, requestId);
        assertThat(activeFiles)
            .hasSize(3)
            .extracting(File::getUploadOrder)
            .containsExactly(1L, 2L, 3L);
        assertThat(activeFiles.getFirst().getId()).isEqualTo(keptFileId);

        // 삭제된 기존 2개는 S3 즉시 삭제 대신 retention 대상으로 남는다.
        List<File> deletedFiles = fileRepository.findAll().stream()
            .filter(file -> file.getPurpose() == FilePurpose.REQUEST_REFERENCE)
            .filter(file -> file.getPurposeId().equals(requestId))
            .filter(file -> file.getDeletedAt() != null)
            .toList();
        assertThat(deletedFiles)
            .hasSize(2)
            .allSatisfy(file -> assertThat(file.getPurgeAt()).isAfter(file.getDeletedAt()));

        // LocalStack에는 초기 3개와 신규 2개가 남아 있어 purge 전 객체 수를 재현한다.
        assertThat(listObjectKeys()).hasSize(5);
    }

    @Test
    @DisplayName("다른 RequestPost의 fileId는 동기화 요청에 사용할 수 없다")
    void updateRejectsReferenceFileOwnedByAnotherRequestPost() {
        // given
        User user = userRepository.save(createUser("ownership-user"));
        CurrentUser currentUser = CurrentUser.from(user);
        RequestResponse first = requestPostService.save(
            currentUser, createRequest("첫 번째 요청"), null, List.of(image("first.png"))
        );
        RequestResponse second = requestPostService.save(
            currentUser, createRequest("두 번째 요청"), null, List.of(image("second.png"))
        );
        Long foreignFileId = second.referenceImages().getFirst().fileId();

        // when & then
        assertThatThrownBy(() -> requestPostService.update(
            first.id(),
            currentUser,
            createRequest("변조 요청"),
            null,
            List.of(),
            new ReferenceImageSyncRequest(
                List.of(new ReferenceImageSyncRequest.ExistingImage(foreignFileId, 1L)),
                List.of()
            )
        )).isInstanceOf(BusinessException.class);

        assertThat(fileRepository.findByPurposeAndPurposeIdAndDeletedAtIsNullOrderByUploadOrderAsc(
            FilePurpose.REQUEST_REFERENCE,
            first.id()
        )).hasSize(1);
        assertThat(listObjectKeys()).hasSize(2);
    }

    @Test
    @DisplayName("S3 업로드 실패 시 RequestPost와 파일 메타데이터를 MySQL에 남기지 않는다")
    void saveRollsBackDatabaseWhenS3UploadFails() {
        // given
        User user = userRepository.save(createUser("rollback-user"));
        doThrow(SdkClientException.builder().message("injected S3 failure").build())
            .when(s3Client)
            .putObject(any(PutObjectRequest.class), any(RequestBody.class));

        // when & then
        assertThatThrownBy(() -> requestPostService.save(
            CurrentUser.from(user),
            createRequest("업로드 실패 요청"),
            null,
            List.of(image("will-fail.png"))
        )).isInstanceOf(BusinessException.class);

        assertThat(requestPostRepository.count()).isZero();
        assertThat(fileRepository.count()).isZero();
        assertThat(listObjectKeys()).isEmpty();
    }

    private List<String> listObjectKeys() {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
            .contents()
            .stream()
            .map(object -> object.key())
            .toList();
    }

    private RequestCreateRequest createRequest(String title) {
        return new RequestCreateRequest(
            title,
            "RequestPost LocalStack integration test",
            "CHARACTER",
            "LOW_POLY",
            "UNITY",
            LocalDateTime.now().plusDays(1),
            null
        );
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("referenceImages", name, "image/png", name.getBytes());
    }

    private User createUser(String nickname) {
        return User.builder()
            .email(nickname + "@assetbox.test")
            .password("password")
            .name("통합테스트 사용자")
            .nickname(nickname)
            .major(Major.BACK_END)
            .build();
    }
}
