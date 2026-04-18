/**
 * Sanitization utilities for all inbound data (Android notifications + LLM responses).
 */

function sanitizeText(value, maxLen = 500) {
  if (value == null) return '';
  const str = String(value);
  return str
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '')
    .replace(/<[^>]*>/g, '')
    .trim()
    .slice(0, maxLen);
}

function sanitizeMessagePayload(body) {
  return {
    conversationId: sanitizeText(body.conversationId, 128),
    contactName:    sanitizeText(body.contactName,    100),
    sender:         sanitizeText(body.sender,         100),
    text:           sanitizeText(body.text,           2000),
    timestamp:      Number.isFinite(Number(body.timestamp)) ? Number(body.timestamp) : Math.floor(Date.now() / 1000),
    isGroup:        Boolean(body.isGroup),
  };
}

function validateLLMResponse(raw) {
  if (typeof raw !== 'string' || raw.trim() === '') {
    throw new Error('LLM returned empty response');
  }
  const match = raw.match(/\{[\s\S]*?\}/);
  if (!match) throw new Error(`LLM response contains no JSON object. Raw: ${raw.slice(0, 200)}`);

  let parsed;
  try {
    parsed = JSON.parse(match[0]);
  } catch (e) {
    throw new Error(`LLM JSON parse failed: ${e.message}. Raw: ${match[0].slice(0, 200)}`);
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`LLM response is not a JSON object. Got: ${typeof parsed}`);
  }
  return {
    summary:   sanitizeText(String(parsed.summary   || ''), 400),
    sentiment: sanitizeText(String(parsed.sentiment || 'neutral'), 20),
  };
}

module.exports = { sanitizeText, sanitizeMessagePayload, validateLLMResponse };
