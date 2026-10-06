# DeepSeek compatibility proxy

Fixes the Android Studio "agent" panel error when talking to DeepSeek:

```
Model query failed: 422: Failed to deserialize the JSON body into the target type:
messages[0].role: unknown variant `developer`,
expected one of `system`, `user`, `assistant`, `tool`, `latest_reminder`
```

The panel sends OpenAI-style requests that use the `developer` role, and
Responses-API content blocks (`input_text`/`input_image`) that DeepSeek does
not accept. DeepSeek's schema is an *internally tagged enum*: a message's
`content` must be **either** a plain string **or** an array of blocks whose
types are `text`/`image_url`. DeepSeek also validates tool schemas strictly
(every function's `parameters` must be object-rooted JSON Schema). This tiny
PHP script does six things:

1. rewrites `developer` -> `system` (merging multiple leading system messages
   into one, since DeepSeek accepts only a single system message);
2. translates OpenAI **Responses API** requests (`POST /responses`, which
   DeepSeek does not implement) into `chat/completions` requests, so the panel
   works even though it only offers "OpenAI-compatible" mode;
3. normalises message `content` to DeepSeek's shape: Responses-API
   `input_text`/`output_text` parts become `text` blocks (or collapse to a
   plain string when the whole content is text), and `input_image` becomes
   `image_url`. This avoids the
   `invalid type: string ..., expected ... ChatCompletionRequestContentBlock`
   422 error.
4. normalises every tool/function parameter schema so its root is a JSON
   **object** (`{"type":"object","properties":{...}}`), fixing the
   `Invalid schema for function '...': [] is not of type "object"` 400 error.
5. remaps client model names onto models DeepSeek actually serves. The panel
   hardcodes an OpenAI model for some internal calls (its "sub-agent"), which
   DeepSeek answers with a 404. See `MODEL_ALIASES` / `MODEL_FALLBACK`.
6. can supply its own API key (`FALLBACK_API_KEY`) when the panel only sends a
   placeholder key to a custom base URL.

Everything else is forwarded unchanged to `https://api.deepseek.com`.

It is designed to run as a **subfolder of an existing site**, e.g.
`https://kantai3d.com/deepseek/`.

## Why host it on the NAS

- Always on, no port/resource taken from your dev machine.
- The real API key can live only on the NAS (`FALLBACK_API_KEY`), so Android
  Studio can send a dummy key.

## Deploy as a subfolder (nginx + php-fpm)

Assume the site root is `/3d` (as in your `kantai3d.com` config) and we mount
the proxy at `/deepseek`.

1. Create the folder and copy `index.php` into it:

   ```
   /3d/deepseek/index.php
   ```

2. Add these location blocks to the **existing `server { ... }`** for
   `kantai3d.com`.

   **Important ordering:** nginx evaluates regex `location ~` blocks in order,
   and your config already has `location ~* \.(js|css|...|json)$`. Put the
   `/deepseek/` block **first** so nothing under the proxy is caught by the
   asset rules. The `location = /deepseek/index.php` block guarantees the script
   is only ever executed as its own, isolated file.

   ```nginx
   # --- DeepSeek proxy (place BEFORE the static-asset regex location) ---
   location = /deepseek {
       return 301 /deepseek/;
   }

   location /deepseek/ {
       # Route any path under /deepseek/ to the proxy script.
       try_files $uri /deepseek/index.php?$query_string;
   }

   location = /deepseek/index.php {
       include fastcgi_params;
       fastcgi_pass unix:/run/php/php8.2-fpm.sock;   # <-- your fpm socket
       fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
       # Streaming (SSE) mirrors: disable buffering.
       fastcgi_buffering off;
   }
   # --- end DeepSeek proxy ---
   ```

   Adjust `fastcgi_pass` to match however your existing site talks to PHP. If
   your site already has a generic `location ~ \.php$` block you can reuse its
   `fastcgi_pass`, but a dedicated `location = /deepseek/index.php` is safest:
   it avoids the regex asset block and lets you set `fastcgi_buffering off` only
   where it is needed.

3. Reload nginx: `nginx -s reload`.

## Configure Android Studio

Set the DeepSeek **Base URL** to:

```
https://kantai3d.com/deepseek
```

> Do **not** add `/v1`. The panel appends `/models`, `/responses`, etc. The
> proxy strips `/deepseek`, and (when `TRANSLATE_RESPONSES` is on) rewrites
> `/responses` to `/chat/completions` upstream, so both work.

Choose the **OpenAI-compatible** schema.

For the API key: the panel appears not to forward your real key to a custom base
URL (it sent a 6-character placeholder). So set the real key **in the script**:

```php
const FALLBACK_API_KEY = 'sk-your-real-deepseek-key';
```

and put any dummy value (e.g. `dummy`) in the panel. The proxy recognises short
keys as placeholders (`MIN_REAL_KEY_LEN`) and substitutes the real one. If you
prefer to be explicit, set `FORCE_FALLBACK_KEY = true` to always use the
script's key and ignore the client header entirely.

## Responses API translation

Some IDE panels only offer "OpenAI-compatible" and use the newer **Responses
API** (`POST /responses`) instead of `chat/completions`. DeepSeek does not
implement `/responses`, so the proxy translates it. Mapping:

| Responses request        | Chat/completions request           |
|--------------------------|------------------------------------|
| `instructions` (string)  | leading `system` message           |
| `input` (string)         | a single `user` message            |
| `input` (array of items) | `messages[]` (roles, text parts, `function_call`/`function_call_output`) |
| `tools[]` (`{type,name,parameters}`) | `tools[]` in chat/completions shape (`function.{name,parameters}`), schema re-rooted as object |
| `model`                  | `model` (defaults to `DEFAULT_MODEL`) |
| `max_output_tokens`      | `max_tokens`                       |
| `temperature`, `top_p`, `stream`, `stop`, penalties, `seed` | passthrough |
| `stream_options`         | dropped (DeepSeek rejects it)      |

The translation is **best-effort**: it covers text and the common parameters.
Multi-modal parts other than images and advanced Responses-only features (e.g.
`reasoning`, server-side tools) are not translated. If your panel can be
switched to the classic chat/completions API, prefer that and set
`TRANSLATE_RESPONSES = false`.

### Content normalisation

Independently of the endpoint, every outgoing message's `content` is forced
into DeepSeek's accepted shape by `responses_content_to_chat()`:

| Incoming content part                     | Outgoing                          |
|-------------------------------------------|-----------------------------------|
| plain string                              | plain string                      |
| `{"type":"input_text","text":"…"}`        | `{"type":"text","text":"…"}`      |
| `{"type":"output_text","text":"…"}`       | `{"type":"text","text":"…"}`      |
| `{"type":"text","text":"…"}`              | `{"type":"text","text":"…"}`      |
| `{"type":"input_image","image_url":"…"}`  | `{"type":"image_url","image_url":{"url":"…"}}` |
| all parts are text                        | collapsed to one plain **string** |

Collapsing to a string when everything is text is deliberate: it produces the
simplest valid request and avoids the
`invalid type: string …, expected … ChatCompletionRequestContentBlock` mismatch
that occurs when string and array parts are mixed within one message.


## Config knobs (`index.php`)

| Constant            | Meaning                                                                  |
|---------------------|--------------------------------------------------------------------------|
| `UPSTREAM_BASE`     | Upstream base, default `https://api.deepseek.com`.                       |
| `SUBFOLDER_BASE`    | URL path the proxy is mounted at, default `/deepseek`. Set `''` for root. |
| `FALLBACK_API_KEY`  | Key the proxy uses itself when the client sends none/placeholder.        |
| `MIN_REAL_KEY_LEN`  | Client keys shorter than this count as placeholders (default 20).        |
| `FORCE_FALLBACK_KEY`| If true, always use `FALLBACK_API_KEY`, ignore the client's header.      |
| `TRANSLATE_RESPONSES`| Translate `POST /responses` into `chat/completions` (default true).     |
| `CHAT_COMPLETIONS_PATH`| Upstream path used for the translation (default `/chat/completions`).|
| `DEFAULT_MODEL`     | Model used if a Responses request omits `model` (default `deepseek-chat`).|
| `DEEPSEEK_MODELS`   | Models DeepSeek serves; matching names pass through unchanged.            |
| `MODEL_ALIASES`     | Client-model -> DeepSeek-model map (case-insensitive), e.g. `gpt-4o-mini`.|
| `MODEL_FALLBACK`    | If true, unknown model names fall back to `DEFAULT_MODEL` (default true).  |
| `DEBUG_LOG`         | Logs role rewrites to the php error log. Turn off when done.             |
| `DEBUG_CAPTURE`     | Writes a key-free summary of the last request next to the script.        |
| `DEBUG_SHOW_CAPTURE`| Exposes that summary at `?__debug`. Turn off when done.                  |

## Testing

Health check that it forwards and rewrites (replace host and key):

```bash
curl -sS https://kantai3d.com/deepseek/v1/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [
      {"role": "developer", "content": "You are helpful."},
      {"role": "user", "content": "say hi"}
    ]
  }'
```

Before the proxy this returns the 422 `unknown variant developer`. Through the
proxy it should return a normal completion. Check your php/nginx error log for
lines like:

```
[deepseek-proxy] POST /deepseek/v1/chat/completions -> https://api.deepseek.com/chat/completions
[deepseek-proxy] roles developer,user => system,user
```

## Troubleshooting

### `401: null` from Android Studio

`401: null` means DeepSeek returned 401 but the body was empty/unreadable. Work
through these in order:

1. **Check whether the proxy received a key.** First make the panel send its
   request, then open:

   ```
   https://kantai3d.com/deepseek/?__debug
   ```

   Look at the `last_request` object. It is a key-free summary of the most
   recent real request, e.g.:

   ```json
   "last_request": {
     "phase": "result",
     "method": "POST",
     "request_uri": "/deepseek/v1/chat/completions",
     "upstream_url": "https://api.deepseek.com/chat/completions",
     "upstream_status": 401,
     "client_sent_auth": true,
     "auth_shape": "Bearer …(len 38)",
     "using_fallback": false,
     "rewrote_roles": true,
     "roles_before": ["developer", "user"],
     "roles_after": ["system", "user"]
   }
   ```

   Read it like this:

   - `client_sent_auth: false` and `using_fallback: false` => Android Studio is
     **not** sending the key to your URL. Fix by setting the key server-side:
     ```php
     const FALLBACK_API_KEY = 'sk-your-real-deepseek-key';
     ```
     and use any dummy value (e.g. `proxy`) in the panel. The proxy then
     supplies the real key itself. (`__debug` never prints the key value, only
     its shape and length.)
   - `client_sent_auth: true` but `upstream_status: 401` => the key is reaching
     DeepSeek but is rejected (wrong/expired/out of credit), or an upstream
     CDN/WAF (Cloudflare sees your host) is altering the request. Verify with
     curl in step 2.
   - `rewrote_roles: true` and `roles_after: ["system", ...]` => the actual bug
     this proxy exists to fix is being handled correctly.
   - `last_request` shows `"capture disabled or none yet"` => either no panel
     request has reached the proxy (routing/base-URL problem) or capture is off.

   > Security: the capture file is served by `?__debug`. Set
   > `DEBUG_SHOW_CAPTURE = false` (or `DEBUG_CAPTURE = false`) once you are done.
   > It never contains the API key.


2. **Verify the key survives to DeepSeek** with curl, bypassing Android Studio:

   ```bash
   curl -i -sS https://kantai3d.com/deepseek/v1/chat/completions \
     -H "Authorization: Bearer $DEEPSEEK_KEY" \
     -H "Content-Type: application/json" \
     -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}]}'
   ```

   - `200` + JSON completion => proxy and key are fine; the problem is how
     Android Studio sends the key. Use `FALLBACK_API_KEY`.
   - `401 Authentication Fails` => the key itself is wrong/expired/out of
     credit. Test the same key directly against `https://api.deepseek.com`.
   - Any other response => paste it and check `DEBUG_LOG` in the php error log.

3. **Look at the debug log.** With `DEBUG_LOG = true` the php-fpm/nginx error
   log shows lines like:

   ```
   [deepseek-proxy] POST /deepseek/v1/chat/completions -> https://api.deepseek.com/chat/completions
   [deepseek-proxy] found auth in header 'Authorization' (len=38)
   [deepseek-proxy] roles developer,user => system,user
   [deepseek-proxy] upstream responded 200
   ```

   If you see `WARNING: no auth header from client and FALLBACK_API_KEY is
   empty`, that is the cause of the 401.

4. **Header stripping by an upstream proxy/CDN.** If your site sits behind
   CloudFront or a WAF, confirm it forwards the `Authorization` header. The
   `__debug` endpoint is the quick way to see whether the header reaches PHP.

### 422: `invalid type: string ..., expected internally tagged enum ChatCompletionRequestContentBlock`

Full shape of the error:

```
422: Failed to deserialize the JSON body into the target type:
messages[1]: invalid type: string "<ide-context>...</ide-context>hi",
expected internally tagged enum ChatCompletionRequestContentBlock at line 1 column 36213
```

DeepSeek's message schema is an **internally tagged enum**: `content` must be
**either** a plain string **or** an array of block objects, and block objects
must use DeepSeek's block types (`text` / `image_url`). The Android Studio panel
sends Responses-API blocks (`input_text` / `input_image`) and can mix several
parts in one message. The proxy now:

- converts `input_text` / `output_text` / `summary_text` parts to `text`;
- converts `input_image` to `image_url: { url }`;
- collapses a message to a single plain string when all its parts are text
  (which is what the panel's `<ide-context>…hi` message becomes);
- keeps an array of blocks only when the message is genuinely multimodal.

Because merging system messages also has to produce valid content, leading
system messages are collapsed to plain text before being merged.

Verify the exact shape the proxy sends with `?__debug`. The `last_request`
object now includes `messages_summary`, a key-free list of every outgoing
message:

```json
"messages_summary": [
  { "i": 0, "role": "system",        "content_type": "string", "has_tool": false, "preview": "You are an expert…" },
  { "i": 1, "role": "user",          "content_type": "string", "has_tool": false, "preview": "<ide-context>…hi" },
  { "i": 2, "role": "assistant",     "content_type": "array",  "has_tool": true,  "preview": "[array:function]" }
]
```

Read it like this:

- `content_type: "string"` for a text-only message is what we want.
- `content_type: "array"` with `preview: "[array:text,array:image_url]"` is a
  multimodal message; every block type must be `text` or `image_url`.
- If you still see `array:input_text` or `array:input_image`, the panel is
  sending something the translation did not recognise — check that you are
  hitting the `/responses` path (so `translated_responses` is `true`) and
  extend `responses_content_to_chat()`.

### 400: `Invalid schema for function '...': [] is not of type "object"`

Full shape of the error:

```
400: Invalid schema for function 'get_top_level_sub_projects':
[] is not of type "object" (request_id: ...)
```

DeepSeek validates each tool/function like a strict JSON Schema validator and
requires the **root** of every `parameters` object to be an object
(`"type":"object"`). OpenAI is lenient; DeepSeek is not. The Android Studio
panel sometimes sends:

- `"parameters": []` (empty array), or
- `"parameters": {}` / a schema without `"type"`, or
- a Responses-shaped tool (`{"type":"function","name":...,"parameters":...}`)
  that is not in the chat/completions shape.

The proxy now fixes all of these:

- `normalise_schema_root()` forces `"type":"object"` and guarantees a
  `"properties"` object (using `{}` rather than `[]` when empty, so it encodes
  as a JSON object, not an array);
- `normalise_tools()` walks `tools[]` and applies the above, handling both the
  chat/completions shape (`tools[].function.parameters`) and the
  Responses/legacy shape (`tools[].parameters`), which it also re-homes under
  `function`.

Only the **root** of each function's schema is coerced; nested schemas are left
untouched.

Check `?__debug` — the `last_request` object now includes `tools_summary`:

```json
"tools_summary": [
  { "i": 0, "name": "get_top_level_sub_projects", "parameters_type": "object", "has_properties": true },
  { "i": 1, "name": "read_file",                 "parameters_type": "object", "has_properties": true }
]
```

`parameters_type` must be `"object"` and `has_properties` should be `true` for
every entry. If you see `parameters_type: "(none)"` or `"array"`, the coercion
did not run — make sure the request path matched (`translated_responses: true`
for `/responses`, or a `chat/completions` path) and that `tools` is present in
the body.

### Model 404: "The sub-agent call failed (model 404)"

The panel uses a **sub-agent** for some tasks (and other internal calls) that
hardcodes an **OpenAI model name** (e.g. `gpt-4o-mini`). DeepSeek does not serve
those names, so the request is answered with a 404 and the panel reports:

```
The sub-agent call failed (model 404). I'll do the conversion myself...
```

The main agent usually recovers by doing the work itself, but the 404 recurs
whenever the panel makes an internal call with the hardcoded model.

The proxy now remaps the model **before** forwarding:

1. `resolve_model()` passes through any model in `DEEPSEEK_MODELS`
   (`deepseek-chat`, `deepseek-reasoner`, `deepseek-coder`) case-insensitively;
2. otherwise applies an explicit entry from `MODEL_ALIASES`;
3. otherwise, when `MODEL_FALLBACK = true`, falls back to `DEFAULT_MODEL`.

To pin specific names, add aliases (keys are case-insensitive):

```php
const MODEL_ALIASES = [
    'gpt-4o'      => 'deepseek-chat',
    'gpt-4o-mini' => 'deepseek-chat',
    'gpt-4.1'     => 'deepseek-chat',
];
```

With the default `MODEL_FALLBACK = true` you usually do not need aliases at all:
any unknown model name becomes `DEFAULT_MODEL`. Set `MODEL_FALLBACK = false` if
you would rather see the raw upstream 404 for unmapped names.

Verify with `?__debug` — the capture records both the requested and sent model,
plus a short error preview when upstream returns 4xx/5xx:

```json
"model_summary": { "requested": "gpt-4o-mini", "sent": "deepseek-chat" },
"upstream_status": 200,
"error_preview": null
```

- `requested` = what the panel asked for, `sent` = what went to DeepSeek. If
  `requested` differs from `sent`, the remap worked.
- If `upstream_status` is 404, read `error_preview` — DeepSeek includes the
  offending model name, e.g.
  `{"error":{"message":"The model 'xyz' does not exist ..."}}`. Add that name
  to `DEEPSEEK_MODELS` (if valid) or `MODEL_ALIASES`.

You can also confirm what DeepSeek actually offers:

```bash
curl -sS https://kantai3d.com/deepseek/models -H "Authorization: Bearer dummy"
```

Only the `id` values it lists will work without remapping.

### 404: `null`

`404: null` means the upstream (DeepSeek) returned **404 with an empty body** —
i.e. the panel requested a path DeepSeek does not expose. Unlike a 401/422 this
is a *routing* problem: the request reached DeepSeek fine, but at the wrong
path.

Common causes:

- the panel pinged the **base URL itself** (`GET /deepseek/`), which used to be
  forwarded as `https://api.deepseek.com/` → 404. The proxy now answers a bare
  root probe with `/models` (GET) / `/chat/completions` (POST);
- the base URL in the panel ends with `/v1` **and** the panel appends `/v1`,
  producing `/v1/v1/...`. The proxy now collapses repeated `/v1` segments;
- a duplicated or trailing slash (`/deepseek//models`). Repeated slashes are now
  collapsed.

Diagnose with `?__debug` — the response now shows both the raw and normalised
paths:

```json
{
  "request_uri": "/deepseek/v1/models",
  "original_path": "/models",
  "computed_path": "/models",
  "upstream_would": "https://api.deepseek.com/models",
  "last_request": { "upstream_status": 200, ... }
}
```

- `original_path` is after the subfolder strip; `computed_path` is what is sent
  upstream. If they differ, normalisation kicked in.
- In `last_request` check `upstream_url` and `upstream_status`. If
  `upstream_url` is `https://api.deepseek.com/` (bare), the panel hit the base
  URL — make sure it is configured as `https://kantai3d.com/deepseek` (no
  trailing `/v1`), and retry.
- If `last_request` still shows an old successful call, the failing request did
  not reach the proxy at all — re-check nginx routing / the base URL.

Verify directly:

```bash
# Should be 200 + model list
curl -i -sS https://kantai3d.com/deepseek/models -H "Authorization: Bearer dummy"
# Also 200 (repeated /v1 collapsed)
curl -i -sS https://kantai3d.com/deepseek/v1/v1/models -H "Authorization: Bearer dummy"
```

### Headers rendered as text in the browser

Hitting the endpoint with a plain browser GET (no auth, wrong method) shows the
raw response headers as text. That is expected: chat/completions only accepts
POST with an Authorization header. Test with the curl command above, not a
browser.

### Model detection fails: "Error reading response" on `/models`

The panel calls `GET /models` first, to list available models. If that returns a
200 with an **empty body**, the panel reports:

```
Request to "https://.../deepseek/models" using schema OpenAI-compatible failed
with error: Error reading response. Ensure your URL and schema are correct.
```

This was caused by an output/streaming bug: the proxy forwarded a 200 status but
dropped the JSON body. The body is now buffered and written in one shot after
`curl_exec`, so it is always delivered. Verify with:

```bash
curl -i -sS https://kantai3d.com/deepseek/models -H "Authorization: Bearer dummy"
```

Expected: `HTTP/1.1 200` and a JSON body like
`{"object":"list","data":[{"id":"deepseek-chat",...}, ...]}`.

If you still get an empty body, check the php error log for the line
`upstream responded 200 (body N bytes)` — `N` should be > 0. If `N` is 0 the
upstream itself returned nothing (very unlikely for `/models`).

### Status code not honoured / empty body

The proxy captures the upstream status and headers, then emits them *before*
the body, and writes the buffered body afterwards, so the client always sees
the correct status code and a JSON `Content-Type` even for empty error bodies.

## Path mapping

Paths are resolved defensively by `resolve_upstream_path()`:

- the subfolder (`SUBFOLDER_BASE = '/deepseek'`) is stripped **only at a path
  boundary**, so `/deepseekX/...` is not mangled;
- `/v1` segments are stripped, **including repeated ones** (`/v1/v1/models`);
- repeated slashes are collapsed;
- known endpoints are matched by the **end** of the path, so any prefix form
  works;
- a bare base-URL probe (`/`) is answered with `/models` (GET) or
  `/chat/completions` (POST) instead of being forwarded into a 404;
- `/responses` is translated to `/chat/completions`.

| Client request                                          | Upstream                                    |
|---------------------------------------------------------|---------------------------------------------|
| `https://kantai3d.com/deepseek/v1/chat/completions`     | `https://api.deepseek.com/chat/completions` |
| `https://kantai3d.com/deepseek/chat/completions`        | `https://api.deepseek.com/chat/completions` |
| `https://kantai3d.com/deepseek/v1/v1/chat/completions`  | `https://api.deepseek.com/chat/completions` |
| `https://kantai3d.com/deepseek/responses`               | `https://api.deepseek.com/chat/completions` (translated) |
| `https://kantai3d.com/deepseek/v1/models`               | `https://api.deepseek.com/models`           |
| `https://kantai3d.com/deepseek/models`                  | `https://api.deepseek.com/models`           |
| `https://kantai3d.com/deepseek/` (GET)                  | `https://api.deepseek.com/models`           |
| `https://kantai3d.com/deepseek/` (POST)                 | `https://api.deepseek.com/chat/completions` |

## Caveats

- Only the `developer` role is rewritten. `system`, `user`, `assistant`, `tool`
  and `latest_reminder` are passed through untouched.
- `/responses` translation is best-effort (text + common params); see the
  mapping table above.
- Other endpoints (`/models`, embeddings, ...) are proxied verbatim.
- This is meant to run behind TLS on a host you control; do not expose it to the
  public internet without adding authentication.
