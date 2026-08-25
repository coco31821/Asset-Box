# RequestPost MySQL·LocalStack 통합 테스트

## 목적

H2와 Mockito 호출 검증만으로는 확인할 수 없는 RequestPost 참고 이미지 동기화의 DB 최종 상태와 S3 객체 상태를 MySQL Testcontainers·LocalStack에서 함께 검증한다.

## 검증 시나리오

| 시나리오 | MySQL 검증 | LocalStack S3 검증 |
| --- | --- | --- |
| 기존 3개에서 1개 유지·2개 삭제·2개 신규 추가 | 활성 파일 3개, 순서 `1..3`, 삭제 파일 2건의 retention 예약 | purge 전 기존 3개와 신규 2개, 총 5개 객체 |
| 다른 RequestPost의 `fileId` 전달 | 현재 요청 파일 상태 유지 | 신규 업로드·삭제 없이 기존 객체 2개 유지 |
| S3 upload 예외 주입 | RequestPost와 File 메타데이터 모두 rollback | 실패 전에 객체가 생성되지 않아 객체 0개 |

삭제된 참조 파일은 retention 기간 전까지 S3에 남는다. 따라서 첫 번째 시나리오의 객체 수 5개는 고아 객체가 아니라 의도된 보관 정책의 결과다.

## 실행

Docker Desktop을 실행한 뒤 다음 명령을 사용한다.

```powershell
.\gradlew.bat test --tests "io.teabag.assetbox.request.integration.RequestPostLocalStackIntegrationTest" --no-daemon
```

## 범위와 다음 단계

이 테스트는 MySQL과 LocalStack에서 정상 동기화·소유권 검증·S3 업로드 실패의 DB rollback을 재현한다. S3 업로드 성공 후 DB 실패 시의 보상 처리와 재시도는 Storage Outbox 작업에서 별도로 다룬다.
