# RequestPost 참고 이미지 S3 호출 수 검증

## 목적

RequestPost 참고 이미지 수정은 기존 파일을 다시 업로드하지 않고, `fileId`를 기준으로 유지·재정렬·삭제·신규 추가를 구분한다. 이 문서는 해당 계약을 단위 테스트로 고정한 fixture와 호출 수를 기록한다.

## 고정 fixture

- 기존 `REQUEST_REFERENCE` PNG 10개, 파일당 1 KiB
- 동일한 RequestPost(`purposeId = 1`)에 연결
- S3 storage service는 Mockito mock으로 대체

## 검증 결과

| 시나리오 | 신규 multipart | 유지 / 삭제 | S3 PUT | 즉시 S3 DELETE | DB 파일 상태 |
| --- | ---: | --- | ---: | ---: | --- |
| 10개 재정렬 | 0개 | 10개 유지 | 0회 | 0회 | `uploadOrder`만 변경 |
| 10개 중 2개 교체 | 2개 | 8개 유지 / 2개 삭제 | 2회 | 0회 | 삭제 2건에 `deletedAt`, `purgeAt` 설정 |
| RequestPost 참조 파일 삭제 | 0개 | 전체 삭제 | 0회 | 0회 | 각 파일에 retention 예약 |

이 수치는 Mockito 호출 검증 결과이며, 실제 AWS S3의 응답 시간이나 비용 측정값이 아니다. LocalStack 또는 실제 S3 smoke test 결과와 혼용하지 않는다.

## 실행

```powershell
.\gradlew.bat test --tests "io.teabag.assetbox.file.service.FileServiceTest" --no-daemon
```
