from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoTokenizer, AutoModelForSequenceClassification
import torch

# Load FinBERT once at startup
tokenizer = AutoTokenizer.from_pretrained("ProsusAI/finbert")
model = AutoModelForSequenceClassification.from_pretrained("ProsusAI/finbert")

app = FastAPI()

class NewsRequest(BaseModel):
    text: str

@app.post("/sentiment")
def analyze_sentiment(req: NewsRequest):
    inputs = tokenizer(req.text, return_tensors="pt", truncation=True, padding=True)
    outputs = model(**inputs)
    probs = torch.nn.functional.softmax(outputs.logits, dim=-1)

    sentiment = ["negative", "neutral", "positive"]
    max_idx = torch.argmax(probs).item()

    # Score mapping: -1 negative, 0 neutral, +1 positive
    score = -1 if max_idx == 0 else (1 if max_idx == 2 else 0)

    return {
        "text": req.text,
        "sentiment": sentiment[max_idx],
        "score": score,
        "probabilities": probs[0].tolist()
    }
