# 플레이어 레벨과 성장 상자 추가

Database V121을 먼저 적용하고 lobby, client 순서로 배포한다. Client #393과 Database #132가 함께 필요하다.

## API

인증 토큰의 사용자만 처리한다. 요청 본문으로 사용자, 경험치, 레벨, 보상량을 지정할 수 없다.

| 요청 | 동작 |
|---|---|
| `POST /api/users/mine/progression/sync` | 완료된 매칭 경기 반영, 레벨 보상, 오늘의 상자 지급 |
| `POST /api/users/mine/progression/faction` | `{"faction":"HELL"}` 또는 `{"faction":"NATURE"}` 선택 |
| `GET /api/users/mine/chests` | 기존 목록에 `rewardSlots` 추가. 고정 상자는 0 |
| `POST /api/users/mine/chests/{id}/open` | 현재 레벨을 갱신하고 보유 상자를 한 번 개봉 |

성장 응답: `faction`, `level`, 누적 `xp`, 현재 레벨 진행량 `levelXp`, 다음 레벨 필요량 `nextLevelXp`, `currency`, 이번 호출의 `gainedXp`, `grantedChests`.
50레벨에서는 `levelXp`와 `nextLevelXp`가 0이다. 첫 세력 선택 전에는 `faction`이 null이다.
같은 세력 재요청은 멱등 처리하며 다른 세력 재선택은 409, 잘못된 세력은 400이다.

## 지급 규칙

- 새 계정에 전체 카드를 주던 초기화를 제거한다. 세력 선택 시 5종을 3장씩 주고 15장짜리 시작 덱을 만든다.
- 기존 보유 카드와 선택 덱은 보존한다. 기존 계정도 레벨 1에서 시작하며 과거 경기는 소급하지 않는다.
- `HELL`: fire_shot, ember_spirit_swarm, towerback, rock_golem, magma_explosion.
- `NATURE`: chain_lightning, water_slime_swarm, vine_spirit, tree_golem, water_explosion.
- 매칭 승리 100 XP, 패배 25 XP, 무승부 50 XP, 취소 0 XP. 친선전·연습·PvE는 제외한다.
- Game PR #185와 GameRust PR #21에서 항복은 `SURRENDER`, 패배 확정 시 접속이 끊긴 경우는 `DISCONNECT`로 저장한다. 해당 원인의 패배는 0 XP, 승자는 100 XP다. 복귀 후 일반 패배와 종료 사유가 없는 과거 패배는 25 XP다. DB V121과 함께 적용한다.
- 레벨 1~9에서 다음 레벨까지 100 XP, 10~29는 250 XP, 30~49는 330 XP. 50레벨 누적 12,500 XP.
- 레벨 2~50마다 상자 1개. 일반 레벨 3칸, 5의 배수 레벨 6칸, 레벨 50은 7칸. 합계 49개·178칸.
- 한국 날짜 기준 일일 3칸 상자 1개. 접속하지 않은 날짜는 소급하지 않는다. 보관한 상자는 만료되지 않는다.

| 레벨 | HELL 고정 2장 | NATURE 고정 2장 |
|---:|---|---|
| 5 | rock_mage | wind_spirit |
| 10 | meteor_shower | tornado_strike |
| 15 | mini_rock_swarm | life_tree |
| 20 | magma_spirit | aqua_archer |
| 25 | frenzy_totem | grass_generator |
| 30 | dragon_tower | tide_call |
| 35 | earth_call | overgrowth |
| 40 | wall_golem | storm_rider |
| 45 | evil_ent | vine_world |
| 50 | fire_lord_spirit | sea_serpent |

위 표의 양쪽 마법은 같은 레벨에 상자 풀에 들어간다. 나머지 `purpose=PLAYER` 마법은 레벨 1부터 포함한다.
최신 dev 카탈로그는 PLAYER 74종이며 초기 풀 54종, 전체 수집 기준 222장이다.

## 상자 추첨과 설정

각 칸마다 카드 1장 또는 골드를 추첨한다. 카드 후보는 해금된 PLAYER 마법 중 3장 미만 보유한 종류이며 종류별 확률은 같다.
각 칸 지급 후 후보를 다시 계산한다. 후보가 없으면 무조건 골드다. 카드·골드 중 최소 보장 종류는 없다.
초기 설정은 `ProgressionProperties`에만 정의한다.

| 설정 | 기본값 |
|---|---:|
| `progression.card-probability` | 0.8 |
| `progression.currency-min` | 10 |
| `progression.currency-max` | 30 |

골드는 최소~최대 사이 정수 균등 추첨이며 소비 기능은 없다.
응답은 `MAGIC` 또는 `CURRENCY` 보상이며 골드는 `rewardId=0`, `rewardKey=gold`다.
상자 획득 시 칸 수를 저장하고 개봉 시 레벨·보유 카드를 사용한다. 같은 종류의 결과는 합산해서 반환한다.

경기 반영 원장, 일일/레벨별 상자 발급 키, 사용자별 행 잠금으로 중복 지급을 방지한다.
상자 소비·카드·골드 지급은 하나의 트랜잭션이다. 동일 상자 재개봉은 409다.
성장 경로는 기존 3장 초과 보유량을 줄이지 않는다. 모험·퀘스트의 기존 고정 카드 지급 정책은 별도다.

## 검증

신규 정책·API·PostgreSQL 통합 테스트 12개 통과. 관련 상자·매칭을 포함한 기존 회귀 테스트 51개도 통과했다.
최신 DB 전체 마이그레이션을 격리 PostgreSQL에 적용해 동시 선택·중복 상자·풀 고갈·경기 반영을 검증했다.
전체 테스트의 기존 모험 보상 기대값 불일치 3건은 변경 전 `origin/feature/103`에서도 재현된다.
일일 상자 날짜 변경과 항복·이탈 기록의 0 XP 반영도 검증했다. Unity 프리팹 렌더링 캡처는 Client PR #396에 첨부한다. 운영 배포와 운영 서버에 연결한 플레이 검증은 수행하지 않았다.
