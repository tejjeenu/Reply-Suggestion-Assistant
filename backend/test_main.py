import unittest

import backend.main as backend


class ContextRetrievalTests(unittest.TestCase):
    def setUp(self) -> None:
        self.previous_model = backend.CONTEXT_EMBEDDING_MODEL
        backend.CONTEXT_EMBEDDING_MODEL = ""

    def tearDown(self) -> None:
        backend.CONTEXT_EMBEDDING_MODEL = self.previous_model

    def test_retrieves_relevant_history_and_user_style(self) -> None:
        result = backend.retrieve_relevant_history(
            context_text="should we get pizza tonight?",
            vision_context={},
            imported_history=(
                "Alex: did you book the dentist\n"
                "Jamie: yep all sorted\n"
                "Alex: pizza tonight?\n"
                "Jamie: yesss usual place?"
            ),
            automatic_history="Alex\nWhat time should we order pizza?",
            user_name="Jamie",
        )

        self.assertIn("pizza", result.lower())
        self.assertIn("Examples of the user's own writing style", result)
        self.assertIn("Jamie: yesss usual place?", result)


if __name__ == "__main__":
    unittest.main()
