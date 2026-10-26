# SMF-33. Что добавить в smart-factoring-deploy

В репозиторий деплоя изменения не вносились: в нём нет ветки `feature/SMF-33`, а у `smart-factoring-ml` ещё нет CI,
который публикует образ в ghcr. Ниже — готовый фрагмент.

## Новые переменные backend

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `DEMO_IMPORT_DIR` | `/data/demo` | каталог с выгрузками генератора для `POST /demo/import {path}` (смонтировать volume) |
| `ML_MODE` | `stub` | `stub` — встроенная заглушка моделей, `http` — сервис smart-factoring-ml |
| `ML_BASE_URL` | `http://smart-factoring-ml:8000` | адрес сервиса моделей |
| `ML_STUB_SCORES_DIR` | пусто | для `stub`: каталог со `stub_*_scores.csv` |
| `LLM_MODE` | `stub` | `stub` — шаблонные тексты, `gigachat`, `off` — без черновика |
| `LLM_PARALLELISM` | `8` | одновременных запросов к LLM |
| `GIGACHAT_AUTH_KEY` | пусто | ключ авторизации GigaChat API (секрет) |
| `GIGACHAT_SCOPE` | `GIGACHAT_API_PERS` | тариф |
| `GIGACHAT_CA_CERT` | пусто | путь к PEM корневого сертификата НУЦ Минцифры внутри контейнера (Р-49) |
| `GIGACHAT_MODEL` | `GigaChat` | модель: `GigaChat`, `GigaChat-Pro`, `GigaChat-Max` — по тарифу |
| `GIGACHAT_TIMEOUT` | `60s` | таймаут одного запроса |

Сервису `ml` — `BACKEND_LLM_URL` (по умолчанию `http://backend:8080/internal/rest/llm`): LLM только через backend (Р-48).

`POSTGRES_SCHEMA` на стенде — `smart_factoring_test` (схема создаётся заранее, приложение её не создаёт).

## Фрагмент docker-compose.yml

```yaml
  backend:
    # ... как сейчас, плюс:
    environment:
      DEMO_IMPORT_DIR: /data/demo
      ML_MODE: ${ML_MODE:-stub}
      ML_BASE_URL: http://ml:8000
      LLM_MODE: ${LLM_MODE:-stub}
      GIGACHAT_AUTH_KEY: ${GIGACHAT_AUTH_KEY:-}
      GIGACHAT_CA_CERT: /certs/russian_trusted_root_ca.pem
    volumes:
      - ./demo:/data/demo:ro
      - ./certs:/certs:ro

  ml:
    image: ghcr.io/monbar921/smart-factoring-ml:${ML_TAG:?ML_TAG is required}
    restart: unless-stopped
    environment:
      # обученные модели коллеги, когда появятся
      PD_MODEL_MODULE: ${PD_MODEL_MODULE:-}
      DELAY_MODEL_MODULE: ${DELAY_MODEL_MODULE:-}
    cpus: ${ML_CPUS:-1}
    mem_limit: ${ML_MEM_LIMIT:-1g}
    healthcheck:
      test: [ "CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/v1/health')" ]
      interval: 10s
      timeout: 3s
      retries: 6
```

Сервис `ml` доступен только внутри сети стенда (без `ports`), как backend.

## Роли Keycloak (Р-44)

Gateway пускает к экрану процесса только с ролью realm `operator`, к эмулятору CRM — только с `manager`.
**Роли нужно завести до выката нового gateway**, иначе тестовый пользователь получит 403 / «Нет доступа».

Keycloak импортирует `infra/keycloak/realms/smart-factoring-test-realm.json` только если realm ещё нет, поэтому
на существующем стенде — вручную (Admin Console → realm smart-factoring-test → Realm roles → Create role
`operator` и `manager`; Users → тестовый пользователь → Role mapping → Assign role) или через `kcadm.sh`:

```bash
kcadm.sh create roles -r smart-factoring-test -s name=operator -s 'description=Экран процесса: импорт и запуск прогона'
kcadm.sh create roles -r smart-factoring-test -s name=manager -s 'description=Эмулятор CRM: решения по предложениям'
kcadm.sh add-roles -r smart-factoring-test --uusername "$KEYCLOAK_TEST_USER" --rolename operator --rolename manager
```

Для новых стендов — в realm-файл:

```json
"roles": { "realm": [
  { "name": "user", "description": "Пользователь smart-factoring" },
  { "name": "operator", "description": "Экран процесса: импорт демо-данных и запуск прогона" },
  { "name": "manager", "description": "Эмулятор CRM: очередь, карточка клиента, решения по предложениям" }
] },
```

и тестовому пользователю `"realmRoles": ["user", "operator", "manager"]`. Роли попадают в access token
в `realm_access.roles` стандартным client scope `roles` — отдельная настройка клиентов не нужна.
После выдачи ролей пользователю нужно перелогиниться (роли — в новом токене).

## GigaChat

1. Ключ авторизации: личный кабинет GigaChat API (developers.sber.ru) → проект → «Получить ключ».
   Это Base64 `client_id:client_secret` — в секрет окружения `GIGACHAT_AUTH_KEY`, в git не класть.
2. Сертификат: GigaChat использует сертификаты НУЦ Минцифры, которых нет в JDK. Скачать «Russian Trusted Root CA»
   (PEM) с портала Госуслуг (раздел про сертификаты НУЦ Минцифры) и положить на VDS в `certs/russian_trusted_root_ca.pem`
   рядом с `docker-compose.yml` стенда. Backend читает его из `GIGACHAT_CA_CERT`, образ пересобирать не нужно (Р-49).
3. Включить: `LLM_MODE=gigachat`. Без ключа backend не стартует — это намеренно, чтобы не уйти в молчаливую деградацию.
4. Проверить до прогона: ручной тест `GigaChatManualTest` (см. его javadoc) или запрос к backend из сети стенда:
   `curl -s -XPOST http://backend:8080/internal/rest/llm/chat/completions -H 'Content-Type: application/json'
   -d '{"messages":[{"role":"user","content":"Привет"}]}'`.
