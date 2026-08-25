import unittest
from unittest.mock import AsyncMock, patch

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
            scanned_history="",
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

    def test_uses_local_target_to_retrieve_relevant_scanned_global_context(self) -> None:
        result = backend.retrieve_relevant_history(
            context_text="can we move tomorrow's appointment?",
            vision_context={"reply_target": "reschedule appointment"},
            scanned_history=(
                "[older context]\nAlex: the dentist is booked for Tuesday\n"
                "Jamie: mornings are usually easiest for me"
            ),
            imported_history="Alex: that film was brilliant",
            automatic_history="Alex: pizza this weekend?",
            user_name="",
        )

        self.assertIn("dentist", result.lower())
        self.assertIn("Conversation scanned from the local reply target", result)


class ResponseModeTests(unittest.TestCase):
    def test_normalizes_supported_modes_and_aliases(self) -> None:
        self.assertEqual("flirty", backend.normalize_response_mode(" Flirty "))
        self.assertEqual("funny", backend.normalize_response_mode("humorous"))
        self.assertEqual("professional", backend.normalize_response_mode("formal"))
        self.assertEqual("casual", backend.normalize_response_mode("unknown"))

    def test_mock_suggestions_follow_mode(self) -> None:
        self.assertNotEqual(
            backend.mock_suggestions("serious"),
            backend.mock_suggestions("funny"),
        )
        self.assertEqual(3, len(backend.mock_reply_result("supportive")["suggestions"]))


class VisionBatchTests(unittest.IsolatedAsyncioTestCase):
    async def test_splits_long_scroll_capture_into_ordered_vision_batches(self) -> None:
        images = [
            {"mime_type": "image/jpeg", "base64": f"image-{index}"}
            for index in range(10)
        ]
        fake_batch = AsyncMock(side_effect=lambda **kwargs: {"batch": kwargs["batch_number"]})

        with patch.object(backend, "call_groq_vision_batch", fake_batch):
            result = await backend.call_groq_vision("Signal", "visible text", images)

        self.assertEqual(3, fake_batch.await_count)
        self.assertEqual(
            [{"batch": 1}, {"batch": 2}, {"batch": 3}],
            result["ordered_screen_batches"],
        )


class SuggestEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_preserves_source_app_and_normalizes_response_mode(self) -> None:
        request = backend.SuggestionRequest(
            source_app="Signal",
            response_mode="Humorous",
            context_text="Alex: are you free later?",
        )
        fake_result = {"suggestions": ["one", "two", "three"]}

        with (
            patch.object(backend, "GROQ_API_KEY", "test-key"),
            patch.object(backend, "call_groq", AsyncMock(return_value=fake_result)) as call,
        ):
            result = await backend.suggest(request)

        self.assertEqual(fake_result, result)
        self.assertEqual("Signal", call.await_args.kwargs["source_app"])
        self.assertEqual("funny", call.await_args.kwargs["response_mode"])


if __name__ == "__main__":
    unittest.main()
