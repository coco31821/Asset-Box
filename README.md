# AssetBox

> TA가 제작한 3D 에셋을 공유하고, 필요한 에셋의 요청부터 작업 완료까지 연결하는 협업 플랫폼

- Repository: [Teabag-BE/Asset-Box](https://github.com/Teabag-BE/Asset-Box)
- 기간: 2026.05 ~ 2026.07
- 형태: 팀 프로젝트
- 담당: Backend — Request(에셋 요청 게시판) 도메인

## 프로젝트 소개

AssetBox는 교육 과정에서 제작한 3D 모델을 한곳에 모아 탐색·재사용하고, 원하는 에셋이 없을 때 TA에게 제작을 요청할 수 있도록 만든 플랫폼입니다.

단순 파일 게시판이 아니라 `요청 작성 → TA 배정 → 제작 진행 → 결과 게시글 연결 → 요청 완료`라는 업무 흐름을 서비스 안에서 추적할 수 있도록 구성했습니다. 저는 Request 도메인을 담당해 요청의 생성부터 결과물 연결까지 이어지는 백엔드 흐름을 구현했습니다.

## 주요 기능

- 이메일 및 Google/Naver OAuth2 로그인, JWT 인증과 역할별 접근 제어
- 3D 에셋 게시글 CRUD, 카테고리·태그·검색, 좋아요·조회수
- ZIP 에셋 업로드, 모델·텍스처 추출, S3 Presigned URL 조회·다운로드
- 에셋 요청 CRUD, TA 배정, 상태 전이, 결과 게시글 연결
- 썸네일·다중 참고 이미지 업로드 및 수정 동기화
- 1:1 메시지, 댓글, 사용자 피드백
- Docker Compose, GitHub Actions, GHCR, EC2 기반 배포

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Backend | Java 25, Spring Boot 4, Spring MVC |
| Data | Spring Data JPA, QueryDSL, MySQL, H2 |
| Security | Spring Security, OAuth2 Client, JWT |
| Cache | Redis, Redisson |
| File | Amazon S3, AWS SDK, Presigned URL |
| Test | JUnit 5, Mockito, MockMvc, AssertJ |
| Infra | Docker, GitHub Actions, GHCR, EC2, Nginx Proxy Manager |

## 시스템 구성

```mermaid
flowchart LR
    Client["React Client"] --> Proxy["Nginx Proxy Manager"]
    Proxy --> API["Spring Boot Modular Monolith"]
    API --> MySQL[(MySQL)]
    API --> Redis[(Redis)]
    API --> S3[(Amazon S3)]
    Actions["GitHub Actions"] --> GHCR
    GHCR --> EC2
```

---

## 담당 업무 및 기여

### Request 도메인 구현

- 요청 생성·단건/목록 조회·수정·소프트 삭제 API
- `REQUESTED → IN_PROGRESS → COMPLETED` 상태 전이
- TA 전공 사용자만 요청을 수락하도록 권한 검증
- 요청자의 자기 요청 수락 및 중복 배정 방지
- 결과 에셋 게시글 등록 시 연결된 요청 자동 완료
- 요청 썸네일과 다중 참고 이미지 업로드·조회·수정
- `@Version` 기반 RequestPost 낙관적 락과 충돌 응답
- Controller·Service·Domain 계층의 성공·실패 테스트

## 기술적 문제 해결

### 1. 요청 상태와 담당자 규칙을 코드로 강제

일반 CRUD와 달리 에셋 요청은 요청자와 작업 담당자의 역할, 현재 상태에 따라 허용되는 행위가 달랐습니다. 상태 변경 API만 열어두면 TA가 아닌 사용자의 수락, 자기 요청 수락, 중복 배정, 담당자가 아닌 사용자의 완료 처리가 가능했습니다.

`RequestPost`에 `assign()`과 `complete()` 행위를 표현하고 서비스에서 다음 규칙을 검증했습니다.

- 생성 상태는 항상 `REQUESTED`
- TA 전공 사용자만 수락 가능
- 요청자와 수락자가 같으면 거부
- 이미 배정되었거나 완료된 요청은 재수락 불가
- 배정된 담당자가 결과 게시글을 작성할 때만 완료 가능
- `IN_PROGRESS` 상태에서만 결과 게시글 연결 가능

도메인 규칙을 컨트롤러 밖에 두어 Post 등 다른 도메인에서 호출해도 같은 검증이 적용되도록 했습니다.

### 2. 결과 게시글과 요청의 생명주기를 한 트랜잭션으로 연결

결과 에셋 게시글을 작성한 뒤 요청을 따로 완료하면 한쪽만 성공해 데이터가 불일치할 수 있었습니다. 게시글 생성 입력에 `linkedRequestId`를 추가하고, 게시글 저장 흐름 안에서 요청 완료 서비스를 호출했습니다.

연결 대상 존재 여부, 담당자 일치, 현재 상태, 기존 결과 게시글 연결 여부를 검증한 뒤 게시글과 요청 상태를 함께 변경했습니다. 이로써 사용자가 별도 완료 API를 호출하지 않아도 요청에서 실제 결과물을 추적할 수 있게 했습니다.

### 3. 선택 파일이 없는 multipart 요청 안정화

클라이언트에 따라 선택 파일이 `null`, 빈 목록, 크기 0의 빈 `MultipartFile`로 전달됐습니다. 실제 파일이 없는데 업로드와 Presigned URL 생성을 시도하며 요청 작성이 실패하는 문제를 `hasFile()`과 `nonEmptyFiles()` 판별 로직으로 해결했습니다.

첨부 없음, 참고 이미지만 있음, 빈 파일 파트 전달의 세 가지 회귀 시나리오를 테스트해 클라이언트별 multipart 차이를 방어했습니다.

### 4. 기존·신규·삭제 참고 이미지 동기화

수정 화면에서는 기존 이미지 유지·순서 변경·삭제·신규 추가가 동시에 일어납니다. `ReferenceImageSyncRequest`로 기존 파일 ID와 정렬 순서, 신규 파일의 정렬 순서를 명시하도록 계약을 설계했습니다.

- 현재 파일과 유지 파일을 비교해 삭제 대상 계산
- 중복되거나 다른 요청에 속한 파일 ID 차단
- 기존·신규 이미지의 정렬 값이 `1..N`인지 검증
- 신규 파일 수와 정렬 값 수의 일치 검증
- 썸네일 교체 성공 후 이전 스토리지 객체 정리

Request 도메인은 수정 의도를 계산하고 File 도메인은 실제 저장을 담당하도록 경계를 유지했습니다.

### 5. RequestPost 버저닝으로 동시 수정 유실 방지

#### 문제

요청자와 배정된 TA가 비슷한 시점에 요청 내용이나 상태를 변경하면, 늦게 저장된 요청이 먼저 저장된 변경을 조용히 덮어쓰는 lost update가 발생할 수 있었습니다. 특히 내용 수정과 `IN_PROGRESS`·`COMPLETED` 상태 변경이 충돌하면 화면에는 성공으로 보이지만 실제 업무 상태가 되돌아갈 위험이 있었습니다.

#### 해결

- `RequestPost`에 JPA `@Version` 필드를 추가했습니다.
- 조회 응답과 수정 요청에 `version`을 포함해 클라이언트가 읽은 버전을 전달하도록 했습니다.
- 저장 시 버전이 다르면 `ObjectOptimisticLockingFailureException`을 `REQUEST_VERSION_CONFLICT`로 변환했습니다.
- 충돌 응답은 HTTP `409 Conflict`로 통일하고 최신 요청을 다시 조회하도록 안내했습니다.
- 내용 수정과 상태 전이, 결과 게시글 자동 완료가 경합하는 통합 테스트를 추가했습니다.

#### 결과

| 동시 수정 검증 | 개선 전 | 개선 후 |
| --- | ---: | ---: |
| 같은 버전을 읽은 수정 요청 | 2건 모두 성공 가능 | 1건 성공, 1건 409 충돌 |
| 재현 테스트에서 유실된 변경 | 1건 | 0건 |
| 충돌 감지 위치 | 사후 확인 불가 | DB update 시점 즉시 감지 |

낙관적 락을 사용해 일반 조회에는 별도 잠금을 추가하지 않으면서, 실제 충돌이 발생한 요청만 재조회·재시도하도록 만들었습니다.


## 회고

> 요청 게시판을 단순 CRUD가 아닌 상태·권한·파일·결과 게시글이 연결된 업무 흐름으로 설계하며, 도메인 경계와 트랜잭션 일관성을 코드와 테스트로 구체화했습니다.

## 향후 개선

- 요청 삭제 시 작성자 검증과 상태 정책 보강
- S3 작업과 DB 트랜잭션 사이의 보상 처리 확대
- 참고 이미지 동기화의 롤백·부분 실패 테스트 보강
- 상태 전이 규칙을 전용 정책 객체로 분리
- 충돌 빈도와 재시도 성공률을 운영 메트릭으로 수집
