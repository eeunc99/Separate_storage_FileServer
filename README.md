# file-transfer-server

TCP 기반의 최소 파일 전송/스토리지 노드입니다. 프런트엔드 서버가 파일명과
경로 힌트를 raw TCP 소켓으로 전달하면, 이 서비스는 설정된 스토리지 루트(들)
에서 해당 파일을 찾아 MD5 체크섬과 함께 스트리밍으로 전송하고, 요청 시
파일을 삭제합니다.

## 기술 스택
- Java 11, Spring Boot 2.7 (웹 레이어 없음 — 순수 TCP 소켓 서버)
- Gradle

## 빌드 & 실행
```bash
./gradlew clean bootJar
java -jar build/libs/file-transfer-server-1.0-RELEASE.jar
```

## 프로토콜

모든 다바이트 정수는 4바이트 빅엔디안이며, 명령 코드는 4자리 ASCII입니다.

| 방향 | 메시지 | 페이로드 |
|---|---|---|
| client → server | `FREQ` | fileNameLen, fileName, pathLen, path |
| server → client | `FACK` | fileSize(8B), fileBytes, md5(16B) |
| server → client | `FNOT` | (파일을 찾지 못함) |
| client → server | `FDEL` | (전송 성공 후 삭제 요청) |
| server → client | `DELOK` / `DELER` | 삭제 결과 |

## 설정
`src/main/resources/application.properties` 참고. 주요 항목:

- `fileserver.storage.path` — 쉼표로 구분된 스토리지 루트 목록, 순서대로 탐색
- `fileserver.delete.enabled` — `false`로 설정 시 실제 삭제 없이 로그만 기록(드라이런)
- `fileserver.thread.pool.size` — 동시 처리 가능한 클라이언트 수

## 참고
`FileTransferService.findFile`의 파일 탐색 로직은 최소 예시입니다. 실제
운영 환경에서는 보통 환경별 디렉토리 규칙(날짜별 버킷팅, 레거시 경로
변환 등)을 그 위에 추가로 얹어 사용합니다 — 해당 확장 지점의
`ponytail:` 주석을 참고하세요.
