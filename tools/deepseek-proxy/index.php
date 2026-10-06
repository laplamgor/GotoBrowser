<?php
/**
 * DeepSeek API compatibility proxy.
 *
 * Purpose
 * -------
 * Some clients (e.g. the Android Studio "agent" panel) send OpenAI-style chat
 * requests that use role "developer". The DeepSeek API rejects those with:
 *
 *   422: Failed to deserialize the JSON body into the target type:
 *        messages[0].role: unknown variant `developer`,
 *        expected one of `system`, `user`, `assistant`, `tool`, `latest_reminder`
 *
 * This script:
 *   1. rewrites any "developer" role to "system" (merging multiple developer
 *      messages into a single leading system message, because DeepSeek only
 *      accepts one system message);
 *   2. translates OpenAI "Responses API" requests (POST /responses, which
 *      DeepSeek does not implement) into chat/completions requests;
 *   3. normalises message content and tool parameter schemas to the shapes
 *      DeepSeek accepts (plain string / text|image_url blocks; object-rooted
 *      function parameters), see normalise_tools();
 *   4. can supply its own API key (FALLBACK_API_KEY) when the client only sends
 *      a placeholder, since some IDE panels do not forward the real key to a
 *      custom base URL.
 * Everything else is forwarded untouched to https://api.deepseek.com.
 *
 * Deployment (nginx + php-fpm), as a SUBFOLDER of an existing site
 * ----------------------------------------------------------------
 * Drop this file at the subfolder, e.g. <site-root>/deepseek/index.php, and
 * add a dedicated location block for the subfolder BEFORE the generic
 * static-asset regex location (nginx evaluates regex locations in order, and
 * you do not want /deepseek/... caught by the assets block):
 *
 *   # Existing site: root /3d; index index.php index.html;
 *   location /deepseek/ {
 *       try_files $uri /deepseek/index.php?$query_string;
 *   }
 *   location = /deepseek {
 *       return 301 /deepseek/;
 *   }
 *   location = /deepseek/index.php {
 *       include fastcgi_params;
 *       fastcgi_pass unix:/run/php/php8.2-fpm.sock;
 *       fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
 *       # streaming: do not buffer
 *       fastcgi_buffering off;
 *   }
 *
 * The subfolder path (SUBFOLDER_BASE) is stripped from the incoming path
 * before building the upstream URL, so a request to
 *   https://<site>/deepseek/v1/chat/completions
 * becomes
 *   https://api.deepseek.com/chat/completions
 *
 * Then in Android Studio's agent panel set the DeepSeek base URL to:
 *   https://kantai3d.com/deepseek/v1
 */

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

// Upstream DeepSeek API base. Do NOT add a trailing slash.
const UPSTREAM_BASE = 'https://api.deepseek.com';

// The subfolder this proxy is served from, i.e. the part of the URL between
// the site host and the /v1 segment. It is stripped from the incoming path
// before building the upstream URL. Set to '' if served from the site root.
// Example: for https://kantai3d.com/deepseek/v1/chat/completions use
// '/deepseek'.
const SUBFOLDER_BASE = '/deepseek';

// Optional fallback API key. Leave empty ('') to always use the key the client
// sends in the Authorization header. If set, it is used whenever the client
// does not send an Authorization header, OR when it sends a placeholder key
// (shorter than MIN_REAL_KEY_LEN, e.g. "dummy"). Keeping the key here lets
// Android Studio send a dummy key and keeps the real one only on the NAS.
const FALLBACK_API_KEY = '';

// A real DeepSeek key is "sk-" + 32 chars (35 total). Anything shorter than
// this is treated as a placeholder and replaced by FALLBACK_API_KEY.
const MIN_REAL_KEY_LEN = 20;

// When true, FALLBACK_API_KEY is ALWAYS used and the client's Authorization
// header (and any X-Api-Key) is ignored entirely. Use this when the panel only
// lets you enter a placeholder and you want the NAS to own the real key.
const FORCE_FALLBACK_KEY = false;

// Translate OpenAI "Responses API" requests (POST /responses) into DeepSeek's
// chat/completions API, since DeepSeek does not implement /responses. The
// translation is best-effort: it maps `instructions`->system, `input`->messages
// and the common request fields. Set false to disable.
const TRANSLATE_RESPONSES = true;

// Upstream path used when translating /responses.
const CHAT_COMPLETIONS_PATH = '/chat/completions';

// Default model used if the Responses request does not specify one.
const DEFAULT_MODEL = 'deepseek-chat';

// Roles that DeepSeek understands and that should never be touched.
const VALID_ROLES = ['system', 'user', 'assistant', 'tool', 'latest_reminder'];

// Verbose logging to the nginx/php error log. Set to false once it works.
const DEBUG_LOG = true;

// Optional: record a small summary of the most recent real (non-debug) request
// into DEBUG_CAPTURE_FILE, visible at <subfolder>/?__debug. This is the easiest
// way to see what the Android Studio panel actually sends without reading the
// php error log. Never stores the API key, only its presence/shape.
const DEBUG_CAPTURE_FILE = __DIR__ . '/.deepseek-proxy-last.json';

// Disable the capture to avoid writing to disk: set to false.
const DEBUG_CAPTURE = true;

// When true, the last capture is shown by ?__debug. Set false if you don't
// want the capture to be publicly readable on your site.
const DEBUG_SHOW_CAPTURE = true;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function log_debug(string $msg): void {
    if (DEBUG_LOG) {
        error_log('[deepseek-proxy] ' . $msg);
    }
}

/**
 * Persist a small, key-free summary of the most recent real request so it can
 * be inspected at ?__debug. Best-effort; failures are ignored.
 */
function capture_summary(array $data): void {
    if (!DEBUG_CAPTURE) return;
    $data['captured_at'] = gmdate('c');
    @file_put_contents(DEBUG_CAPTURE_FILE, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
}

/**
 * Build a compact, key-free description of the outgoing messages so the exact
 * shape sent upstream can be inspected at ?__debug. Shows role + content type
 * + (for strings) a short preview, so shape mismatches are easy to spot.
 */
function summarise_messages(array $messages): array {
    $out = [];
    foreach ($messages as $i => $m) {
        if (!is_array($m)) {
            $out[] = ['i' => $i, 'raw_type' => gettype($m)];
            continue;
        }
        $content = $m['content'] ?? null;
        if (is_string($content)) {
            $ctype = 'string';
            $preview = mb_substr($content, 0, 60);
        } elseif (is_array($content)) {
            $ctype = 'array';
            $blockTypes = [];
            foreach ($content as $b) {
                $blockTypes[] = is_array($b) ? gettype($b) . ':' . ($b['type'] ?? '?') : gettype($b);
            }
            $preview = '[' . implode(',', $blockTypes) . ']';
        } else {
            $ctype = gettype($content);
            $preview = '';
        }
        $out[] = [
            'i'            => $i,
            'role'         => $m['role'] ?? '?',
            'content_type' => $ctype,
            'has_tool'     => isset($m['tool_calls']) || isset($m['tool_call_id']),
            'preview'      => $preview,
        ];
    }
    return $out;
}

/**
 * Compact, key-free description of the outgoing tools so ?__debug shows the
 * function names and whether their parameter schema is an object root.
 */
function summarise_tools(?array $tools): ?array {
    if (!is_array($tools)) {
        return null;
    }
    $out = [];
    foreach ($tools as $i => $tool) {
        if (!is_array($tool)) {
            continue;
        }
        $fn  = isset($tool['function']) && is_array($tool['function']) ? $tool['function'] : $tool;
        $par = $fn['parameters'] ?? null;
        $rootType = is_array($par) ? ($par['type'] ?? '(none)') : gettype($par);
        $out[] = [
            'i'                => $i,
            'name'             => $fn['name'] ?? '?',
            'parameters_type'  => $rootType,
            'has_properties'   => is_array($par) && array_key_exists('properties', $par),
        ];
    }
    return $out;
}

function fail(int $code, string $type, string $message): void {
    http_response_code($code);
    header('Content-Type: application/json');
    echo json_encode([
        'error' => [
            'message' => $message,
            'type'    => $type,
            'code'    => $code,
        ],
    ]);
    exit;
}

/**
 * Rewrite the "messages" array so DeepSeek accepts it.
 *
 * - "developer" becomes "system".
 * - All system-role messages that appear at the start are merged into a single
 *   system message, since DeepSeek accepts only one system message.
 * - Non-leading system messages (rare, but valid in OpenAI semantics) are kept
 *   as-is to avoid changing conversation semantics.
 */
function normalise_messages(array $messages): array {
    $rewritten = false;

    // 1. developer -> system (anywhere in the array).
    foreach ($messages as &$msg) {
        if (is_array($msg) && isset($msg['role']) && $msg['role'] === 'developer') {
            $msg['role'] = 'system';
            $rewritten = true;
        }
    }
    unset($msg);

    // 2. Merge leading system messages into one.
    $leadingSystemParts = [];
    $rest = [];
    $seenNonSystem = false;
    foreach ($messages as $msg) {
        $role = is_array($msg) && isset($msg['role']) ? $msg['role'] : null;
        if (!$seenNonSystem && $role === 'system') {
            $content = isset($msg['content']) ? $msg['content'] : '';
            // Collapse any content (string or blocks) to plain text for the
            // merged system message. A system message must be a plain string
            // for DeepSeek.
            if (is_string($content)) {
                $leadingSystemParts[] = $content;
            } elseif (is_array($content)) {
                $textBits = [];
                foreach ($content as $part) {
                    if (is_string($part)) {
                        $textBits[] = $part;
                    } elseif (is_array($part) && isset($part['text'])) {
                        $textBits[] = (string) $part['text'];
                    }
                }
                $leadingSystemParts[] = implode("\n", $textBits);
            }
            $rewritten = true;
        } else {
            $seenNonSystem = true;
            $rest[] = $msg;
        }
    }

    if (count($leadingSystemParts) > 1) {
        $merged = [
            'role'    => 'system',
            'content' => implode("\n\n", $leadingSystemParts),
        ];
        return array_merge([$merged], $rest);
    }

    if ($rewritten && count($leadingSystemParts) === 1) {
        // Rebuild so the single system message stays first.
        return array_merge(
            [['role' => 'system', 'content' => $leadingSystemParts[0]]],
            $rest
        );
    }

    return $messages;
}

/**
 * DeepSeek (unlike OpenAI) strictly validates that every function's
 * `parameters` is a JSON-Schema OBJECT, i.e. its root has "type":"object".
 * The Android Studio panel sometimes sends `parameters` as an empty array `[]`
 * (or omits "type"/"properties"), which DeepSeek rejects with:
 *
 *   400: Invalid schema for function 'x': [] is not of type "object"
 *
 * This rewrites such schemas so the root is always an object. Nested schemas
 * are left untouched; only the top level of each function's `parameters` is
 * coerced.
 */
function normalise_schema_root($schema) {
    // PHP decodes JSON `[]` and `{}` both to array(); an empty array cannot be
    // distinguished, and either way DeepSeek wants an object here, so map any
    // non-array / empty value to a bare object schema.
    if (!is_array($schema) || $schema === []) {
        return ['type' => 'object', 'properties' => new stdClass()];
    }

    // Force the root type to "object".
    $schema['type'] = 'object';

    // Ensure "properties" exists and is an object even when empty.
    if (!array_key_exists('properties', $schema) || $schema['properties'] === null) {
        $schema['properties'] = new stdClass();
    } elseif (!is_array($schema['properties'])) {
        $schema['properties'] = new stdClass();
    } elseif ($schema['properties'] === []) {
        $schema['properties'] = new stdClass();
    }

    return $schema;
}

/**
 * Rewrite the request `tools` array so every function's parameter schema has
 * an object root (see normalise_schema_root). Returns true if anything changed.
 */
function normalise_tools(array &$payload): bool {
    if (!isset($payload['tools']) || !is_array($payload['tools'])) {
        return false;
    }

    $changed = false;
    foreach ($payload['tools'] as &$tool) {
        if (!is_array($tool)) {
            continue;
        }
        // Chat/completions shape: {"type":"function","function":{"name","parameters"}}
        if (isset($tool['function']) && is_array($tool['function'])) {
            $fixed = normalise_schema_root($tool['function']['parameters'] ?? null);
            $tool['function']['parameters'] = $fixed;
            $changed = true;
            continue;
        }
        // Responses/legacy shape: {"name","parameters"} directly on the tool.
        if (isset($tool['name'])) {
            $tool['parameters'] = normalise_schema_root($tool['parameters'] ?? null);
            $changed = true;
        }
    }
    unset($tool);

    return $changed;
}

/**
 * Return true when a path looks like a chat/completions request whose body
 * should be rewritten. Other endpoints (models, embeddings, ...) are proxied
 * untouched.
 */
function is_chat_path(string $path): bool {
    return str_contains($path, 'chat/completions');
}

/**
 * Return true for the OpenAI Responses API endpoint.
 */
function is_responses_path(string $path): bool {
    return $path === '/responses' || str_ends_with($path, '/responses');
}

/**
 * Resolve the client-supplied path into the upstream (DeepSeek) path.
 *
 * Clients vary: some send /v1/..., some /...; some probe /models, some
 * /v1/models; some duplicate the base (/v1/v1/...). Unknown paths used to be
 * forwarded verbatim, which produced a bare `404: null` from DeepSeek when the
 * panel asked for something the API does not expose. We normalise defensively
 * and operate on the end of the path (the API endpoint) rather than assuming a
 * fixed prefix structure.
 */
function resolve_upstream_path(string $path, string $method): string {
    // Collapse repeated slashes (/deepseek//models) then trim trailing slash
    // (except the root itself).
    $path = preg_replace('#/{2,}#', '/', $path);
    if ($path === '') {
        $path = '/';
    }

    // Remove ANY leading /v1 segments, however many times they repeat
    // (/v1/models, /v1/v1/models, ...). DeepSeek's base is the API root.
    while (str_starts_with($path, '/v1/')) {
        $path = substr($path, 3); // drop "/v1", keep the next "/..."
    }
    if ($path === '/v1') {
        $path = '/';
    }

    // Known endpoints, matched by the end of the path so any prefix form works.
    if (str_ends_with($path, '/chat/completions')) {
        return '/chat/completions';
    }
    if (str_ends_with($path, '/models')) {
        return '/models';
    }
    if (str_ends_with($path, '/embeddings')) {
        return '/embeddings';
    }
    if (str_ends_with($path, '/completions')) {
        return '/completions';
    }

    // Bare root or an empty path: the panel frequently pings the base URL
    // during setup. Answer with something meaningful instead of a 404.
    if ($path === '/' || $path === '') {
        return $method === 'POST' ? CHAT_COMPLETIONS_PATH : '/models';
    }

    // Unknown path: forward as-is (logged by the caller).
    return $path;
}

/**
 * Convert a Responses-API content value into a DeepSeek chat content value.
 *
 * Returns either a plain string (when everything is text and PLAIN_CONTENT is
 * acceptable) or an array of typed blocks. Note: DeepSeek's schema is an
 * internally tagged enum, so a message's content must be EITHER a string OR an
 * array of blocks, and block items must use DeepSeek's block types
 * ("text" / "image_url"), not OpenAI's ("input_text" / "input_image").
 */
function responses_content_to_chat($content): array {
    // Returns [contentValue, isBlockArray]
    if (is_string($content)) {
        return [$content, false];
    }
    if (!is_array($content)) {
        return ['', false];
    }

    $blocks = [];
    $allText = true;
    foreach ($content as $part) {
        if (is_string($part)) {
            $blocks[] = ['type' => 'text', 'text' => $part];
            continue;
        }
        if (!is_array($part)) {
            continue;
        }
        $ptype = $part['type'] ?? 'text';

        // Text-ish parts: input_text (Responses), output_text, text.
        if (isset($part['text']) && in_array($ptype, ['input_text', 'output_text', 'text', 'summary_text'], true)) {
            $blocks[] = ['type' => 'text', 'text' => (string) $part['text']];
            continue;
        }
        // Image parts: input_image (Responses) -> image_url (chat).
        if (($ptype === 'input_image' || $ptype === 'image_url') ) {
            $allText = false;
            $url = '';
            if (isset($part['image_url'])) {
                $url = is_array($part['image_url']) ? ($part['image_url']['url'] ?? '') : $part['image_url'];
            } elseif (isset($part['url'])) {
                $url = $part['url'];
            }
            if ($url !== '') {
                $blocks[] = ['type' => 'image_url', 'image_url' => ['url' => $url]];
            }
            continue;
        }
        // Fallback: if it has `text`, treat as text.
        if (isset($part['text'])) {
            $blocks[] = ['type' => 'text', 'text' => (string) $part['text']];
        }
    }

    if (empty($blocks)) {
        return ['', false];
    }

    // If everything is plain text, collapse to a single string. DeepSeek accepts
    // a string for pure-text content and this keeps the request simple.
    if ($allText) {
        $joined = '';
        foreach ($blocks as $b) {
            $joined .= $b['text'];
        }
        return [$joined, false];
    }

    // Otherwise keep blocks, but drop any text block whose type DeepSeek might
    // not accept in multimodal context: normalize every text block already done.
    return [$blocks, true];
}

/**
 * Convert a responses `input` value into a chat `messages` array.
 *
 * The Responses API accepts either:
 *   - a plain string, or
 *   - an array of items like
 *       {"type":"message","role":"user","content":[{"type":"input_text","text":"..."}]}
 *       {"type":"function_call", ...} / {"type":"function_call_output", ...}
 *
 * We map the message items to chat messages and convert their content into
 * DeepSeek-compatible strings or blocks.
 */
function responses_input_to_messages($input): array {
    $messages = [];

    if (is_string($input)) {
        return [['role' => 'user', 'content' => $input]];
    }
    if (!is_array($input)) {
        return $messages;
    }

    foreach ($input as $item) {
        if (is_string($item)) {
            $messages[] = ['role' => 'user', 'content' => $item];
            continue;
        }
        if (!is_array($item)) {
            continue;
        }

        // function_call_output -> tool message
        if (($item['type'] ?? null) === 'function_call_output') {
            $messages[] = [
                'role'         => 'tool',
                'tool_call_id' => $item['call_id'] ?? ($item['id'] ?? ''),
                'content'      => is_string($item['output'] ?? null)
                    ? $item['output']
                    : json_encode($item['output'] ?? '', JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            ];
            continue;
        }

        // function_call -> assistant message with tool_calls
        if (($item['type'] ?? null) === 'function_call') {
            $messages[] = [
                'role'       => 'assistant',
                'content'    => null,
                'tool_calls' => [[
                    'id'       => $item['call_id'] ?? ($item['id'] ?? ''),
                    'type'     => 'function',
                    'function' => [
                        'name'      => $item['name'] ?? '',
                        'arguments' => is_string($item['arguments'] ?? null)
                            ? $item['arguments']
                            : json_encode($item['arguments'] ?? new stdClass(), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
                    ],
                ]],
            ];
            continue;
        }

        // Regular message item.
        $role = $item['role'] ?? 'user';
        [$content] = responses_content_to_chat($item['content'] ?? '');

        $messages[] = ['role' => $role, 'content' => $content];
    }

    return $messages;
}

/**
 * Translate an OpenAI Responses API request body into a DeepSeek
 * chat/completions body. Best-effort mapping of the common fields.
 */
function translate_responses_request(array $req): array {
    $out = [];

    $out['model'] = $req['model'] ?? DEFAULT_MODEL;

    // `instructions` becomes the system message; `input` becomes the messages.
    $messages = [];
    if (isset($req['instructions']) && is_string($req['instructions']) && $req['instructions'] !== '') {
        $messages[] = ['role' => 'system', 'content' => $req['instructions']];
    }
    if (isset($req['input'])) {
        $messages = array_merge($messages, responses_input_to_messages($req['input']));
    }
    // A Responses client may also send a plain `messages` array (some do).
    if (empty($messages) && isset($req['messages']) && is_array($req['messages'])) {
        $messages = $req['messages'];
    }
    $out['messages'] = normalise_messages($messages);

    // Straight passthrough of common generation parameters.
    foreach (['temperature', 'top_p', 'max_tokens', 'stream', 'stop', 'frequency_penalty', 'presence_penalty', 'seed'] as $k) {
        if (array_key_exists($k, $req)) {
            $out[$k] = $req[$k];
        }
    }
    // Responses uses `max_output_tokens` -> chat `max_tokens`.
    if (!isset($out['max_tokens']) && isset($req['max_output_tokens'])) {
        $out['max_tokens'] = $req['max_output_tokens'];
    }
    // DeepSeek rejects stream_options; drop it if present.
    unset($out['stream_options']);

    // Responses tools ({"type":"function","name","parameters"}) map to the
    // chat/completions tools shape. Copy them over, then normalise.
    if (isset($req['tools']) && is_array($req['tools'])) {
        $out['tools'] = $req['tools'];
        // Responses function tools: lift name/parameters under "function".
        foreach ($out['tools'] as &$tool) {
            if (is_array($tool)
                && ($tool['type'] ?? null) === 'function'
                && isset($tool['name'])
                && !isset($tool['function'])) {
                $tool['function'] = [
                    'name'        => $tool['name'],
                    'description' => $tool['description'] ?? '',
                    'parameters'  => $tool['parameters'] ?? null,
                ];
                unset($tool['name'], $tool['description'], $tool['parameters']);
            }
        }
        unset($tool);
        normalise_tools($out);
    }

    return $out;
}

// ---------------------------------------------------------------------------
// Build upstream URL from the incoming request
// ---------------------------------------------------------------------------

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

// REQUEST_URI contains the full path + query as requested by the client.
$requestUri = $_SERVER['REQUEST_URI'] ?? '/';
$query = '';
if (($qpos = strpos($requestUri, '?')) !== false) {
    $query = substr($requestUri, $qpos + 1);
    $rawPath = substr($requestUri, 0, $qpos);
} else {
    $rawPath = $requestUri;
}

// Strip the subfolder prefix this proxy is mounted under, e.g. /deepseek.
// Only strip at a path boundary, so /deepseekX/... is not mangled.
$path = $rawPath;
if (SUBFOLDER_BASE !== '') {
    if ($path === SUBFOLDER_BASE) {
        $path = '/';
    } elseif (str_starts_with($path, SUBFOLDER_BASE . '/')) {
        $path = substr($path, strlen(SUBFOLDER_BASE));
    }
}
// Ensure a leading slash (empty string, or "/v1/..." after stripping).
if ($path === '' || $path === '/') {
    $path = '/';
} elseif (!str_starts_with($path, '/')) {
    $path = '/' . $path;
}

// Normalise the path into the DeepSeek upstream path. This strips any /v1
// segments (even repeated), matches known endpoints by suffix, and answers a
// bare base-URL probe instead of forwarding it into a 404.
$originalPath = $path;
$path = resolve_upstream_path($path, $method);
if ($path !== $originalPath) {
    log_debug("path normalised: $originalPath -> $path");
}

// Translate the OpenAI Responses API path into DeepSeek's chat/completions.
$translatedResponses = false;
if (TRANSLATE_RESPONSES && is_responses_path($originalPath)) {
    $path = CHAT_COMPLETIONS_PATH;
    $translatedResponses = true;
}

$upstreamUrl = UPSTREAM_BASE . $path . ($query !== '' ? '?' . $query : '');
log_debug("$method $requestUri -> $upstreamUrl" . ($translatedResponses ? ' (responses->chat)' : ''));

// ---------------------------------------------------------------------------
// Read and (optionally) rewrite the request body
// ---------------------------------------------------------------------------

$rawBody = file_get_contents('php://input');
$body = $rawBody;
$rewroteRoles = false;
$rolesBefore = null;
$rolesAfter = null;
$messagesSummary = null;
$toolsSummary = null;

if ($rawBody !== '' && $translatedResponses) {
    // Translate a Responses API body into a chat/completions body.
    $json = json_decode($rawBody, true);
    if (json_last_error() === JSON_ERROR_NONE && is_array($json)) {
        $translated = translate_responses_request($json);
        $rolesBefore = array_map(
            fn($m) => is_array($m) && isset($m['role']) ? $m['role'] : '?',
            $translated['messages'] ?? []
        );
        $rolesAfter = $rolesBefore;
        $rewroteRoles = true;
        $messagesSummary = summarise_messages($translated['messages'] ?? []);
        $toolsSummary = summarise_tools($translated['tools'] ?? null);
        log_debug('responses body translated; roles ' . implode(',', $rolesBefore));
        $body = json_encode($translated, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    } else {
        log_debug('responses body is not JSON; forwarding as-is');
    }
} elseif ($rawBody !== '' && is_chat_path($path)) {
    $json = json_decode($rawBody, true);
    if (json_last_error() === JSON_ERROR_NONE && is_array($json)) {
        $touched = false;
        if (isset($json['messages']) && is_array($json['messages'])) {
            $rolesBefore = array_map(
                fn($m) => is_array($m) && isset($m['role']) ? $m['role'] : '?',
                $json['messages']
            );
            $json['messages'] = normalise_messages($json['messages']);
            $rolesAfter = array_map(
                fn($m) => is_array($m) && isset($m['role']) ? $m['role'] : '?',
                $json['messages']
            );
            $rewroteRoles = $rolesBefore !== $rolesAfter;
            $messagesSummary = summarise_messages($json['messages']);
            log_debug('roles ' . implode(',', $rolesBefore) . ' => ' . implode(',', $rolesAfter));
            $touched = true;
        }
        // DeepSeek strictly validates function parameter schemas.
        if (normalise_tools($json)) {
            log_debug('normalised tools parameter schemas');
            $touched = true;
        }
        if (isset($json['tools'])) {
            $toolsSummary = summarise_tools(is_array($json['tools']) ? $json['tools'] : null);
        }
        if ($touched) {
            $body = json_encode($json, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        } else {
            log_debug('body present but nothing to rewrite; forwarding as-is');
        }
    } else {
        log_debug('body present but not a chat JSON object; forwarding as-is');
    }
}

// ---------------------------------------------------------------------------
// Forward headers
// ---------------------------------------------------------------------------

// Collect incoming headers (works under php-fpm/nginx).
$headers = [];
if (function_exists('getallheaders')) {
    foreach (getallheaders() as $name => $value) {
        $headers[$name] = $value;
    }
} else {
    foreach ($_SERVER as $key => $value) {
        if (str_starts_with($key, 'HTTP_')) {
            $name = str_replace(' ', '-', ucwords(strtolower(str_replace('_', ' ', substr($key, 5)))));
            $headers[$name] = $value;
        }
    }
}

// ---------------------------------------------------------------------------
// Debug endpoint: GET <subfolder>/?__debug reports what the proxy received
// without leaking the API key. Safe to leave in; it never proxies upstream.
// ---------------------------------------------------------------------------
if (isset($_GET['__debug'])) {
    header('Content-Type: application/json');
    $safeHeaders = [];
    foreach ($headers as $name => $value) {
        $lower = strtolower($name);
        if ($lower === 'authorization' || $lower === 'x-api-key') {
            // Show only shape: "Bearer sk-…(len 35)" — never the key itself.
            $prefix = str_contains($value, ' ') ? explode(' ', $value, 2)[0] : '(raw)';
            $safeHeaders[$name] = "$prefix …(len " . strlen($value) . ")";
        } else {
            $safeHeaders[$name] = $value;
        }
    }
    echo json_encode([
        'ok'              => true,
        'method'          => $method,
        'request_uri'     => $requestUri,
        'original_path'   => $originalPath ?? null,
        'computed_path'   => $path ?? null,
        'upstream_would'  => UPSTREAM_BASE . ($path ?? '') . ($query !== '' ? '?' . $query : ''),
        'subfolder_base'  => SUBFOLDER_BASE,
        'fallback_key_set'=> FALLBACK_API_KEY !== '',
        'headers_received'=> $safeHeaders,
        'last_request'    => (DEBUG_SHOW_CAPTURE && is_file(DEBUG_CAPTURE_FILE))
            ? json_decode((string) @file_get_contents(DEBUG_CAPTURE_FILE), true)
            : 'capture disabled or none yet',
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    exit;
}

// Headers we must not forward verbatim.
$skip = ['host', 'content-length', 'connection', 'accept-encoding'];

$curlHeaders = [];
$authValue = null;
foreach ($headers as $name => $value) {
    $lower = strtolower($name);
    if (in_array($lower, $skip, true)) {
        continue;
    }
    // Diagnostic: note any bearer-looking header without logging its value.
    if (stripos($value, 'bearer ') === 0) {
        $authValue = $value;
        log_debug("found auth in header '$name' (len=" . strlen($value) . ")");
    }
    // A client might use X-Api-Key instead of Authorization. Rewrite it so
    // DeepSeek always receives a proper Authorization header.
    if ($lower === 'x-api-key') {
        $authValue = (str_starts_with($value, 'Bearer ') || str_starts_with($value, 'bearer '))
            ? $value
            : 'Bearer ' . $value;
        $curlHeaders[] = 'Authorization: ' . $authValue;
        continue;
    }
    if ($lower === 'authorization') {
        $authValue = $value;
    }
    $curlHeaders[] = "$name: $value";
}

// Decide which key to actually use upstream.
$useFallback = false;
if (FORCE_FALLBACK_KEY && FALLBACK_API_KEY !== '') {
    $useFallback = true;
} elseif ($authValue === null && FALLBACK_API_KEY !== '') {
    $useFallback = true;
} elseif ($authValue !== null && FALLBACK_API_KEY !== '') {
    // Replace placeholder keys (e.g. "Bearer dummy") with the real one.
    $bare = preg_replace('/^Bearer\s+/i', '', $authValue);
    if (strlen(trim($bare)) < MIN_REAL_KEY_LEN) {
        log_debug('client sent a placeholder key (len ' . strlen(trim($bare)) . '); using FALLBACK_API_KEY');
        $useFallback = true;
    }
}

if ($useFallback) {
    // Remove any client-supplied Authorization/X-Api-Key and inject ours.
    $curlHeaders = array_values(array_filter($curlHeaders, function ($h) {
        return !preg_match('/^(authorization|x-api-key):/i', $h);
    }));
    $curlHeaders[] = 'Authorization: Bearer ' . FALLBACK_API_KEY;
    $authValue = 'Bearer ' . FALLBACK_API_KEY;
    log_debug('using FALLBACK_API_KEY');
} elseif ($authValue === null) {
    log_debug('WARNING: no auth header from client and FALLBACK_API_KEY is empty');
}

// Always request identity encoding so we don't have to gunzip before rewriting.
$curlHeaders[] = 'Accept-Encoding: identity';

// Ensure a Content-Type for bodies with content.
if ($body !== '' ) {
    $hasContentType = false;
    foreach ($curlHeaders as $h) {
        if (stripos($h, 'content-type:') === 0) { $hasContentType = true; break; }
    }
    if (!$hasContentType) {
        $curlHeaders[] = 'Content-Type: application/json';
    }
}

if (!function_exists('curl_init')) {
    fail(500, 'proxy_error', 'PHP cURL extension is not enabled.');
}

// Record what we are about to send upstream (key-free).
capture_summary([
    'phase'                => 'request',
    'method'               => $method,
    'request_uri'          => $requestUri,
    'upstream_url'         => $upstreamUrl,
    'translated_responses' => $translatedResponses,
    'client_sent_auth'     => $authValue !== null,
    'auth_shape'           => $authValue !== null
        ? (str_contains($authValue, ' ') ? explode(' ', $authValue, 2)[0] . ' …(len ' . strlen($authValue) . ')' : '(raw, len ' . strlen($authValue) . ')')
        : null,
    'using_fallback'       => $useFallback,
    'rewrote_roles'        => $rewroteRoles,
    'roles_before'         => $rolesBefore,
    'roles_after'          => $rolesAfter,
    'messages_summary'     => $messagesSummary,
    'tools_summary'        => $toolsSummary,
]);

// ---------------------------------------------------------------------------
// Perform the upstream request
// ---------------------------------------------------------------------------

// Response state, filled in by the header callback.
$upstreamStatus = 0;
$upstreamHeaders = [];

$ch = curl_init($upstreamUrl);
curl_setopt_array($ch, [
    CURLOPT_CUSTOMREQUEST  => $method,
    CURLOPT_HTTPHEADER     => $curlHeaders,
    CURLOPT_RETURNTRANSFER => false,    // body goes to WRITEFUNCTION, not a return value
    CURLOPT_HEADER         => false,    // headers handled by HEADERFUNCTION
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_TIMEOUT        => 0,        // allow long streaming requests
    CURLOPT_CONNECTTIMEOUT => 15,
    CURLOPT_ENCODING       => '',       // let curl decode whatever comes back
]);

if ($body !== '' && in_array($method, ['POST', 'PUT', 'PATCH'], true)) {
    curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
}

// Collect headers first; do NOT emit them yet. We must know the final status
// and Content-Type before any body byte is written, otherwise the status can
// be lost and the client sees a bodyless error.
curl_setopt($ch, CURLOPT_HEADERFUNCTION, function ($ch, $headerLine) use (&$upstreamStatus, &$upstreamHeaders) {
    $len = strlen($headerLine);
    $trimmed = trim($headerLine);
    if ($trimmed === '') {
        return $len; // end of one header block
    }
    if (stripos($trimmed, 'HTTP/') === 0) {
        $parts = explode(' ', $trimmed, 3);
        $upstreamStatus = isset($parts[1]) ? (int) $parts[1] : 0;
        $upstreamHeaders = []; // new block (e.g. after a 100-continue)
        return $len;
    }
    $upstreamHeaders[] = $trimmed;
    return $len;
});

// Buffer the body, then emit status + headers + body together. This avoids the
// "401: null" symptom where headers and body interleave. (Streaming SSE still
// works because we flush after each chunk once headers are out.)
$responseReady = false;
$emitHeaders = function () use (&$responseReady, &$upstreamStatus, &$upstreamHeaders) {
    if ($responseReady) return;
    $responseReady = true;
    if (!headers_sent()) {
        http_response_code($upstreamStatus > 0 ? $upstreamStatus : 502);
        foreach ($upstreamHeaders as $h) {
            $lower = strtolower($h);
            if (str_starts_with($lower, 'transfer-encoding:')
                || str_starts_with($lower, 'connection:')
                || str_starts_with($lower, 'content-length:')
                || str_starts_with($lower, 'content-encoding:')) {
                continue;
            }
            header($h, false);
        }
        // Always ensure a content type for JSON error bodies.
        $hadCt = false;
        foreach ($upstreamHeaders as $h) {
            if (stripos($h, 'content-type:') === 0) { $hadCt = true; break; }
        }
        if (!$hadCt) {
            header('Content-Type: application/json');
        }
    }
};

// Accumulate the body ourselves rather than trusting echo/streaming to flush
// through php-fpm. We echo at the end (see below). This guarantees the body is
// delivered even for small JSON responses like /models, where chunked
// streaming through a buffering FPM/nginx chain can silently drop the payload.
$bodyBuf = '';
curl_setopt($ch, CURLOPT_WRITEFUNCTION, function ($ch, $chunk) use (&$bodyBuf, $emitHeaders) {
    // Emit status + headers as soon as the first body bytes arrive.
    $emitHeaders();
    $bodyBuf .= $chunk;
    return strlen($chunk);
});

// Make sure nothing swallows our output before we flush it at the end.
@ini_set('zlib.output_compression', '0');
@ini_set('output_buffering', '0');
while (ob_get_level() > 0) {
    @ob_end_clean();
}

$ok = curl_exec($ch);
$curlErr = curl_error($ch);
curl_close($ch);

if ($ok === false) {
    log_debug('curl error: ' . $curlErr);
    capture_summary([
        'phase'                => 'result',
        'method'               => $method,
        'request_uri'          => $requestUri,
        'upstream_url'         => $upstreamUrl,
        'translated_responses' => $translatedResponses,
        'curl_error'           => $curlErr,
        'upstream_status'      => $upstreamStatus,
        'client_sent_auth'     => $authValue !== null,
        'using_fallback'       => $useFallback,
    ]);
    if (!$responseReady) {
        fail(502, 'proxy_error', 'Upstream request failed: ' . $curlErr);
    }
    exit;
}

// Headers may never have been emitted if the response had an empty body
// (e.g. a bare error with no JSON). Emit them now so the status is honoured.
$emitHeaders();

// Write the buffered upstream body to the client. This is the only place the
// body is produced, so it is always delivered.
if ($bodyBuf !== '') {
    echo $bodyBuf;
}

log_debug("upstream responded $upstreamStatus (body " . strlen($bodyBuf) . " bytes)");

capture_summary([
    'phase'                => 'result',
    'method'               => $method,
    'request_uri'          => $requestUri,
    'upstream_url'         => $upstreamUrl,
    'translated_responses' => $translatedResponses,
    'upstream_status'      => $upstreamStatus,
    'client_sent_auth'     => $authValue !== null,
    'auth_shape'           => $authValue !== null
        ? (str_contains($authValue, ' ') ? explode(' ', $authValue, 2)[0] . ' …(len ' . strlen($authValue) . ')' : '(raw, len ' . strlen($authValue) . ')')
        : null,
    'using_fallback'       => $useFallback,
    'rewrote_roles'        => $rewroteRoles,
    'roles_before'         => $rolesBefore,
    'roles_after'          => $rolesAfter,
]);
