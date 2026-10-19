# 2026-10-09 — 로비 마법 미리보기 프록시

- Date: 2026-10-09
- GitHub Issue: [#62](https://github.com/Apptive-Game-Team/ArcaneCastersLobby/issues/62)
- Status: Implemented; PR preparation

## Goal
인증된 클라이언트가 같은 환경의 게임 서버 카탈로그와 JSON을 받게 한다.

## Non-goals
임의 서버 주소 입력, 요청 시 시뮬레이션, 매칭 로직 변경, 버전 변경.

## Context / Constraints
Kotlin suspend preview API. 관리 중인 서버 ID와 revision을 고정하고 서비스 JWT를 사용한다.

## Approach (Checklist)
- [x] **Step 0: Recon** 기존 동작과 모듈 지침을 확인했다. 공동 구현 계획은 fast/medium/heavy 검토를 거쳤다.
- [x] **Step 1: Implementation** 카탈로그 서버 선택, 다운로드 서버 고정, SHA-256 검증, 타임아웃 및 취소 전파, gzip 처리.
- [x] **Step 2: Tests** Gradle 전체 270개 중 249개 통과, 조건부 제외 21개. 실제 Netty gzip 응답 및 서비스 인증 헤더, 서버 고정·충돌·취소 검증.
- [ ] **Step 3: Rollout / Rollback** 게임 #102 이후, 클라이언트 #266 전에 배포한다. 실패 시 해당 모듈 커밋을 되돌린다.

## Validation
- **Commands to run:** ./gradlew test; git diff --check.
- **Expected output:** Gradle 전체 270개 중 249개 통과, 조건부 제외 21개. 실제 Netty gzip 응답 및 서비스 인증 헤더, 서버 고정·충돌·취소 검증.
- 실제 DB 및 배포 브라우저 전체 연동 검증은 배포 확인 항목이다. Graphify 실행 파일이 없어 루트 그래프 갱신은 실행하지 못했다.

## Risks & Rollback
- **Risks:** 운영 입력에 따라 상황 재생 길이가 달라질 수 있으며 서버 생성/전송 비용과 브라우저 저장 동작은 실제 환경에서 확인한다.
- **Rollback steps:** 게임 #102 이후, 클라이언트 #266 전에 배포한다. 실패 시 해당 모듈 커밋을 되돌린다.

## Open Questions
- 실제 DB 입력과 WebGL 브라우저 재실행/저장 용량 초과 검증.
