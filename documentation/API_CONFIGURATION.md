# API configuration

AI Reality Scanner never ships with an API key. You use your own key, which is stored **encrypted on your phone** (AES-256-GCM, with the key held in the Android Keystore). It is only sent to the provider you select.

## Option A: Anthropic Claude (recommended)

1. Go to **console.anthropic.com**, sign in, open **API keys** and click **Create key**.
2. Add billing credit if your account needs it.
3. In the app, open **Settings → AI vision engine → Anthropic Claude**. Paste the key (it starts with `sk-ant-`) and tap **Save key**.
4. Choose a model:
   - `claude-sonnet-5-5` is the default. It balances quality and speed.
   - `claude-opus-5-5` gives the most detailed reasoning but is slower and costs more.
   - `claude-haiku-5-5` is the fastest and cheapest. It's good for Live mode.
   - You can type any other model ID in **Model ID (custom)**.
5. Tap **Test connection**. It sends a tiny text-only request, with no image.

Endpoint used: `POST https://api.anthropic.com/v1/messages` (header `anthropic-version: 2023-06-01`).

## Option B: OpenAI (or an OpenAI-compatible server)

1. Go to **platform.openai.com → API keys** and create a key.
2. In the app, open **Settings → OpenAI**, paste the key (`sk-…`) and tap **Save key**.
3. Pick a vision-capable model from the presets or type one in. Model names change often, so check OpenAI's model list if you get an HTTP 404.
4. **API base URL**: leave this as `https://api.openai.com/`, or point it at any OpenAI-compatible server, for example a self-hosted vision model.

Endpoint used: `POST {base}/v1/chat/completions`. Images are sent as `data:image/jpeg;base64,…`, and the token limit is set with `max_completion_tokens`.

## Option C: on-device (no key, offline)

This uses Google ML Kit on the phone and uploads nothing:
- Generic image labels (about 400 categories) with confidence scores
- Object detection
- Text recognition (Latin script, plus Devanagari when the language is Hindi)
- Barcode and QR decoding
- Translation (the model downloads once, about 30 MB per language)

It **cannot** name plant species, product models or landmarks, and it can't answer follow-up questions.

## What gets sent, and when

| Action | Sent to the cloud provider |
|---|---|
| Tap **Scan** / import a photo | One JPEG, resized to ≤ 1280 px (configurable), plus the mode prompt and any on-device OCR text |
| Ask a follow-up | The scan's image, a summary of the analysis, recent conversation turns and your question |
| Live mode with AI descriptions on | One frame ≤ 768 px, at most once per interval (default 6 s), and only when the scene has changed |
| Discover facts / Test connection | Text only |
| Compare | Both images (≤ 1024 px) |

Nothing is uploaded until you accept the consent dialog. To revoke consent, go to **Settings → Privacy → Allow cloud image analysis**.

## Cost control built in

- Images are resized and compressed before upload. In Settings you can set the max size (768–1600 px) and JPEG quality (60–95).
- Re-analysing the same image in the same mode and language returns a cached result (in-memory cache of 40 entries).
- Live mode never streams video. It uses a minimum interval plus scene-change detection, both adjustable in Settings.
- Discover facts are cached per topic per day.

## Troubleshooting

| Message | Fix |
|---|---|
| "API key was rejected (HTTP 401)" | Paste the key again. Check for spaces and make sure the key is still active. |
| "Model or endpoint not found (HTTP 404)" | The model ID is wrong or retired. Choose a preset or check the provider's model list. |
| "Rate limit or quota reached (HTTP 429)" | Wait a moment, or add credit or raise limits in the provider console. |
| "Image is too large (HTTP 413)" | Lower **Max upload image size**. |
| "No internet connection" | Connect, or switch to On-device mode. |
