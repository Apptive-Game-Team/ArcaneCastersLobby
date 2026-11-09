# 레벨별 확정 보상 조회

`GET /api/users/mine/progression/rewards`는 로그인한 계정의 선택 세력에 해당하는 레벨 1~50 지급 목록을 반환한다. 조회는 경험치 반영, 일일 상자 지급, 계정 생성, 카드 지급을 수행하지 않는다. 세력 선택 전에는 409를 반환한다.

```json
{
  "faction": "NATURE",
  "levels": [{
    "level": 10,
    "requiredXp": 900,
    "chestCount": 1,
    "rewardSlots": 6,
    "cards": [{"magicId": 43, "magicName": "tornado_strike", "amount": 2}]
  }]
}
```

예시는 10레벨 행만 표시한다. 응답은 50개 행이며, 카드 ID와 이름은 `progression_magic_rewards`와 `magics`에서 읽는다. 1레벨에는 시작 카드 5종을 각 3장 표시하고 상자는 없다. 이후 각 레벨에는 상자 1개, 5레벨마다 선택 세력 카드 2장이 표시된다. 상자의 보상 칸 수는 지급 서비스가 사용하는 `ProgressionPolicy.slots`와 같다.

클라이언트는 성공한 `/sync` 응답의 현재 레벨로 지급 완료 상태를 표시한다. 고정 카드의 최대 보유 수는 기존 지급 규칙을 따른다. 상대 세력 마법과 상자 풀 해금 목록은 이 응답에 포함하지 않는다. 기존 sync/faction 응답과 지급 정책은 변경하지 않는다.
