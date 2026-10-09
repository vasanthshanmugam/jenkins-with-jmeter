# 5. The JMeter Test Plan and Its Parameterization

File: `test-plans/shoplite-api.jmx`. System under test: the ShopLite API (`app/server.py`), which we
control completely, so the lab never depends on a public endpoint that may be slow, rate-limited or down.

## 5.1 The ShopLite API

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/health` | – | `{"status":"UP"}` |
| POST | `/api/login` | – | `{"token":"…"}` for `user01`…`user20` / `Passw0rd!` |
| GET | `/api/products` | Bearer token | 50 products `[{"id","name","price"}]` |
| GET | `/api/products/{id}` | Bearer token | one product |
| POST | `/api/orders` | Bearer token | `201 {"orderId":1001,"status":"CONFIRMED"}` |
| GET | `/api/orders/{orderId}` | Bearer token | the order |
| GET/POST | `/admin/chaos` | – | inject delay (`delayMs`) or errors (`errorRate`) for exercises |

Try it from a laptop before writing any JMeter:

```bash
curl -s -X POST http://192.168.0.19:8081/api/login -H 'Content-Type: application/json' \
     -d '{"username":"user01","password":"Passw0rd!"}'
# {"token": "5f0c…", "username": "user01"}
curl -s http://192.168.0.19:8081/api/products -H 'Authorization: Bearer 5f0c…' | head -c 200
```

## 5.2 Test plan tree

```
ShopLite API - CI Performance Test           (Test Plan; User Defined Variables ENV, SLA_MS)
└── Shopper Journey                          (Thread Group: threads/rampup/loops/duration from properties)
    ├── HTTP Request Defaults                (protocol/host/port from properties, timeouts)
    ├── HTTP Header Manager - Common         (Content-Type, Accept, User-Agent)
    ├── CSV Data Set Config - Users          (USERNAME, PASSWORD from test-data/users.csv)
    ├── Think Time                           (Uniform Random Timer)
    ├── Duration Assertion - SLA             (every sample must finish within SLA_MS)
    ├── 01_Login                  POST /api/login
    │   ├── Extract TOKEN                    (JSON Extractor $.token, default NOT_FOUND)
    │   ├── Assert HTTP 200
    │   └── Assert token present             (JSON Assertion $.token)
    ├── If logged in (TOKEN extracted)
    │   ├── HTTP Header Manager - Authorization   (Bearer ${TOKEN})
    │   ├── 02_List_Products      GET /api/products
    │   │   ├── Extract random PRODUCT_ID    ($[*].id, match no. 0 = random)
    │   │   └── Assert HTTP 200
    │   ├── 03_Get_Product        GET /api/products/${PRODUCT_ID}
    │   │   ├── Assert HTTP 200
    │   │   └── Assert returned id = PRODUCT_ID
    │   ├── 04_Create_Order       POST /api/orders  {"productId": ${PRODUCT_ID}, "quantity": ${__Random(1,3,)}}
    │   │   ├── Extract ORDER_ID
    │   │   └── Assert HTTP 201
    │   └── If order created (ORDER_ID extracted)
    │       └── 05_Get_Order      GET /api/orders/${ORDER_ID}
    │           └── Assert status = CONFIRMED
    ├── View Results Tree   (disabled – GUI debugging only)
    └── Summary Report      (disabled – GUI debugging only)
```

## 5.3 Each element and why it is there

| Element | Why |
|---|---|
| **Test Plan → User Defined Variables** | Turns properties into variables once (`ENV=${__P(env,local)}`, `SLA_MS=${__P(sla_ms,3000)}`) so samplers can use `${ENV}`. |
| **Thread Group** | Load model. `on_sample_error=continue` – one failed request must not stop a virtual user, otherwise errors *reduce* load and hide problems. `scheduler=true` + `duration` makes run time predictable for CI. `same_user_on_next_iteration=true` keeps cookies/variables per user. |
| **HTTP Request Defaults** | Target defined once. Switching environments changes only properties, never samplers. Connect timeout 5 s / response timeout 10 s – without timeouts a hung server makes a CI build hang. |
| **HTTP Header Manager – Common** | JSON content type for every request; `User-Agent: JMeter-CI/<env>` lets server logs and APM separate test traffic from real users. |
| **CSV Data Set Config** | Each virtual user logs in as a different user (`shareMode.all`, recycle at EOF). Real systems cache per user; one shared login would make results unrealistically good. |
| **JSON Extractor (TOKEN, PRODUCT_ID, ORDER_ID)** | **Correlation**: values created by the server at runtime are captured and reused. Default `NOT_FOUND` makes failures explicit instead of sending `${TOKEN}` literally. |
| **If Controllers** | Skip calls that cannot succeed (no token / no order) – one root error, not five cascading ones. Uses `__jexl3` with "Interpret condition as variable expression" for performance. |
| **Response / JSON Assertions** | HTTP 200 is not enough: a login page can return 200 with an error body. Assertions validate *content* (`$.token` exists, returned `id` equals the requested id, order status is `CONFIRMED`). |
| **Duration Assertion** | Marks a sample failed if it exceeds `SLA_MS` – an individual-request SLA, separate from the aggregate p95 gate. |
| **Uniform Random Timer** | Think time between requests (`thinktime` ± `thinktime_range` ms). Without it 10 threads behave like hundreds of real users. Set to 0 only for the smoke test. |
| **Listeners (disabled)** | View Results Tree / Summary Report are for debugging in the GUI. In non-GUI runs they waste CPU and memory; results go to the JTL via `-l`. Keep them **disabled** before committing. |

Sampler names start with a number (`01_` … `05_`) so the dashboard tables sort in journey order and the gate
can reference them (`critical.labels=01_Login,04_Create_Order`). Renaming a sampler is therefore a change to
the gate contract – review it like code.

## 5.4 Parameterization: JMeter properties vs variables

| | **Property** | **Variable** |
|---|---|---|
| Scope | Global – shared by all threads | Per thread (each virtual user has its own copy) |
| Set by | `-Jname=value`, `-q file`, `user.properties`, `__setProperty` | User Defined Variables, extractors, CSV Data Set, `__V`… |
| Read with | `${__P(name,default)}` or `${__property(name,,default)}` | `${name}` |
| Typical use | Configuration from outside: host, users, duration, environment | Runtime data: token, product id, username |
| Can Jenkins set it? | **Yes** – via the command line | No (not directly) |

Properties used by `shoplite-api.jmx` – **every one has a valid default**, so the plan opens and runs in the GUI
without any command-line arguments:

| Property | Default | Used in | Notes |
|---|---|---|---|
| `env` | `local` | UDV `ENV` → User-Agent | label only |
| `protocol` | `http` | HTTP Request Defaults | |
| `host` | `localhost` | HTTP Request Defaults | `perf-app` in CI, `192.168.0.19` from a laptop |
| `port` | `8081` | HTTP Request Defaults | `8080` inside Docker |
| `threads` | `5` | Thread Group | integer ≥ 1 |
| `rampup` | `10` | Thread Group | seconds |
| `loops` | `-1` | Thread Group loop count | `-1` = until `duration`; smoke uses `1` |
| `duration` | `60` | Thread Group scheduler | seconds |
| `thinktime` | `1000` | Uniform Random Timer constant delay | ms |
| `thinktime_range` | `500` | Uniform Random Timer random range | ms |
| `sla_ms` | `3000` | UDV `SLA_MS` → Duration Assertion | ms |
| `datafile` | `../test-data/users.csv` | CSV Data Set | **relative to the JMX file's folder**, not the current directory |

**Numeric properties.** Thread Group fields are parsed as integers *after* function evaluation. If someone passes
`-Jthreads=ten`, JMeter logs a `NumberFormatException` and the thread group may start 0 threads – the run
"succeeds" with an empty JTL. That is why the Jenkinsfile validates numbers before calling JMeter and the gate
enforces `min.samples`.

### Precedence (last one wins)

```
JMX default in __P(name,default)  <  jmeter.properties / user.properties  <  -q file(s) in order  <  -J on the command line
```

So `-q config/env/ci.properties -Jhost=other-host` targets `other-host`. Try it:

```bash
jmeter -n -t test-plans/shoplite-api.jmx -q config/jmeter-ci.properties -q config/env/local.properties \
       -Jthreads=2 -Jloops=1 -Jthinktime=0 -l results/try.jtl
grep -c 01_Login results/try.jtl       # 2  (one login per thread)
```

### From Jenkins parameter to JMeter property

```
Build with Parameters: THREADS=25
   └─▶ environment { THREADS = "${params.THREADS}" }          (Jenkinsfile)
         └─▶ sh: -Jthreads="$THREADS"                          (shell variable)
               └─▶ JMeter property threads=25
                     └─▶ ${__P(threads,5)} in the Thread Group = 25 users
```

## 5.5 Validate the script in the GUI before committing

1. Open the GUI with the laptop environment loaded:
   ```bash
   jmeter -q config/env/local.properties -Jthreads=1 -Jloops=1 -Jthinktime=0 -t test-plans/shoplite-api.jmx
   ```
   (Windows: `"%JMETER_HOME%\bin\jmeter.bat" -q config\env\local.properties -Jthreads=1 -Jloops=1 -Jthinktime=0 -t test-plans\shoplite-api.jmx`)
2. Right-click **View Results Tree → Enable**. Press **Start** (green arrow).
3. Check every sampler is green, and in *Response data* that `01_Login` returns a token and `05_Get_Order`
   shows `"status": "CONFIRMED"`. In *Request* check the `Authorization: Bearer …` header is a real token, not `${TOKEN}`.
4. Negative check: change the CSV password column to `wrong` in the GUI's CSV file copy → `01_Login` fails, and
   02–05 are **skipped** (If Controller) instead of failing.
5. **Disable View Results Tree again**, save, and run the CLI smoke test:
   ```bash
   ./scripts/run-local.sh smoke local          # Windows: .\scripts\run-local.ps1 -Mode smoke
   ```
   Expected tail: `RESULT     : PASS (build -> SUCCESS)`.
6. Commit only after step 5 passes: `git add test-plans/shoplite-api.jmx && git commit -m "…"`.

Never run the actual load test from the GUI – see docs/06 §6.9.
