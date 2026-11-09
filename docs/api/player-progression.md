# 플레이어 레벨과 성장 상자 추가

기본 성장 기능은 Database V121을 사용한다. 수동 수령 기능은 V124를 먼저 적용하고 lobby, client 순서로 배포한다. 모든 lobby 인스턴스가 새 원장을 지원하고 새 클라이언트를 검증한 뒤 `rewards.manual-claims-enabled=true`로 활성화한다. 기본값은 false다.

## API

인증 토큰의 사용자만 처리한다. 요청 본문으로 사용자, 경험치, 레벨, 보상량을 지정할 수 없다.

| 요청 | 동작 |
|---|---|
| `POST /api/users/mine/progression/sync` | 완료된 경기의 XP·레벨 갱신과 레벨·일일 보상 발급. 수동 모드는 미수령으로 보관 |
| `POST /api/users/mine/progression/faction` | `{"faction":"HELL"}` 또는 `{"faction":"NATURE"}` 선택 |
| `GET /api/users/mine/progression/rewards/pending` | 미수령 성장 보상과 과거 자동 지급 기준선, 수령 완료 레벨 조회 |
| `POST /api/users/mine/progression/rewards/{id}/claim` | 본인의 성장 보상 수령. 다른 사용자의 ID는 404, 재시도는 같은 영수증을 200으로 반환 |
| `GET /api/users/mine/chests` | 기존 목록에 `rewardSlots` 추가. 고정 상자는 0 |
| `POST /api/users/mine/chests/{id}/open` | 현재 레벨을 갱신하고 보유 상자를 한 번 개봉 |

성장 응답: `faction`, `level`, 누적 `xp`, 현재 레벨 진행량 `levelXp`, 다음 레벨 필요량 `nextLevelXp`, `currency`, 이번 호출의 `gainedXp`, `grantedChests`.
50레벨에서는 `levelXp`와 `nextLevelXp`가 0이다. 첫 세력 선택 전에는 `faction`이 null이다.
같은 세력 재요청은 멱등 처리하며 다른 세력 재선택은 409, 잘못된 세력은 400이다.

## 수동 수령과 구버전 호환

기능 설정이 true이고 요청에 `X-Reward-Claims: manual-v1`이 있을 때 수동 수령을 적용한다. 성장 sync·세력 선택·미수령 조회/수령·퀘스트 목록/check/claim·상자 개봉에 같은 헤더를 사용한다. 상자 개봉의 내부 성장 sync도 이 모드를 유지한다.

XP와 레벨, 세력 선택의 시작 카드와 덱은 즉시 반영한다. 이후 레벨의 고정 마법과 상자, 접속한 오늘의 일일 상자는 미수령 원장에 예정 수량·마법 ID·상자 ID·칸 수를 저장한다. 접속하지 않은 날짜는 소급하지 않는다. 수령을 미룬 보상은 보존한다.

미수령 응답은 `{ enabled, baselineLevel, claimedLevels, rewards }`다. `rewards`의 각 그룹은 `{ id, source, level, createdAt, rewards }`이고, 일일 보상의 `level`은 null이다. 내부 보상은 `{ rewardType, rewardId, rewardKey, amount, rewardSlots }`다. `rewardSlots`는 마법이면 0이다. `baselineLevel` 이하는 이미 자동 지급한 레벨이며 다시 발급하지 않는다. `claimedLevels`는 그 이후 수령 완료와 기존 source 소유 레벨을 포함한다.

수령 응답은 `{ id, alreadyClaimed, rewards }`다. 마법의 `amount`는 실제 재고 증가량이므로 0일 수 있다. 2장 보유 상태에서 2장 보상을 받으면 영수증은 1장이며, 3장 이상 보유하면 0장이다. 기존 3장 초과 보유량은 보존한다. 같은 source의 상자를 이미 소유했다면 해당 그룹을 재지급하지 않고 0장 영수증으로 완료한다. 사용자 성장 행, 보상 행 순서로 잠그고 지급·영수증·수령 상태를 한 트랜잭션으로 저장한다.

설정이 false이거나 헤더가 없으면 기존 자동 지급 호환 경로를 사용한다. 남아 있는 미수령 성장 보상도 같은 원자적 수령 경로로 정산한다. 수동·구버전 요청이 동시에 들어와도 사용자/source 유일성과 사용자별 잠금으로 한 번만 지급한다. 롤백은 새 lobby를 유지한 채 설정을 false로 바꾼다. 미수령 원장을 모르는 과거 바이너리로 되돌리거나 원장·영수증을 삭제하지 않는다.

퀘스트는 기존 목록·수령 API와 `user_quests`를 유지한다. 수동 모드의 check는 MAGIC/CARD/CHEST를 포함한 퀘스트의 자동 지급을 보류하고 목록에는 유효 `claimMode=MANUAL`을 반환한다. 기존 COMPLETED 상태와 명시 수령의 409 응답은 유지한다. 클라이언트는 quest 409에서 목록과 재고를 다시 조회하며 획득 연출을 반복하지 않는다. 퀘스트 보상을 성장 원장으로 복제하지 않는다.

## 지급 규칙

- 새 계정에 전체 카드를 주던 초기화를 제거한다. 세력 선택 시 5종을 3장씩 주고 15장짜리 시작 덱을 만든다.
- 기존 보유 카드와 선택 덱은 보존한다. 기존 계정도 레벨 1에서 시작하며 과거 경기는 소급하지 않는다.
- `HELL`: fire_shot, rock_golem, rock_mage, wall_golem, stone_gargoyle.
- `NATURE`: chain_lightning, electric_spearman, aqua_archer, tree_golem, thunder_spirit.
- 매칭과 봇 연습 경기의 승리 100 XP, 패배 25 XP, 무승부 50 XP, 취소 0 XP. 상대 자동 선택과 난이도 선택 연습에 같은 규칙을 적용한다. 친선전·PvE·관리자 봇 대전은 제외한다.
- 연습 세션 생성에 성공한 사용자만 성장 대상 경기로 등록한다. 등록 전 과거 연습 경기는 소급하지 않으며, 경험치와 레벨 보상은 기존 경기 원장과 사용자별 잠금으로 한 번만 지급한다.
- 데스크톱 로컬 연습 결과는 클라이언트가 제출하므로 XP를 지급하지 않는다. 공유 게임 서버가 기록한 연습 세션만 반영한다.
- Game PR #185와 GameRust PR #21에서 항복은 `SURRENDER`, 패배 확정 시 접속이 끊긴 경우는 `DISCONNECT`로 저장한다. 해당 원인의 패배는 0 XP, 승자는 100 XP다. 복귀 후 일반 패배와 종료 사유가 없는 과거 패배는 25 XP다. DB V121과 함께 적용한다.
- 레벨 1~9에서 다음 레벨까지 100 XP, 10~29는 250 XP, 30~49는 330 XP. 50레벨 누적 12,500 XP.
- 레벨 2~50마다 상자 1개. 일반 레벨 3칸, 5의 배수 레벨 6칸, 레벨 50은 7칸. 합계 49개·178칸.
- 한국 날짜 기준 일일 3칸 상자 1개. 접속하지 않은 날짜는 소급하지 않는다. 보관한 상자는 만료되지 않는다.

| 레벨 | HELL 고정 2장 | NATURE 고정 2장 |
|---:|---|---|
| 5 | ember_spirit_swarm | water_slime_swarm |
| 10 | meteor_shower | tornado_strike |
| 15 | rock_golem | life_tree |
| 20 | magma_spirit | vine_spirit |
| 25 | frenzy_totem | grass_generator |
| 30 | dragon_tower | tide_call |
| 35 | earth_call | overgrowth |
| 40 | crater_carrier | storm_rider |
| 45 | evil_ent | vine_world |
| 50 | fire_lord_spirit | sea_serpent |

위 지급표는 Database V125부터 적용한다. 같은 카드는 여러 레벨에 등록될 수 있으며
상자 후보는 가장 이른 해금 레벨부터 마법별로 한 번만 포함한다. 예를 들어 rock_golem은
시작 카드이면서 15레벨 보상이므로 레벨 1부터 추첨 가능하다. 보유 상한은 그대로 3장이다.
지급표에 없는 `purpose=PLAYER` 마법은 레벨 1부터 포함한다.
기존 계정의 보유 카드·덱과 이미 발급한 미수령 보상·영수증은 바꾸지 않는다.
상자 후보의 중복 제거를 지원하는 lobby를 먼저 배포한 뒤 V125와 Client #398을 적용한다.

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

수동 수령 변경은 실제 PostgreSQL의 수령 테스트 11개, V123 기존 계정의 V124 업그레이드 테스트 1개, 기존 성장 테스트 10개와 수동 퀘스트 테스트 2개를 포함해 검증했다. 실제 설정 스캔의 flag 주입, 모든 정산 요청의 capability 전달, 영수증 0/1/2장, 동시 수령·재시도·rollback과 상자 개봉의 미수령 보존도 통과했다. 전체 테스트는 447개 중 443개 통과, 기존 보상 기대값 3개 실패, 1개 건너뜀이다. 이 3건은 앞선 봇 XP 변경의 전체 검증에서도 같은 기대값 불일치로 재현됐다. Quest HTTP 검사의 5초 제한은 실행 중 시간 초과를 확인한 뒤 기존 성장 API 검사와 같은 30초로 명시했고 해당 9개는 재검증과 전체 실행에서 통과했다. 운영 배포와 실제 클라이언트 플레이는 수행하지 않았다.

봇 연습 XP 변경은 매칭 서비스 9개와 실제 PostgreSQL 성장 통합 테스트 10개를 검증했다. 세션 생성 실패, 30초 등록 지연, 과거 경기 제외, 동시 동기화, 중복 등록과 레벨 보상을 포함한다. 격리 PostgreSQL을 사용한 전체 테스트는 419개 중 415개 통과, 3개 실패, 1개 건너뜀이다. 실패 3개는 기존 모험 상자의 마법 보상과 MANUAL 퀘스트 목록 기대값 불일치이며, 이번 변경 전 커밋에서는 다시 실행하지 않았다.

신규 정책·API·PostgreSQL 통합 테스트 12개 통과. 관련 상자·매칭을 포함한 기존 회귀 테스트 51개도 통과했다.
최신 DB 전체 마이그레이션을 격리 PostgreSQL에 적용해 동시 선택·중복 상자·풀 고갈·경기 반영을 검증했다.
전체 테스트의 기존 모험 보상 기대값 불일치 3건은 변경 전 `origin/feature/103`에서도 재현된다.
일일 상자 날짜 변경과 항복·이탈 기록의 0 XP 반영도 검증했다. Unity 프리팹 렌더링 캡처는 Client PR #396에 첨부한다. 운영 배포와 운영 서버에 연결한 플레이 검증은 수행하지 않았다.
