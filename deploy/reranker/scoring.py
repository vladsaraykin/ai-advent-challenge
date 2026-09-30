"""Windowed ranking is separate from HTTP and model initialization for reproducible tests."""
import math


def score_documents(model, query, documents):
    tokenizer = model.tokenizer
    query = tokenizer.decode(tokenizer.encode(query, add_special_tokens=False)[:96], skip_special_tokens=True)
    pairs, owners = [], []
    for i, document in enumerate(documents):
        ids = tokenizer.encode(document, add_special_tokens=False)
        for start in range(0, len(ids), 336):
            pairs.append((query, tokenizer.decode(ids[start:start + 400], skip_special_tokens=True)))
            owners.append(i)
            if start + 400 >= len(ids):
                break
    if not pairs:
        raise ValueError("No passage tokens")
    scores = model.predict(pairs, batch_size=8, show_progress_bar=False)
    if len(scores) != len(pairs):
        raise ValueError("Invalid score count")
    result = [0.0] * len(documents)
    for owner, score in zip(owners, scores):
        value = float(score)
        if not math.isfinite(value) or value < 0 or value > 1:
            raise ValueError("Invalid score")
        result[owner] = max(result[owner], value)
    return result
