package com.calculator.scientific

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OpenAiSolverService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun solveFromPaperBitmap(
        bitmap: Bitmap,
        apiKey: String = BuildConfig.OPENAI_API_KEY
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val resolvedKey = apiKey.trim().ifEmpty { BuildConfig.OPENAI_API_KEY.trim() }
            if (resolvedKey.isBlank() || resolvedKey == "sk-replace-with-your-openai-api-key") {
                return@withContext Result.failure(
                    IllegalStateException("NO API KEY IN .env")
                )
            }

            try {
                val base64Jpeg = encodeBitmapToBase64(bitmap)

                val systemPrompt = """
                    You are a direct math and exam problem solver displayed on a compact 2-line scientific calculator LCD.
                    Rules:
                    1. Return ONLY the final answer(s). Do NOT include steps, explanations, introductions, or markdown formatting.
                    2. If there is a single question, output ONLY the final value, expression, or option letter/answer (e.g. "42" or "B) 15.6" or "x = 4, -2").
                    3. If there are multiple numbered questions on the paper, output each final answer on its own short line (e.g. "1: 24\n2: C\n3: 9.81").
                    4. Keep every line ultra-concise and plain text so it fits a calculator screen.
                """.trimIndent()

                val contentArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put(
                            "text",
                            "Solve the problem(s) on this paper. Output ONLY the final answer without any text explanation."
                        )
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Jpeg")
                            put("detail", "high")
                        })
                    })
                }

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    })
                }

                val payload = JSONObject().apply {
                    put("model", "gpt-4o")
                    put("messages", messagesArray)
                    put("temperature", 0.0)
                    put("max_tokens", 300)
                }

                val requestBody = payload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $resolvedKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = try {
                            JSONObject(rawBody).optJSONObject("error")?.optString("message")
                                ?: "HTTP ${response.code}"
                        } catch (_: Exception) {
                            "HTTP ${response.code}"
                        }
                        return@withContext Result.failure(RuntimeException(errMsg))
                    }

                    val json = JSONObject(rawBody)
                    val rawAnswer = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                        .replace("```", "")
                        .replace("\\(", "")
                        .replace("\\)", "")
                        .replace("\\[", "")
                        .replace("\\]", "")
                        .trim()

                    Result.success(rawAnswer.ifEmpty { "NO ANSWER" })
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 1400
        val width = bitmap.width
        val height = bitmap.height
        val scaledBitmap = if (width > maxDim || height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(width, height).toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
