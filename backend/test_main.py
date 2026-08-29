import unittest
from unittest.mock import AsyncMock, MagicMock, patch

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
    async def test_vision_request_does_not_enforce_provider_json_mode(self) -> None:
        fake_post = AsyncMock(return_value="{}")
        image = {
            "mime_type": "image/jpeg",
            "base64": "encoded-image",
            "role": "reply_target",
            "title": "latest screen",
        }

        with patch.object(backend, "post_groq_chat_completion", fake_post):
            await backend.call_groq_vision_batch(
                source_app="Signal",
                context_text="visible text",
                images=[image],
                batch_number=1,
                batch_count=1,
            )

        payload = fake_post.await_args.args[0]
        self.assertNotIn("response_format", payload)

    def test_selects_reply_target_and_nearest_context_for_vision(self) -> None:
        images = [
            {"role": "reply_target" if index == 0 else "history", "id": str(index)}
            for index in range(12)
        ]

        result = backend.select_vision_images(images)

        self.assertEqual(["0", "1", "2"], [image["id"] for image in result])

    def test_selects_latest_images_when_reply_target_is_last(self) -> None:
        images = [
            {"role": "reply_target" if index == 11 else "history", "id": str(index)}
            for index in range(12)
        ]

        result = backend.select_vision_images(images)

        self.assertEqual(["9", "10", "11"], [image["id"] for image in result])

    async def test_splits_long_scroll_capture_into_ordered_vision_batches(self) -> None:
        images = [
            {"mime_type": "image/jpeg", "base64": f"image-{index}"}
            for index in range(10)
        ]
        fake_batch = AsyncMock(side_effect=lambda **kwargs: {"batch": kwargs["batch_number"]})

        with patch.object(backend, "call_groq_vision_batch", fake_batch):
            result = await backend.call_groq_vision("Signal", "visible text", images)

        self.assertEqual(4, fake_batch.await_count)
        self.assertEqual(
            [3, 3, 3, 1],
            [len(call.kwargs["images"]) for call in fake_batch.await_args_list],
        )
        self.assertEqual(
            [{"batch": 1}, {"batch": 2}, {"batch": 3}, {"batch": 4}],
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


class GroqRequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_retries_json_validation_failure_without_response_format(self) -> None:
        failed_response = MagicMock(status_code=400)
        failed_response.json.return_value = {
            "error": {"code": "json_validate_failed"}
        }
        successful_response = MagicMock(status_code=200)
        successful_response.json.return_value = {
            "choices": [{"message": {"content": '{"ok":true}'}}]
        }
        client = AsyncMock()
        client.post.side_effect = [failed_response, successful_response]
        client_context = MagicMock()
        client_context.__aenter__ = AsyncMock(return_value=client)
        client_context.__aexit__ = AsyncMock(return_value=None)
        payload = {
            "model": "vision-model",
            "response_format": {"type": "json_object"},
            "messages": [],
        }

        with patch.object(backend.httpx, "AsyncClient", return_value=client_context):
            content = await backend.post_groq_chat_completion(payload, "vision analysis")

        self.assertEqual('{"ok":true}', content)
        self.assertEqual(2, client.post.await_count)
        self.assertIn("response_format", client.post.await_args_list[0].kwargs["json"])
        self.assertNotIn("response_format", client.post.await_args_list[1].kwargs["json"])
        self.assertIn("response_format", payload)


if __name__ == "__main__":
    unittest.main()
