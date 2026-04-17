const { token } = require('morgan');
const OpenAI = require('openai');

const client = new OpenAI({
  baseURL: process.env.LM_STUDIO_BASE_URL,
  apiKey: process.env.LM_STUDIO_TOKEN,
});

const MODEL = process.env.LM_STUDIO_MODEL || 'local-model';

async function summarizeConversation(contactName, messages) {
  const msgLines = messages
    .map(m => `[${new Date(m.timestamp * 1000).toLocaleTimeString()}] ${m.sender}: ${m.text}`)
    .join('\n');

  const prompt = `You are a concise assistant that summarizes WhatsApp conversations.
Analyze the following conversation with "${contactName}" and produce:
1. SUMMARY: 1-2 sentences in hebrew capturing what was discussed
2. INTENT: What the other person is trying to communicate or achieve
3. SENTIMENT: One emoji corresponding to the sentiment of the conversation.

Keep it brief and direct. Focus on practical meaning.

Conversation:
${msgLines}

Respond in this exact JSON format:
{"summary": "...", "intent": "...", "sentiment": "..."}`;

  try {
    const response = await client.chat.completions.create({
      model: MODEL,
      messages: [{ role: 'user', content: prompt }],
      temperature: 0.3,
      max_tokens: 200,
    });

    const raw = response.choices[0].message.content.trim();
    // Extract JSON even if the model wraps it in markdown
    const match = raw.match(/\{[\s\S]*\}/);
    if (match) return JSON.parse(match[0]);
    return { summary: raw, intent: '', sentiment: 'neutral' };
  } catch (err) {
    console.error('[LLM] Error:', err.message);
    return { summary: 'Summary unavailable — LM Studio may not be running.', intent: '', sentiment: 'neutral' };
  }
}

module.exports = { summarizeConversation };
