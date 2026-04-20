const OpenAI = require('openai');
const { validateLLMResponse } = require('../utils/sanitize');

const client = new OpenAI({
  baseURL: process.env.LM_STUDIO_BASE_URL || 'http://127.0.0.1:1234/v1',
  apiKey: process.env.LM_STUDIO_API_TOKEN || 'dummy',
});

const MODEL = process.env.LM_STUDIO_MODEL || 'local-model';

/**
 * Summarize new messages, using an optional prior history summary as context.
 * Returns { summary, sentiment }.
 */
async function summarizeConversation(contactName, newMessages, historySummary = null) {
  if (!newMessages.length) {
    return { summary: 'No new messages.', sentiment: 'neutral' };
  }

  const newLines = newMessages
    .map(m => `[${new Date(m.timestamp * 1000).toLocaleTimeString()}] ${m.sender}: ${m.text}`)
    .join('\n');

  const historyBlock = historySummary
    ? `PREVIOUS SUMMARY (context only — do not re-summarize):\n"${historySummary}"\n\n`
    : '';

  const prompt = `You are a concise assistant that summarizes WhatsApp conversations.
${historyBlock}NEW MESSAGES to summarize (from conversation with "${contactName}"):
${newLines}

Respond with a JSON object only — no markdown, no explanation:
{"summary": "1-2 sentence summary in hebrew of the new messages", "sentiment": "one word: positive|neutral|negative|urgent|concerned|friendly"}`;

  try {
    const response = await client.chat.completions.create({
      model: MODEL,
      messages: [{ role: 'user', content: prompt }],
      temperature: 0.3,
      max_tokens: 180,
    });

    const raw = response.choices[0]?.message?.content ?? '';
    return validateLLMResponse(raw); // throws if invalid — caught below
  } catch (err) {
    console.error('[LLM] Error:', err.message);
    console.error('Prompt was:', prompt);
    console.error('Raw response was:', err.response?.data || 'N/A');
    return { summary: 'Summary unavailable.', sentiment: 'neutral' };
  }
}

module.exports = { summarizeConversation };
