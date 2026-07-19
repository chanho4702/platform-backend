# platform-backend

ALM·Wiki 플랫폼의 횡단 서비스 멀티모듈 repo.

## 모듈

| 모듈 | 역할 | 포트 |
|---|---|---|
| common-proto | gRPC 계약(platform.org.v1) — GitHub Packages 발행 (`com.platform:common-proto`) | — |
| org-service | 조직·팀·RBAC. REST(게이트웨이 경유 /api/org/**) + gRPC PermissionService | 9130 / 9131 |

## 빌드·실행

```powershell
.\gradlew.bat build                  # 전 모듈 빌드+테스트
.\gradlew.bat :org-service:bootRun   # dev 모드 (Eureka 등록)
```

배포판은 infra/keycloak compose가 담당 — docker 프로필(Eureka 미등록, DNS 직결).

## proto 발행

`v*` 태그 push 시 CI가 태그 버전으로 GitHub Packages 발행.
소비: `implementation 'com.platform:common-proto:<버전>'` + GitHub Packages 저장소 인증.

## 권한 모델

grant_entry 단일 원장 — subject(USER|TEAM) × resource(GLOBAL|SPACE|PROJECT) × role(VIEWER<EDITOR<ADMIN).
판정: 직접+팀 grant 병합 최고 role, GLOBAL은 전 리소스 적용. 최초 관리자는 PLATFORM_BOOTSTRAP_ADMIN_ID 시드.
