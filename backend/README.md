# backend — Veresiye Platform API

FastAPI + PostgreSQL. Uygular: [`shared-contracts/openapi.yaml`](../shared-contracts/openapi.yaml)
(Bölüm A) + [`docs/db-schema.md`](../docs/db-schema.md). Plan: [`docs/faz5-backend-plan.md`](../docs/faz5-backend-plan.md).

## Çalıştırma

```bash
docker compose up -d --build
curl -s localhost:4010/health          # {"status":"ok"}
docker compose logs -f api
```

Port **4010**, çünkü app-pos'un debug build'i zaten `http://10.0.2.2:4010/` adresine
bakıyor (eski Prism portu) — Android tarafında tek satır değişmiyor.

## Test

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest -q
```

## Sıfırlama

```bash
docker compose down -v     # volume dahil siler; sonraki açılışta yeniden seed edilir
```

## Bilinmesi gerekenler

- **Bakiye saklanmaz**, `SUM` ile türetilir (`app/ledger.py`) — `core-domain/Ledger.kt::balanceOf`
  ile birebir aynı kural olmak zorunda.
- **`transactions` append-only**, veritabanı seviyesinde trigger ile zorlanıyor. UPDATE/DELETE
  denemesi hata verir; düzeltme = ters işaretli yeni satır.
- **Seed idempotent**: sadece `users` boşsa çalışır. Alembic her açılışta koştuğu için
  koşulsuz seed bakiyeleri katlardı.
- **Seed = app-mobile'ın seed'i** (plan §0.1), ISO-8601'e çevrili. app-pos'unki alt kümesiydi
  ve `/me/debts` için gereken ikinci satıcıyı içermiyordu.
