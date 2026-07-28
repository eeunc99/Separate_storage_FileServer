# file-transfer-server

A minimal TCP file-transfer/storage node. A front-end server sends a
file name + path hint over a raw TCP socket; this service locates the
file across one or more configured storage roots, streams it back with
an MD5 checksum, and optionally deletes it on request.

## Stack
- Java 11, Spring Boot 2.7 (no web layer — pure TCP socket server)
- Gradle

## Build & Run
```bash
./gradlew clean bootJar
java -jar build/libs/file-transfer-server-1.0-RELEASE.jar
```

## Protocol

All multi-byte integers are 4-byte big-endian; command codes are 4 ASCII bytes.

| Direction | Message | Payload |
|---|---|---|
| client → server | `FREQ` | fileNameLen, fileName, pathLen, path |
| server → client | `FACK` | fileSize(8B), fileBytes, md5(16B) |
| server → client | `FNOT` | (file not found) |
| client → server | `FDEL` | (request delete after successful transfer) |
| server → client | `DELOK` / `DELER` | delete result |

## Configuration
See `src/main/resources/application.properties`. Key settings:

- `fileserver.storage.path` — comma-separated storage roots, searched in order
- `fileserver.delete.enabled` — set `false` to dry-run deletes (log only)
- `fileserver.thread.pool.size` — concurrent client cap

## Notes
The file-lookup logic in `FileTransferService.findFile` is a minimal
example; real deployments typically layer environment-specific directory
conventions (date bucketing, legacy path rewriting, etc.) on top — see the
`ponytail:` comment at that extension point.
