# Data API

## Get Parameters

### `GET /api/data/parameters`

게임 내에서 사용되는 모든 파라미터 데이터를 가져옵니다. 특정 버전을 기준으로 변경 사항이 하나라도 있으면 전체 파라미터 스냅샷을 반환합니다.

### Query Parameters

| Name             | Type   | Description                                                                                             | Required |
|------------------|--------|---------------------------------------------------------------------------------------------------------|----------|
| `currentVersion` | String | ISO-8601 형식의 날짜-시간 문자열 (e.g., `2023-01-01T12:00:00`). 이 시간 이후에 변경 사항이 하나라도 있으면 전체 파라미터 스냅샷을 반환합니다. | No       |

### Response Body

성공 시, `ParametersResponse` 객체를 반환합니다.

```json
{
  "parameters": [
    {
      "gameObjectName": "Player",
      "paramName": "MaxHP",
      "value": 100.0
    },
    {
      "gameObjectName": "Player",
      "paramName": "AttackPower",
      "value": 10.0
    }
  ],
  "version": "2023-01-01T15:30:00",
  "requiresRefresh": true
}
```

-   **parameters**: `Parameter` 객체의 배열
    -   `gameObjectName` (String): 파라미터가 속한 게임 객체의 이름.
    -   `paramName` (String): 파라미터의 이름.
    -   `value` (Double): 파라미터의 값.
-   **version** (String): 응답에 포함된 파라미터 중 가장 마지막에 업데이트된 시간 (ISO-8601 형식). `currentVersion` 파라미터가 제공되었지만 새로운 데이터가 없는 경우, 제공된 `currentVersion` 값이 그대로 반환될 수 있습니다. 변경 사항이 하나라도 감지되면 전체 파라미터 스냅샷이 반환되며, 버전은 그 스냅샷의 최신 타임스탬프가 됩니다.
-   **requiresRefresh** (Boolean): 클라이언트가 전체 데이터를 다시 받아야 하는 경우 `true`, `currentVersion` 기준으로 변경이 없어 빈 응답을 반환하는 경우 `false`입니다.

### Example Usage

#### 모든 파라미터 가져오기

`GET /api/data/parameters`

#### 특정 버전 이후 변경이 있으면 전체 파라미터 가져오기

`GET /api/data/parameters?currentVersion=2023-01-01T12:00:00`

## Get Magics

### `GET /api/data/magics`

클라이언트가 카드를 그리고 시전할 때 필요한 마법 목록을 가져옵니다. 특정 버전을 기준으로 변경 사항이 하나라도 있으면 전체 마법 스냅샷을 반환합니다.

### Query Parameters

| Name             | Type   | Description                                                                                          | Required |
|------------------|--------|-------------------------------------------------------------------------------------------------------|----------|
| `currentVersion` | String | ISO-8601 형식의 날짜-시간 문자열 (e.g., `2023-01-01T12:00:00`). 이 시간 이후에 변경 사항이 하나라도 있으면 전체 마법 스냅샷을 반환합니다. | No       |

### Response Body

성공 시, `MagicsResponse` 객체를 반환합니다.

```json
{
  "version": "2023-01-01T15:30:00",
  "magics": [
    {
      "id": 34,
      "name": "leafair",
      "element": "Nature",
      "manaCost": 10,
      "aimShape": 0
    }
  ],
  "requiresRefresh": true
}
```

-   **magics**: `MagicDto` 객체의 배열
    -   `id` (Long): 마법 식별자. `magics.id`.
    -   `name` (String): 마법 이름. `magics.name`이며 마법 bean 이름과 같습니다.
    -   `element` (String): 마법의 원소. `Fire`, `Water`, `Lightning`, `Rock`, `Nature`, `Wind`, `None` 중 하나입니다.
    -   `manaCost` (Integer): 시전에 필요한 마나. `game_objects.name = magics.name`으로 이어진 `parameter_values`의 `mana_cost` 값입니다.
    -   `aimShape` (Integer): 조준 표시 모양. `1`이면 직선, `0`이면 원입니다. 같은 방식으로 `parameter_values`의 `aim_shape` 값에서 옵니다.
-   **version** (String): 응답에 포함된 마법 중 가장 마지막에 업데이트된 시간 (ISO-8601 형식). `currentVersion` 파라미터가 제공되었지만 새로운 데이터가 없는 경우, 제공된 `currentVersion` 값이 그대로 반환될 수 있습니다.
-   **requiresRefresh** (Boolean): 클라이언트가 전체 데이터를 다시 받아야 하는 경우 `true`, `currentVersion` 기준으로 변경이 없어 빈 응답을 반환하는 경우 `false`입니다.

카드 조합(`cards`)과 시전 종류(`castType`)는 더 이상 내려주지 않습니다. 카드 한 장이 마법 하나가 되면서 조합이라는 개념 자체가 없어졌고, 마나 비용과 사거리 같은 값의 키가 시전 종류 이름 대신 마법 이름으로 옮겨갔기 때문입니다.

### Example Usage

#### 모든 마법 가져오기

`GET /api/data/magics`

#### 특정 버전 이후 변경이 있으면 전체 마법 가져오기

`GET /api/data/magics?currentVersion=2023-01-01T12:00:00`
