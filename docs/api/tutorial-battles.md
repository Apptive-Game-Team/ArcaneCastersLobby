# 튜토리얼 전투 연결

Database V121과 Lobby #106의 세력·시작 카드 API 위에 Game #186의 튜토리얼
연결을 추가한다. Client #397은 컷신 뒤 이 경로를 사용한다.

`POST /api/tutorial/battles`는 인증 사용자와 `{ "requestId": "UUID" }`를 받는다.
사용자와 세력은 요청 본문으로 받지 않는다. ProgressionRepository에서 저장된
HELL/NATURE를 확인하고, 선택 전에는 409를 반환한다.

게임 서버의 서버 인증 전용 `POST /api/server/tutorial/battles`에
`{ownerId,faction,requestId}`를 전달한다. 응답은 다음 형태다.

```json
{
  "battleId": "tutorial-example",
  "ownerId": 7,
  "faction": "HELL",
  "server": "http://localhost:7001",
  "expiresAt": "2030-01-01T00:00:00Z"
}
```

사용자별 서버 affinity를 20분 유지하며 생성 응답 유실 후에도 같은 서버로
재요청한다. requestId를 바꾸거나 가용 서버 목록이 바뀌어도 기존 affinity를
사용한다. requestId마다 route를 만들면 한 사용자가 서버별로 전투를 만들고
route 상한까지 채울 수 있으므로 owner를 키로 사용한다. 기본 상한은 4,096명이다.

일반 매칭 티켓, 사용자 상태, 덱 조회, SessionRecoveryStore를 사용하지 않는다.
경험치·보상 지급은 이 연결 API가 처리하지 않는다. 이미 선택한 세력과 시작 덱은
기존 progression API의 멱등 지급 계약을 그대로 따른다.

클라이언트는 최초 생성 전에 requestId를 로컬 저장하고, 응답 후 battleId·server·
expiresAt를 함께 보관한다. 아는 주소는 game API로 직접 복귀하고, 응답 유실 시
같은 requestId로 재요청한다. 404/410·명시적 만료 뒤에만 새 생성 키를 사용한다.

`TutorialServiceTest`는 저장된 세력 조회, 미선택 계정 차단, 사용자·세력이 다른
ready 응답 거부, 응답 실패 뒤 서버 목록·requestId가 바뀌어도 같은 서버로
보내는 동작을 검증한다.
