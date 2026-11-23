# Kong API Gateway on Cloud Run

This folder contains the Kong API gateway that sits in front of the `election-ai` app on Google Cloud Run.
All public traffic goes through Kong. The app itself cannot be reached from the internet.

## Architecture

```
                      Internet
                         |
                         v
   +---------------------------------------------+
   |  election-ai-gateway  (Cloud Run, public)   |
   |  Kong 3.9.1, DB-less                        |
   |   /         -> rate limit 120/min per IP    |
   |   /api      -> API key + 60/min per key     |
   |   /refresh  -> POST only, 5/hour per IP     |
   +---------------------------------------------+
                         |  Direct VPC egress (default network)
                         |  counts as "internal" traffic
                         v
   +---------------------------------------------+
   |  election-ai  (Cloud Run, ingress=internal) |
   |  Spring Boot app                            |
   +---------------------------------------------+
```

| Item | Value |
|---|---|
| GCP project | `epictforce` |
| Region | `asia-south1` |
| Gateway service | `election-ai-gateway`: https://election-ai-gateway-839833619222.asia-south1.run.app |
| App service | `election-ai`: https://election-ai-839833619222.asia-south1.run.app (returns 404 from the internet) |
| API key secrets | Secret Manager `kong-api-key`, `kong-api-key-web`, `kong-api-key-desktop` (one per consumer, see below) |
| Runtime service account | `839833619222-compute@developer.gserviceaccount.com` (default compute SA) |
| VPC used for gateway egress | network `default`, subnet `default` (asia-south1) |

## Files

| File | Purpose |
|---|---|
| [kong.yml](kong.yml) | Kong declarative config: upstream service, routes, plugins and consumers. Each consumer key is written as a placeholder such as `${KONG_API_KEY_WEB}`. |
| [start.sh](start.sh) | Container entrypoint. Replaces each placeholder listed in `KEY_VARS` with the matching env var's value, writes the result to `/tmp/kong.yml`, then starts Kong. It refuses to start if any key is missing. |
| [Dockerfile](Dockerfile) | Builds on `kong:3.9.1`. Runs Kong DB-less on port 8080 with the Admin API disabled and logs going to stdout/stderr. |

Kong runs in **DB-less mode**, so it needs no Postgres. All configuration lives in `kong.yml` and is built into the image.
To change routes or plugins, edit `kong.yml` and redeploy the gateway.

## Routes and plugins

| Route | Paths | Plugins | Notes |
|---|---|---|---|
| `web` | `/` (everything not matched below) | `rate-limiting` 120/min per client IP | Web pages, CSV downloads and static files. No key needed. |
| `api` | `/api/*` | `key-auth` (header or query param `apikey`), `rate-limiting` 60/min per consumer | JSON API. Without a key, Kong returns 401. `hide_credentials` removes the key before the request reaches the app. |
| `refresh` | `POST /refresh` | `rate-limiting` 5/hour per IP | Reloads all official data, which is expensive. |

Kong matches the longest path prefix first, so `/api/...` uses the `api` route and every other path uses `web`.
`strip_path: false` means the app receives the original path unchanged.

## Consumers (API clients)

A **consumer** is one client of the API. Each consumer has its own key and its own 60/min limit on `/api`,
so you can revoke or rotate one client's key without affecting the others.

| Consumer | Env var in the container | Secret Manager secret |
|---|---|---|
| `default-client` | `KONG_API_KEY` | `kong-api-key` |
| `web-app` | `KONG_API_KEY_WEB` | `kong-api-key-web` |
| `desktop-app` | `KONG_API_KEY_DESKTOP` | `kong-api-key-desktop` |

Key flow: Secret Manager → (`--set-secrets`) → env var → (`start.sh`) → `/tmp/kong.yml` → Kong.
The client sends its key in the `apikey` header, and Kong removes the key before forwarding the request to the app.

API endpoints protected by the `api` route (see `ConstituencyController.java`):

- `GET /api/constituencies`
- `GET /api/constituency/{ac}`
- `GET /api/constituency/{ac}/areas`

Settings in the [Dockerfile](Dockerfile) that matter on Cloud Run:

- `KONG_PROXY_LISTEN=0.0.0.0:8080`: Cloud Run sends traffic to port 8080.
- `KONG_TRUSTED_IPS=0.0.0.0/0,::/0` and `KONG_REAL_IP_HEADER=X-Forwarded-For`: Kong reads the real client IP from Google's front end, so per-IP rate limits apply to the actual user and not to Google's proxy.
- `KONG_ADMIN_LISTEN=off`: the Admin API is not exposed.
- `KONG_HEADERS=off`: Kong omits its `Via`/`X-Kong-*` headers. Rate-limit headers are still sent.
- `policy: local`: each Cloud Run instance keeps its own counters. With more than one instance, the effective limit is the configured limit multiplied by the number of instances.

## Installation (one-time setup)

Run these commands in **PowerShell**. In Git Bash, `gcloud` fails with "Python was not found".
No local Docker is needed, because Cloud Build builds the image from source.

### Prerequisites

```powershell
gcloud auth login
gcloud config set project epictforce
gcloud services enable run.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com compute.googleapis.com
```

### Step 1: Create the API key secrets (one per consumer)

Kong does not issue keys. Each key is any random string you choose.

> **Do not pipe a key into gcloud from PowerShell** (`$key | gcloud ... --data-file=-`).
> PowerShell 5.1 adds a byte-order mark and CRLF, so the stored key no longer matches what clients send, and they get 401.
> Write the key to a file without a BOM or newline, as shown below.

```powershell
$chars = [char[]]'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
$tmp = "$env:TEMP\kong-key.txt"
foreach ($s in "kong-api-key","kong-api-key-web","kong-api-key-desktop") {
  $key = -join ((1..40) | % { $chars | Get-Random })
  [IO.File]::WriteAllText($tmp, $key, (New-Object Text.UTF8Encoding $false))
  gcloud secrets create $s --data-file=$tmp --project epictforce
}
Remove-Item $tmp
```

### Step 2: Allow the runtime service account to read the secrets

```powershell
foreach ($s in "kong-api-key","kong-api-key-web","kong-api-key-desktop") {
  gcloud secrets add-iam-policy-binding $s --project epictforce `
    --member="serviceAccount:839833619222-compute@developer.gserviceaccount.com" `
    --role="roles/secretmanager.secretAccessor"
}
```

### Step 3: Deploy the gateway

Run this from the `gateway/` folder:

```powershell
cd gateway
gcloud run deploy election-ai-gateway --source . --region asia-south1 --project epictforce `
  --set-secrets "KONG_API_KEY=kong-api-key:latest,KONG_API_KEY_WEB=kong-api-key-web:latest,KONG_API_KEY_DESKTOP=kong-api-key-desktop:latest" `
  --allow-unauthenticated --port 8080 --quiet
```

`--set-secrets` makes Cloud Run load each secret into its env var. `start.sh` then writes the keys into the config.
`--set-secrets` replaces the whole list, so always pass every secret.

### Step 4: Turn on Private Google Access for the subnet

The gateway calls the app's `*.run.app` URL from inside the VPC. For that call to work, the subnet needs Private Google Access.

```powershell
gcloud compute networks subnets update default --region asia-south1 --project epictforce `
  --enable-private-ip-google-access
```

### Step 5: Route all gateway egress through the VPC

```powershell
gcloud run services update election-ai-gateway --region asia-south1 --project epictforce `
  --network default --subnet default --vpc-egress all-traffic --quiet
```

The gateway's requests to the app now count as **internal**.
The gateway has no public egress apart from the app. If it ever needs to reach other internet hosts, add Cloud NAT to the `default` network.

### Step 6: Make the app internal-only

```powershell
gcloud run services update election-ai --region asia-south1 --project epictforce --ingress internal --quiet
```

From this point, calls to the app's own URL from the internet return Google's **404** page.

### Step 7: Verify

```powershell
$k  = (gcloud secrets versions access latest --secret kong-api-key --project epictforce).Trim()
$gw = "https://election-ai-gateway-839833619222.asia-south1.run.app"
$app = "https://election-ai-839833619222.asia-south1.run.app"

Invoke-WebRequest "$app/" -UseBasicParsing                                       # expect 404 (blocked)
Invoke-WebRequest "$gw/" -UseBasicParsing                                        # expect 200
Invoke-WebRequest "$gw/api/constituencies" -UseBasicParsing                      # expect 401 (no key)
Invoke-WebRequest "$gw/api/constituencies" -Headers @{apikey=$k} -UseBasicParsing # expect 200
```

To confirm that responses come through Kong, check that they carry `x-ratelimit-limit-minute` / `ratelimit-remaining` headers.

## Using the API

Look up a consumer's key (for example, the web app's):

```powershell
gcloud secrets versions access latest --secret kong-api-key-web --project epictforce
```

Call the API:

```
curl -H "apikey: <key>" https://election-ai-gateway-839833619222.asia-south1.run.app/api/constituencies
```

## Operations

### Redeploy the app

Run this from the repo root as usual:

```powershell
gcloud run deploy election-ai --source . --region asia-south1 --project epictforce --quiet
```

The `ingress=internal` setting is kept. **Do not** pass `--ingress all`, because that makes the app public again.

### Change Kong configuration

Edit [kong.yml](kong.yml), then repeat Step 3 (deploy from `gateway/`). The VPC settings from Step 5 are kept.

### Rotate a consumer's key

This example rotates the web app's key:

```powershell
$chars = [char[]]'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
$tmp = "$env:TEMP\kong-key.txt"
[IO.File]::WriteAllText($tmp, (-join ((1..40) | % { $chars | Get-Random })), (New-Object Text.UTF8Encoding $false))
gcloud secrets versions add kong-api-key-web --data-file=$tmp --project epictforce
Remove-Item $tmp
# Create a new revision so that instances pick up the new version:
gcloud run services update election-ai-gateway --region asia-south1 --project epictforce `
  --update-secrets KONG_API_KEY_WEB=kong-api-key-web:latest
```

The old key stops working once the new revision is serving. The other consumers are not affected.

### Add another consumer

For example, a consumer called `mobile-app`:

1. In `kong.yml`, add the consumer:
   ```yaml
   - username: mobile-app
     keyauth_credentials:
       - key: "${KONG_API_KEY_MOBILE}"
   ```
2. In `start.sh`, add `KONG_API_KEY_MOBILE` to `KEY_VARS`.
3. Create the secret `kong-api-key-mobile` and grant access to it (Steps 1–2).
4. Redeploy (Step 3), adding `KONG_API_KEY_MOBILE=kong-api-key-mobile:latest` to `--set-secrets`.

To revoke a client, remove its consumer from `kong.yml` and `start.sh`, then redeploy.

### View logs

```powershell
gcloud run services logs read election-ai-gateway --region asia-south1 --project epictforce --limit 50
```

### Roll back or remove the gateway

```powershell
# Make the app public again (the gateway is no longer required):
gcloud run services update election-ai --region asia-south1 --project epictforce --ingress all
# Optionally delete the gateway:
gcloud run services delete election-ai-gateway --region asia-south1 --project epictforce
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Cloud Build fails with `the --chmod option requires BuildKit` | Cloud Build's default builder does not support `COPY --chmod`. The Dockerfile uses `RUN chmod` as root instead. |
| Gateway container exits with `KONG_API_KEY is not set` | `--set-secrets` is missing, or the service account cannot read the secret (see Step 2). |
| Gateway returns 502/504 or the request times out | The gateway's calls to the app are not counted as internal. Check Step 4 (Private Google Access) and Step 5 (`--vpc-egress all-traffic`). The first request after a cold start can be slow. Kong's timeouts (60s connect, 120s read) allow for this. |
| Gateway container exits with `KONG_API_KEY_... is not set` | A key named in `KEY_VARS` was not passed with `--set-secrets`. |
| `/api` returns 401 | The `apikey` header is missing or wrong. If the key is correct, check whether the secret was saved with a BOM or CRLF (see Step 1). |
| 429 Too Many Requests | A rate limit was hit. Limits are set in `kong.yml`. |
| The app's own URL returns 404 | This is expected: the app is internal-only. Use the gateway URL. |
| `gcloud` fails with "Python was not found" | Run gcloud from PowerShell, not Git Bash. |
