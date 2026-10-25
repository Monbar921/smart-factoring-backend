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
    volumes:
      - ./demo:/data/demo:ro

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

## GigaChat

Сертификаты GigaChat выданы НУЦ Минцифры: корневой сертификат нужно добавить в truststore JVM образа backend
(или смонтировать truststore и задать `-Djavax.net.ssl.trustStore`), иначе TLS-рукопожатие не пройдёт.
