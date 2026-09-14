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

카드 조합으로 완성되는 모든 마법 데이터를 가져옵니다. 특정 버전을 기준으로 변경 사항이 하나라도 있으면 전체 마법 스냅샷을 반환합니다.

### Query Parameters

| Name             | Type   | Description                                                                                           | Required |
|------------------|--------|---------------------------------------------------------------------------------------------------------|----------|
| `currentVersion` | String | ISO-8601 형식의 날짜-시간 문자열 (e.g., `2023-01-01T12:00:00`). 이 시간 이후에 변경 사항이 하나라도 있으면 전체 마법 스냅샷을 반환합니다. | No       |

### Response Body

성공 시, `MagicsResponse` 객체를 반환합니다.

```json
{
  "magics": [
    {
      "id": 10,
      "name": "fireball",
      "castType": "shoot",
      "cards": ["Fire", "Shoot"],
      "indicator": {
        "version": 1,
        "layers": [
          {
            "shape": "circle",
            "origin": "target",
            "radius": { "parameter": "radius" }
          }
        ]
      }
    },
    {
      "id": 20,
      "name": "ice_wall",
      "castType": "build",
      "cards": ["Water"],
      "indicator": null
    }
  ],
  "version": "2024-01-02T12:00:00",
  "requiresRefresh": true
}
```

-   **magics**: `MagicDto` 객체의 배열
    -   `id` (Long): 마법 ID. `magics.id`입니다.
    -   `name` (String): 마법 이름. `magics.name`입니다.
    -   `castType` (String): 시전 종류. `magics.cast_type`입니다.
    -   `cards` (Array of String): 이 마법을 완성하는 카드 조합. 카드 하나하나의 이름이며, 여러 장이 같은 이름일 수 있습니다.
    -   `indicator` (Object, nullable): 조준 표시를 그리는 방법을 담은 문서. `magics.indicator` jsonb 컬럼 값을 그대로 내려주며, 로비 서버는 이 문서를 파싱하거나 검증하지 않고 그대로 전달만 합니다. 값이 없으면 `null`입니다. 구조는 아래 "Indicator 문서" 절을 참고하세요.
-   **version** (String): 응답에 포함된 마법 중 가장 마지막에 업데이트된 시간 (ISO-8601 형식). `currentVersion` 파라미터가 제공되었지만 새로운 데이터가 없는 경우, 제공된 `currentVersion` 값이 그대로 반환될 수 있습니다.
-   **requiresRefresh** (Boolean): 클라이언트가 전체 데이터를 다시 받아야 하는 경우 `true`, `currentVersion` 기준으로 변경이 없어 빈 응답을 반환하는 경우 `false`입니다.

### Indicator 문서 (version 1)

`indicator`는 `magics.indicator` jsonb 컬럼의 내용을 그대로 옮긴 문서입니다. 로비 서버는 이 문서의 내용을 해석하지 않으므로, 형식이 바뀌어도 로비 서버 코드를 고칠 필요는 없습니다. 클라이언트가 아래 구조에 맞춰 그립니다.

```json
{
  "version": 1,
  "layers": [ ]
}
```

-   `layers` (Array): 순서가 있는 layer 목록. 클라이언트는 모든 layer를 순서대로 그립니다.

공통 layer 필드:

-   `shape` (필수): `"circle"` 또는 `"lane"`.
-   `origin` (선택, 기본값 `"target"`): `"caster"`면 시전자 위치, `"target"`이면 커서 아래 clamp된 조준 지점입니다.

`shape: "circle"`:

-   `radius` (필수, value): 반지름.
-   `forwardOffset` (선택, value, 기본값 `0`): 시전자의 정면 방향으로 중심을 옮기는 거리.
-   `edgeWidth` (선택, value, 기본값 `0`): `0`이면 원을 채우고, `0`보다 크면 그 두께만큼 테두리만 그립니다.

`shape: "lane"`:

-   `end` (필수): `"target"` 또는 `"forward"`.
-   `length` (필수, `end`가 `"forward"`일 때만, value): 월드 단위 길이.
-   `halfWidth` (필수, value): 폭의 절반.

"value"는 다음 중 하나입니다.

-   순수 JSON 숫자.
-   `{"parameter": "<name>"}` — `<name>`은 클라이언트가 `/api/data/parameters`에서 이미 읽어온 파라미터 이름입니다.
-   `{"parameter": "<name>", "fallback": <number>}` — 해당 파라미터를 찾지 못했을 때 쓸 기본값을 함께 지정합니다.

예시 세 가지:

```json
{"version":1,"layers":[{"shape":"lane","origin":"caster","end":"target","halfWidth":{"parameter":"radius"}}]}
```

```json
{"version":1,"layers":[{"shape":"circle","origin":"target","radius":{"parameter":"radius"}}]}
```

```json
{"version":1,"layers":[{"shape":"circle","origin":"target","radius":{"parameter":"radius"}},{"shape":"lane","origin":"target","end":"forward","length":{"parameter":"attack_range"},"halfWidth":0.4}]}
```

### Example Usage

#### 모든 마법 가져오기

`GET /api/data/magics`

#### 특정 버전 이후 변경이 있으면 전체 마법 가져오기

`GET /api/data/magics?currentVersion=2024-01-02T12:00:00`
