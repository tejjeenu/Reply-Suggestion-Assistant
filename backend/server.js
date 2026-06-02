const http = require("http");

const PORT = Number(process.env.PORT || 3000);
const GROQ_API_KEY = process.env.GROQ_API_KEY || "";
const GROQ_MODEL = process.env.GROQ_MODEL || "llama-3.1-8b-instant";

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    return sendJson(res, 200, { ok: true });
  }

  if (req.method !== "POST" || req.url !== "/suggest") {
    return sendJson(res, 404, { error: "Not found" });
  }

  try {
    const body = await readJsonBody(req);
    const contextText = String(body.context_text || "").trim();

    if (!contextText) {
      return sendJson(res, 400, { error: "context_text is required" });
    }

    if (!GROQ_API_KEY) {
      return sendJson(res, 200, {
        suggestions: mockSuggestions()
      });
    }

    const suggestions = await callGroq({
      sourceApp: String(body.source_app || "Current app"),
      tone: String(body.tone || "casual, natural, helpful"),
      contextText
    });

    return sendJson(res, 200, { suggestions });
  } catch (error) {
    return sendJson(res, 500, {
      error: error && error.message ? error.message : String(error)
    });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Reply Assistant backend listening on http://0.0.0.0:${PORT}`);
  if (!GROQ_API_KEY) {
    console.log("GROQ_API_KEY is not set, returning mock suggestions.");
  }
});

async function callGroq({ sourceApp, tone, contextText }) {
  const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${GROQ_API_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model: GROQ_MODEL,
      temperature: 0.7,
      max_tokens: 350,
      messages: [
        {
          role: "system",
          content: [
            "You generate short reply suggestions from OCR text captured by a user.",
            "Return JSON only in this shape: {\"suggestions\":[\"...\",\"...\",\"...\"]}.",
            "Keep replies natural, specific to the context, and ready to send.",
            "Do not mention OCR, screenshots, or that you are an AI."
          ].join(" ")
        },
        {
          role: "user",
          content: JSON.stringify({
            source_app: sourceApp,
            tone,
            context_text: contextText,
            constraints: [
              "3 to 5 options",
              "usually under 30 words each",
              "avoid being needy, formal, or generic"
            ]
          })
        }
      ]
    })
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Groq returned HTTP ${response.status}: ${text}`);
  }

  const payload = JSON.parse(text);
  const content = payload.choices &&
    payload.choices[0] &&
    payload.choices[0].message &&
    payload.choices[0].message.content;

  return extractSuggestions(String(content || ""));
}

function extractSuggestions(content) {
  const cleaned = content
    .trim()
    .replace(/^```json/i, "")
    .replace(/^```/, "")
    .replace(/```$/, "")
    .trim();

  const parsed = tryParseJson(cleaned) || tryParseJson(extractFirstJsonObject(cleaned));
  if (parsed && Array.isArray(parsed.suggestions)) {
    return parsed.suggestions
      .map((value) => String(value).trim())
      .filter(Boolean)
      .slice(0, 5);
  }

  const lines = cleaned
    .split(/\r?\n/)
    .map((line) => line.replace(/^[-*\d.)\s]+/, "").trim())
    .filter((line) => line.length > 2)
    .slice(0, 5);

  return lines.length ? lines : mockSuggestions();
}

function tryParseJson(value) {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function extractFirstJsonObject(value) {
  const start = value.indexOf("{");
  const end = value.lastIndexOf("}");
  if (start === -1 || end === -1 || end <= start) return "";
  return value.slice(start, end + 1);
}

function mockSuggestions() {
  return [
    "Haha fair, I get what you mean.",
    "That sounds interesting. Tell me more.",
    "I like that. What made you think of it?"
  ];
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) {
        req.destroy();
        reject(new Error("Request body too large"));
      }
    });
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, statusCode, payload) {
  res.writeHead(statusCode, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*"
  });
  res.end(JSON.stringify(payload));
}
