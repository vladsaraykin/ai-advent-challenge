import unittest
from scoring import score_documents


class Tokenizer:
    def encode(self, text, **kwargs):
        return list(text)

    def decode(self, tokens, **kwargs):
        return "".join(tokens)


class FakeModel:
    tokenizer = Tokenizer()

    def predict(self, pairs, **kwargs):
        # Relevant evidence exists only at the end of a long passage.
        return [0.9 if "EVIDENCE" in text else 0.1 for query, text in pairs]


class ScoringTest(unittest.TestCase):
    def test_tail_evidence_is_not_lost_and_scores_preserve_document_order(self):
        self.assertEqual(score_documents(FakeModel(), "question", ["noise", "x" * 1000 + "EVIDENCE", "noise"]), [0.1, 0.9, 0.1])

    def test_invalid_model_scores_are_rejected(self):
        model = FakeModel()
        for value in [float("nan"), float("inf"), -0.1, 1.1]:
            model.predict = lambda pairs, **kwargs: [value] * len(pairs)
            with self.assertRaises(ValueError):
                score_documents(model, "q", ["document"])


if __name__ == "__main__":
    unittest.main()
