# Generating Specs with an LLM

Valem can have an LLM compile a plain-text domain description into a runnable `ModelSpec`,
with an automatic validate-and-repair loop. This guide covers setup and the workflow; for the
exact prompt text see [../reference/llm-prompts.md](../reference/llm-prompts.md), and for every
LLM property see [../reference/configuration.md](../reference/configuration.md).

## Configure a provider

```bash
# Anthropic (default)
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -pl valem-web

# OpenAI
VALEM_LLM_PROVIDER=openai VALEM_LLM_MODEL=gpt-4o \
  VALEM_LLM_API_KEY=$OPENAI_API_KEY mvn spring-boot:run -pl valem-web

# Ollama (local, no key)
VALEM_LLM_PROVIDER=ollama VALEM_LLM_MODEL=llama3 mvn spring-boot:run -pl valem-web
```

Supported providers: `anthropic`, `openai`, `ollama`, `openrouter`, `groq`, `mistral`, `gemini`,
`cerebras`. Set `valem.llm.base-url` to target any OpenAI-compatible server (LM Studio,
vLLM, proxies).

## Workflow (UI or REST)

The UI **✦ Generate** button drives a human-in-the-loop flow: enter a model id + description →
**Preview Prompt** (editable) → **Send to Claude** → review/edit the spec → **Register Model**.

The same via REST:
```bash
# 1. Build the prompt (no LLM call)
curl -s -X POST http://localhost:8080/models/generate/preview -H 'Content-Type: application/json' \
  -d '{"modelId":"order","domainDescription":"E-commerce order with line items, subtotal, 8% tax, total"}'

# 2. Send the (optionally edited) prompt
curl -s -X POST http://localhost:8080/models/generate -H 'Content-Type: application/json' \
  -d '{"modelId":"order","prompt":"<prompt from step 1>"}'

# 3. Register the returned spec
curl -s -X POST http://localhost:8080/models -H 'Content-Type: application/json' -d '<spec from step 2>'
```

## How the repair loop works
`SpecGenerator` runs up to `valem.llm.max-retries` attempts (default 3): parse → validate →
run embedded `tests`; on any failure it feeds the errors back as a repair prompt. See
[../reference/llm-prompts.md](../reference/llm-prompts.md) for the four prompt types
(initial, validation-repair, test-repair, evolution).

## Security note
An LLM-generated spec is only as trustworthy as its review. Its pure logic is sandboxed JSONata, but
a spec may declare `server`/`llm` **effects** that perform outbound I/O — these run through the
built-in SSRF egress guard and are further governed by `valem.effects.allowed-hosts` /
`valem.effects.kinds.enabled`. Generation itself may also use the `web_fetch` tool. Review
[../reference/security-model.md](../reference/security-model.md) before accepting untrusted specs or
enabling web-fetch in production.
